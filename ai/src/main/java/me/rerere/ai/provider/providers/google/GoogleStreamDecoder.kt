package me.rerere.ai.provider.providers.google

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.ProviderRequestException
import me.rerere.ai.provider.stream.DecodeResult
import me.rerere.ai.provider.stream.SseEvent
import me.rerere.ai.provider.stream.StreamChunkDecoder
import me.rerere.ai.provider.stream.prematureStreamTermination
import me.rerere.ai.ui.GoogleThoughtMetadata
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.toMetadata
import me.rerere.ai.util.json
import me.rerere.common.http.jsonPrimitiveOrNull
import kotlin.time.Clock

internal class GoogleStreamDecoder(
    private val responseId: String,
    private val model: String,
    private val expectsImageOutput: Boolean = false,
) : StreamChunkDecoder {
    private val streamState = GoogleStreamState()
    private var finishReason: String? = null
    private var finished = false
    private var toolSequence = 0
    private val providerToolIds = mutableMapOf<String, String>()

    override fun accept(event: SseEvent): DecodeResult {
        if (finished) return DecodeResult(completed = true)
        if (event.data == "[DONE]") return DecodeResult(finish(), completed = true)

        val jsonData = json.parseToJsonElement(event.data).jsonObject
        val blockedReason = jsonData["promptFeedback"]?.jsonObject
            ?.get("blockReason")?.jsonPrimitiveOrNull?.contentOrNull
        if (blockedReason != null) error("Prompt feedback: $blockedReason")

        val chunks = buildList {
            parseUsage(jsonData["usageMetadata"] as? JsonObject)?.let { add(StreamChunk.Usage(it)) }
            val candidate = jsonData["candidates"]?.jsonArray?.firstOrNull()?.jsonObject ?: return@buildList
            candidate["finishReason"]?.jsonPrimitive?.contentOrNull?.let { finishReason = it }
            val content = candidate["content"]?.jsonObject ?: return@buildList
            val message = parseMessage(content, candidate["groundingMetadata"] as? JsonObject)
            addAll(streamState.append(message, responseId))
        }
        return DecodeResult(chunks)
    }

    override fun onClosed(): List<StreamChunk> {
        if (finished) return emptyList()
        // A few relays close the SSE stream immediately after a complete client-tool call and
        // omit the final candidate.finishReason. Keep that actionable call instead of turning it
        // into the generic interrupted-generation error. Empty image responses remain guarded.
        if (finishReason == null && (!expectsImageOutput || !streamState.hasFinalOutput)) {
            prematureStreamTermination("Google generateContent")
        }
        return finish()
    }

    private fun finish(): List<StreamChunk> {
        if (finished) return emptyList()
        finished = true
        if (expectsImageOutput && !streamState.hasFinalOutput) {
            throw ProviderRequestException(
                statusCode = 503,
                retryAfterMillis = null,
                message = "Google image generation ended without a final image or text response",
            )
        }
        return streamState.finish(finishReason, responseId, model)
    }

    private fun parseMessage(content: JsonObject, groundingMetadata: JsonObject?): UIMessage = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = content["parts"]?.jsonArray?.map { parsePart(it.jsonObject) }.orEmpty(),
        annotations = parseAnnotations(groundingMetadata),
    )

    private fun parsePart(part: JsonObject): UIMessagePart = when {
        part.containsKey("text") -> {
            val text = part["text"]?.jsonPrimitive?.contentOrNull ?: ""
            if (part["thought"]?.jsonPrimitive?.booleanOrNull == true) {
                UIMessagePart.Reasoning(text, Clock.System.now(), null)
            } else {
                UIMessagePart.Text(text)
            }
        }
        part.containsKey("functionCall") -> {
            val functionCall = part["functionCall"] as? JsonObject
                ?: error("Gemini functionCall must be a JSON object")
            val toolName = functionCall["name"]?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.isNotBlank() }
                ?: error("Gemini functionCall.name is required")
            val functionCallId = functionCall["id"]?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.isNotBlank() }
            UIMessagePart.Tool(
                // Gemini 3 supplies a stable ID that must be echoed in functionResponse.
                // For older models, keep a local ID but leave functionCallId null.
                toolCallId = functionCallId?.let { providerToolIds.getOrPut(it) {
                    "$responseId:tool-${++toolSequence}"
                } } ?: "$responseId:tool-${++toolSequence}",
                toolName = toolName,
                input = functionCall["args"]?.let { if (it is JsonNull) "null" else it.toString() }
                    .orEmpty(),
                output = emptyList(),
                metadata = GoogleThoughtMetadata(
                    thoughtSignature = part["thoughtSignature"]?.jsonPrimitive?.contentOrNull,
                    functionCallId = functionCallId,
                ).toMetadata(),
            )
        }
        part.containsKey("inlineData") -> {
            val inlineData = part["inlineData"]!!.jsonObject
            val mimeType = inlineData["mimeType"]?.jsonPrimitive?.contentOrNull ?: "image/png"
            require(mimeType.startsWith("image/")) { "Only image mime type is supported" }
            if (part["thought"]?.jsonPrimitive?.booleanOrNull == true) {
                UIMessagePart.Reasoning("[Draft Image]\n", Clock.System.now(), null)
            } else {
                UIMessagePart.Image(
                    url = "data:$mimeType;base64,${inlineData["data"]?.jsonPrimitive?.contentOrNull ?: ""}",
                    metadata = GoogleThoughtMetadata(
                        thoughtSignature = part["thoughtSignature"]?.jsonPrimitive?.contentOrNull,
                    ).toMetadata(),
                )
            }
        }
        else -> error("unknown message part type: $part")
    }

    private fun parseAnnotations(metadata: JsonObject?): List<UIMessageAnnotation> =
        metadata?.get("groundingChunks")?.jsonArray.orEmpty().mapNotNull { chunk ->
            val web = chunk.jsonObject["web"]?.jsonObject ?: return@mapNotNull null
            val url = web["uri"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val title = web["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            UIMessageAnnotation.UrlCitation(title, url)
        }

    private fun parseUsage(usage: JsonObject?): TokenUsage? {
        if (usage == null) return null
        val promptTokens = usage["promptTokenCount"]?.jsonPrimitiveOrNull?.intOrNull ?: 0
        val thoughtTokens = usage["thoughtsTokenCount"]?.jsonPrimitiveOrNull?.intOrNull ?: 0
        val cachedTokens = usage["cachedContentTokenCount"]?.jsonPrimitiveOrNull?.intOrNull ?: 0
        val candidateTokens = usage["candidatesTokenCount"]?.jsonPrimitiveOrNull?.intOrNull ?: 0
        val totalTokens = usage["totalTokenCount"]?.jsonPrimitiveOrNull?.intOrNull ?: 0
        return TokenUsage(
            promptTokens = promptTokens,
            completionTokens = candidateTokens + thoughtTokens,
            totalTokens = totalTokens,
            cachedTokens = cachedTokens,
        )
    }

    private class GoogleStreamState {
        private var sequence = 0
        private var textId: String? = null
        private var reasoningId: String? = null
        private var imageId: String? = null
        private val openToolIds = linkedSetOf<String>()
        var hasFinalOutput: Boolean = false
            private set

        fun append(message: UIMessage, responseId: String): List<StreamChunk> = buildList {
            val imageCount = message.parts.count { it is UIMessagePart.Image }
            var emittedImages = 0
            message.parts.forEach { part ->
                when (part) {
                    is UIMessagePart.Text -> if (part.text.isNotEmpty()) {
                        hasFinalOutput = true
                        addAll(closeReasoning()); addAll(closeImage()); addAll(closeTools())
                        val id = textId ?: nextId(responseId, "text").also {
                            textId = it; add(StreamChunk.TextStart(it))
                        }
                        add(StreamChunk.TextDelta(id, part.text))
                    }
                    is UIMessagePart.Reasoning -> if (part.reasoning.isNotEmpty() || part.metadata != null) {
                        addAll(closeText()); addAll(closeImage()); addAll(closeTools())
                        val id = reasoningId ?: nextId(responseId, "reasoning").also {
                            reasoningId = it; add(StreamChunk.ReasoningStart(it, part.metadata))
                        }
                        add(StreamChunk.ReasoningDelta(id, part.reasoning, part.metadata))
                    }
                    is UIMessagePart.Tool -> {
                        // A tool-only turn is a complete, actionable Gemini response. Image-capable
                        // models may call client tools before producing text or an image, so the
                        // image-output guard must not turn this into a premature stream failure.
                        hasFinalOutput = true
                        addAll(closeText()); addAll(closeReasoning()); addAll(closeImage())
                        val id = part.toolCallId.ifBlank { nextId(responseId, "tool") }
                        if (openToolIds.add(id)) add(StreamChunk.ToolCallStart(id, part.toolName, part.metadata))
                        if (part.input.isNotEmpty()) {
                            add(StreamChunk.ToolCallDelta(id, inputDelta = part.input, metadata = part.metadata))
                        }
                    }
                    is UIMessagePart.Image -> {
                        if (part.url.substringAfter(";base64,", part.url).isNotBlank()) {
                            hasFinalOutput = true
                        }
                        addAll(closeText()); addAll(closeReasoning()); addAll(closeTools())
                        if (imageCount > 1 && emittedImages > 0) addAll(closeImage())
                        val id = imageId ?: nextId(responseId, "image").also {
                            imageId = it
                            add(StreamChunk.ImageStart(
                                id = it,
                                mimeType = part.url.substringAfter("data:").substringBefore(";base64,"),
                                metadata = part.metadata,
                            ))
                        }
                        add(StreamChunk.ImageDelta(id, part.url.substringAfter(";base64,", part.url), part.metadata))
                        emittedImages++
                    }
                    else -> Unit
                }
            }
            if (message.annotations.isNotEmpty()) add(StreamChunk.Annotations(message.annotations))
        }

        fun finish(reason: String?, responseId: String, model: String): List<StreamChunk> = buildList {
            addAll(closeText()); addAll(closeReasoning()); addAll(closeImage()); addAll(closeTools())
            add(StreamChunk.Finish(reason, responseId, model))
        }

        private fun closeText() = textId?.let { textId = null; listOf(StreamChunk.TextEnd(it)) }.orEmpty()
        private fun closeReasoning() = reasoningId?.let {
            reasoningId = null; listOf(StreamChunk.ReasoningEnd(it))
        }.orEmpty()
        private fun closeImage() = imageId?.let { imageId = null; listOf(StreamChunk.ImageEnd(it)) }.orEmpty()
        private fun closeTools() = openToolIds.toList().map { StreamChunk.ToolCallEnd(it) }.also {
            openToolIds.clear()
        }
        private fun nextId(responseId: String, kind: String): String =
            "$responseId:$kind-${++sequence}"
    }
}
