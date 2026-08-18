package me.rerere.ai.provider

import kotlinx.coroutines.flow.collect
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.StreamChunkHandler
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

/**
 * Runs small, explicit probes against a provider. These probes are intended for settings diagnostics only and never
 * inspect normal conversations or capture raw HTTP traffic.
 */
object ProviderDiagnostics {
    suspend fun <T : ProviderSetting> run(
        provider: Provider<T>,
        setting: T,
        model: Model,
    ): ProviderDiagnosticsReport {
        val commonParams = TextGenerationParams(
            model = model,
            customHeaders = model.customHeaders,
            customBody = model.customBodies,
        )
        val discovery = timed {
            provider.listModels(setting).size
        }
        val health = timed {
            val result = provider.generateText(
                providerSetting = setting,
                messages = diagnosticMessages(),
                params = commonParams,
            )
            check(result.message.toText().isNotBlank()) { "Provider returned no text for the health probe." }
        }

        val streamStart = System.nanoTime()
        var firstOutputLatencyMillis: Long? = null
        var firstTextTokenLatencyMillis: Long? = null
        val recordedChunks = mutableListOf<StreamChunk>()
        val streaming = runCatching {
            provider.streamText(
                providerSetting = setting,
                messages = diagnosticMessages(),
                params = commonParams,
            ).collect { chunk ->
                if (firstOutputLatencyMillis == null && chunk.isModelOutputEvent()) {
                    firstOutputLatencyMillis = elapsedMillis(streamStart)
                }
                if (firstTextTokenLatencyMillis == null && chunk.isTextToken()) {
                    firstTextTokenLatencyMillis = elapsedMillis(streamStart)
                }
                ProviderProtocolTraceRecorder.sanitize(chunk)?.let { sanitized ->
                    if (recordedChunks.size < MAX_RECORDED_CHUNKS) {
                        recordedChunks += sanitized
                    }
                }
            }
        }.fold(
            onSuccess = {
                ProviderStreamingDiagnostic(
                    status = ProviderDiagnosticStatus.SUCCESS,
                    latencyMillis = elapsedMillis(streamStart),
                    firstOutputLatencyMillis = firstOutputLatencyMillis,
                    firstTokenLatencyMillis = firstTextTokenLatencyMillis,
                )
            },
            onFailure = { error ->
                ProviderStreamingDiagnostic(
                    status = ProviderDiagnosticStatus.FAILURE,
                    latencyMillis = elapsedMillis(streamStart),
                    firstOutputLatencyMillis = firstOutputLatencyMillis,
                    firstTokenLatencyMillis = firstTextTokenLatencyMillis,
                    error = error.toSafeDiagnosticMessage(),
                )
            },
        )

        val toolCalling = timed {
            provider.generateText(
                providerSetting = setting,
                messages = listOf(
                    UIMessage.system("Use the requested diagnostic tool and do not add commentary."),
                    UIMessage.user("Call diagnostic_ping now."),
                ),
                params = commonParams.copy(
                    tools = listOf(
                        Tool(
                            name = "diagnostic_ping",
                            description = "Verifies that the model can issue a client tool call.",
                            execute = { emptyList() },
                        )
                    )
                ),
            ).message.parts.any { it is UIMessagePart.Tool }
        }

        val normalizedToolResult = when {
            toolCalling.error != null -> ProviderDiagnosticCheck.failure(
                latencyMillis = toolCalling.latencyMillis,
                error = toolCalling.error,
            )
            toolCalling.value == true -> ProviderDiagnosticCheck.success(toolCalling.latencyMillis)
            else -> ProviderDiagnosticCheck.unsupported(toolCalling.latencyMillis)
        }
        val trace = recordedChunks.takeIf { it.isNotEmpty() }?.let {
            ProviderProtocolTraceRecorder.record(
                protocol = setting.protocolId(),
                modelId = model.modelId,
                chunks = it,
            )
        }

        return ProviderDiagnosticsReport(
            health = health.asCheck(),
            modelDiscovery = ProviderModelDiscovery(
                status = discovery.status(),
                latencyMillis = discovery.latencyMillis,
                modelCount = discovery.value,
                error = discovery.error,
            ),
            streaming = streaming,
            toolCalling = normalizedToolResult,
            capabilities = ProviderCapabilityProbe(
                text = health.error == null,
                streaming = streaming.status == ProviderDiagnosticStatus.SUCCESS,
                toolCalling = normalizedToolResult.status == ProviderDiagnosticStatus.SUCCESS,
                reasoning = recordedChunks.any { it is StreamChunk.ReasoningDelta },
            ),
            protocolTrace = trace,
        )
    }

    private fun diagnosticMessages() = listOf(
        UIMessage.system("Reply with exactly OK."),
        UIMessage.user("OK"),
    )

    private fun StreamChunk.isModelOutputEvent(): Boolean = when (this) {
        is StreamChunk.TextStart,
        is StreamChunk.TextDelta,
        is StreamChunk.ReasoningStart,
        is StreamChunk.ReasoningDelta,
        is StreamChunk.ToolCallStart,
        is StreamChunk.ToolCallDelta -> true
        else -> false
    }

    private fun StreamChunk.isTextToken(): Boolean = this is StreamChunk.TextDelta && text.isNotEmpty()
}

@Serializable
data class ProviderDiagnosticsReport(
    val health: ProviderDiagnosticCheck,
    val modelDiscovery: ProviderModelDiscovery,
    val streaming: ProviderStreamingDiagnostic,
    val toolCalling: ProviderDiagnosticCheck,
    val capabilities: ProviderCapabilityProbe,
    val protocolTrace: ProviderProtocolTrace? = null,
)

@Serializable
enum class ProviderDiagnosticStatus {
    SUCCESS,
    FAILURE,
    UNSUPPORTED,
}

@Serializable
data class ProviderDiagnosticCheck(
    val status: ProviderDiagnosticStatus,
    val latencyMillis: Long? = null,
    val error: String? = null,
) {
    companion object {
        fun success(latencyMillis: Long) = ProviderDiagnosticCheck(
            status = ProviderDiagnosticStatus.SUCCESS,
            latencyMillis = latencyMillis,
        )

        fun failure(latencyMillis: Long, error: String) = ProviderDiagnosticCheck(
            status = ProviderDiagnosticStatus.FAILURE,
            latencyMillis = latencyMillis,
            error = error,
        )

        fun unsupported(latencyMillis: Long) = ProviderDiagnosticCheck(
            status = ProviderDiagnosticStatus.UNSUPPORTED,
            latencyMillis = latencyMillis,
        )
    }
}

@Serializable
data class ProviderModelDiscovery(
    val status: ProviderDiagnosticStatus,
    val latencyMillis: Long? = null,
    val modelCount: Int? = null,
    val error: String? = null,
)

@Serializable
data class ProviderStreamingDiagnostic(
    val status: ProviderDiagnosticStatus,
    val latencyMillis: Long? = null,
    val firstOutputLatencyMillis: Long? = null,
    val firstTokenLatencyMillis: Long? = null,
    val error: String? = null,
)

@Serializable
data class ProviderCapabilityProbe(
    val text: Boolean,
    val streaming: Boolean,
    val toolCalling: Boolean,
    val reasoning: Boolean,
)

fun Model.withDetectedCapabilities(probe: ProviderCapabilityProbe): Model {
    val updatedAbilities = abilities.toMutableSet().apply {
        if (probe.toolCalling) add(ModelAbility.TOOL)
        if (probe.reasoning) add(ModelAbility.REASONING)
    }.toList()
    val updatedOutputModalities = outputModalities.toMutableSet().apply {
        if (probe.text) add(Modality.TEXT)
    }.toList()
    return copy(
        outputModalities = updatedOutputModalities,
        abilities = updatedAbilities,
    )
}

/**
 * A portable, normalized protocol trace. It is suitable for replay after a decoder change, but intentionally omits
 * raw HTTP requests, headers and unbounded payloads.
 */
@Serializable
data class ProviderProtocolTrace(
    val schemaVersion: Int = 1,
    val protocol: String,
    val modelId: String,
    val recordedAtEpochMillis: Long = System.currentTimeMillis(),
    val chunks: List<StreamChunk>,
    val expectation: ProviderProtocolTraceExpectation,
)

@Serializable
data class ProviderProtocolTraceExpectation(
    val finishCount: Int,
    val textDeltaCount: Int,
    val reasoningDeltaCount: Int,
    val toolCallStartCount: Int,
) {
    companion object {
        fun from(chunks: List<StreamChunk>) = ProviderProtocolTraceExpectation(
            finishCount = chunks.count { it is StreamChunk.Finish },
            textDeltaCount = chunks.count { it is StreamChunk.TextDelta },
            reasoningDeltaCount = chunks.count { it is StreamChunk.ReasoningDelta },
            toolCallStartCount = chunks.count { it is StreamChunk.ToolCallStart },
        )
    }
}

@Serializable
data class ProviderProtocolRegressionResult(
    val passed: Boolean,
    val expectation: ProviderProtocolTraceExpectation,
    val actual: ProviderProtocolTraceExpectation,
)

object ProviderProtocolTraceRecorder {
    fun record(
        protocol: String,
        modelId: String,
        chunks: List<StreamChunk>,
    ): ProviderProtocolTrace {
        val sanitizedChunks = chunks.asSequence()
            .mapNotNull(::sanitize)
            .take(MAX_RECORDED_CHUNKS)
            .toList()
        return ProviderProtocolTrace(
            protocol = protocol,
            modelId = redact(modelId),
            chunks = sanitizedChunks,
            expectation = ProviderProtocolTraceExpectation.from(sanitizedChunks),
        )
    }

    internal fun sanitize(chunk: StreamChunk): StreamChunk? = when (chunk) {
        is StreamChunk.TextStart -> chunk.copy(id = redact(chunk.id))
        is StreamChunk.TextDelta -> chunk.copy(id = redact(chunk.id), text = redact(chunk.text))
        is StreamChunk.TextEnd -> chunk.copy(id = redact(chunk.id))
        is StreamChunk.ReasoningStart -> chunk.copy(id = redact(chunk.id), metadata = chunk.metadata?.sanitizeObject())
        is StreamChunk.ReasoningDelta -> chunk.copy(
            id = redact(chunk.id),
            text = redact(chunk.text),
            metadata = chunk.metadata?.sanitizeObject(),
        )
        is StreamChunk.ReasoningEnd -> chunk.copy(id = redact(chunk.id), metadata = chunk.metadata?.sanitizeObject())
        is StreamChunk.ToolCallStart -> chunk.copy(
            id = redact(chunk.id),
            toolName = redact(chunk.toolName),
            metadata = chunk.metadata?.sanitizeObject(),
        )
        is StreamChunk.ToolCallDelta -> chunk.copy(
            id = redact(chunk.id),
            toolNameDelta = redact(chunk.toolNameDelta),
            inputDelta = redact(chunk.inputDelta),
            metadata = chunk.metadata?.sanitizeObject(),
        )
        is StreamChunk.ToolCallEnd -> chunk.copy(id = redact(chunk.id))
        is StreamChunk.ServerToolStart -> chunk.copy(
            id = redact(chunk.id),
            toolName = redact(chunk.toolName),
            input = chunk.input?.sanitize(),
            metadata = chunk.metadata?.sanitizeObject(),
        )
        is StreamChunk.ServerToolInputDelta -> chunk.copy(
            id = redact(chunk.id),
            inputDelta = redact(chunk.inputDelta),
            metadata = chunk.metadata?.sanitizeObject(),
        )
        is StreamChunk.ServerToolInputEnd -> chunk.copy(id = redact(chunk.id))
        is StreamChunk.ServerToolEnd -> chunk.copy(
            id = redact(chunk.id),
            input = chunk.input?.sanitize(),
            output = chunk.output?.sanitize(),
            metadata = chunk.metadata?.sanitizeObject(),
        )
        is StreamChunk.ImageStart,
        is StreamChunk.ImageDelta,
        is StreamChunk.ImageSnapshot,
        is StreamChunk.ImageEnd,
        is StreamChunk.Annotations -> null
        is StreamChunk.Usage -> chunk
        is StreamChunk.Finish -> chunk.copy(
            finishReason = chunk.finishReason?.let(::redact),
            responseId = chunk.responseId?.let(::redact),
            model = chunk.model?.let(::redact),
        )
    }

    private fun JsonElement.sanitize(): JsonElement = when (this) {
        is JsonObject -> JsonObject(mapValues { (_, value) -> value.sanitize() })
        is JsonArray -> JsonArray(map { it.sanitize() })
        is JsonPrimitive -> if (isString) JsonPrimitive(redact(content)) else this
    }

    private fun JsonObject.sanitizeObject(): JsonObject = sanitize() as JsonObject
}

object ProviderProtocolTraceReplayer {
    fun validate(trace: ProviderProtocolTrace): ProviderProtocolRegressionResult {
        val actual = ProviderProtocolTraceExpectation.from(trace.chunks)
        val handler = StreamChunkHandler(Model(modelId = trace.modelId))
        val replayedMessages = trace.chunks.fold(listOf(UIMessage.user("protocol trace replay"))) { messages, chunk ->
            handler.handle(messages, chunk)
        }
        val replayedMessage = replayedMessages.lastOrNull()
        return ProviderProtocolRegressionResult(
            passed = trace.schemaVersion == 1 &&
                trace.expectation == actual &&
                replayedMessage?.finishedAt != null,
            expectation = trace.expectation,
            actual = actual,
        )
    }
}

object ProviderProtocolTraceCodec {
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun encode(trace: ProviderProtocolTrace): String = json.encodeToString(trace)

    fun decode(value: String): ProviderProtocolTrace = json.decodeFromString(value)
}

private data class TimedValue<T>(
    val value: T? = null,
    val latencyMillis: Long,
    val error: String? = null,
) {
    fun status(): ProviderDiagnosticStatus = if (error == null) {
        ProviderDiagnosticStatus.SUCCESS
    } else {
        ProviderDiagnosticStatus.FAILURE
    }

    fun asCheck(): ProviderDiagnosticCheck = if (error == null) {
        ProviderDiagnosticCheck.success(latencyMillis)
    } else {
        ProviderDiagnosticCheck.failure(latencyMillis, error)
    }
}

private suspend fun <T> timed(block: suspend () -> T): TimedValue<T> {
    val start = System.nanoTime()
    return try {
        TimedValue(value = block(), latencyMillis = elapsedMillis(start))
    } catch (throwable: Throwable) {
        TimedValue(
            latencyMillis = elapsedMillis(start),
            error = throwable.toSafeDiagnosticMessage(),
        )
    }
}

private fun ProviderSetting.protocolId(): String = when (this) {
    is ProviderSetting.OpenAI -> "openai"
    is ProviderSetting.Google -> "google"
    is ProviderSetting.Claude -> "claude"
}

private fun elapsedMillis(startNanos: Long): Long = (System.nanoTime() - startNanos) / 1_000_000

private const val MAX_RECORDED_CHUNKS = 256
private const val MAX_RECORDED_TEXT_LENGTH = 512
private val sensitiveValuePattern = Regex(
    "(?i)(bearer\\s+|sk-|api[_-]?key\\s*[:=]\\s*)[a-z0-9._-]{6,}"
)

private fun String.toSafeDiagnosticMessage(): String = redact(take(MAX_RECORDED_TEXT_LENGTH))

private fun Throwable.toSafeDiagnosticMessage(): String =
    message?.toSafeDiagnosticMessage()?.ifBlank { null } ?: this::class.simpleName.orEmpty()

private fun redact(value: String): String = sensitiveValuePattern
    .replace(value) { match -> "${match.groupValues[1]}[REDACTED]" }
    .take(MAX_RECORDED_TEXT_LENGTH)
