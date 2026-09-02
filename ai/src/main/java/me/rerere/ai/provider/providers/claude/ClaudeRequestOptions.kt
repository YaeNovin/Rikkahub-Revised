package me.rerere.ai.provider.providers.claude

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.provider.ClaudeResponseFormat
import me.rerere.ai.provider.ProviderRequestChannel
import me.rerere.ai.provider.ProviderRequestDiagnostics
import me.rerere.ai.provider.ProviderRequestOperation
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.util.json
import okhttp3.HttpUrl.Companion.toHttpUrl

internal fun JsonObjectBuilder.applyClaudeRequestOptions(
    params: TextGenerationParams,
    support: ClaudeModelParameterSupport,
    hasTools: Boolean,
    reasoningEnabled: Boolean,
    reasoningEffort: String?,
) {
    if (!support.available) return
    val options = params.claudeOptions

    if (support.supportsServiceTier) {
        options.serviceTier.apiValue?.let { put("service_tier", it) }
    }
    if (support.supportsInferenceGeo) {
        options.inferenceGeo.apiValue?.let { put("inference_geo", it) }
    }
    options.stopSequences.normalizedClaudeStopSequences()
        .takeIf(List<String>::isNotEmpty)
        ?.let { sequences ->
            put("stop_sequences", buildJsonArray {
                sequences.forEach { sequence -> add(JsonPrimitive(sequence)) }
            })
        }
    if (!reasoningEnabled && support.supportsSamplingParameters) {
        options.topK?.takeIf { it > 0 }?.let { put("top_k", it) }
    }
    if (hasTools) {
        buildClaudeToolChoice(params)?.let { put("tool_choice", it) }
    }

    val outputConfig = buildJsonObject {
        reasoningEffort?.let { put("effort", it) }
        if (support.supportsStructuredOutput && options.responseFormat == ClaudeResponseFormat.JSON_SCHEMA) {
            parseClaudeJsonSchema(options.responseJsonSchema)?.let { schema ->
                put("format", buildJsonObject {
                    put("type", "json_schema")
                    put("schema", schema)
                })
            }
        }
    }
    if (outputConfig.isNotEmpty()) put("output_config", outputConfig)
}

fun isValidClaudeJsonSchema(schemaText: String): Boolean =
    parseClaudeJsonSchema(schemaText) != null

fun ProviderSetting.Claude.requestChannel(): ProviderRequestChannel {
    val host = runCatching { baseUrl.trim().trimEnd('/').toHttpUrl().host }.getOrNull()
    return if (host.equals(ANTHROPIC_API_HOST, ignoreCase = true)) {
        ProviderRequestChannel.ANTHROPIC_API
    } else {
        ProviderRequestChannel.COMPATIBLE_ENDPOINT
    }
}

internal fun JsonObject.claudeRequestDiagnostics(
    providerSetting: ProviderSetting.Claude,
    operation: ProviderRequestOperation,
    hasCustomBody: Boolean = false,
    requestId: String? = null,
): ProviderRequestDiagnostics {
    val thinking = this["thinking"] as? JsonObject
    val toolChoice = this["tool_choice"] as? JsonObject
    val outputConfig = this["output_config"] as? JsonObject
    val format = outputConfig?.get("format") as? JsonObject
    val parameters = linkedMapOf("api" to "messages")
    val messages = (this["messages"] as? JsonArray).orEmpty()
    val messageContentBlocks = messages.flatMap { message ->
        (((message as? JsonObject)?.get("content")) as? JsonArray).orEmpty()
    }
    val systemBlocks = (this["system"] as? JsonArray).orEmpty()
    val tools = (this["tools"] as? JsonArray).orEmpty()

    parameters["messages.count"] = messages.size.toString()
    parameters["system.blocks"] = systemBlocks.size.toString()
    parameters["content.textBlocks"] = messageContentBlocks.count { block ->
        ((block as? JsonObject)?.get("type") as? JsonPrimitive)?.contentOrNull == "text"
    }.toString()
    parameters["content.imageBlocks"] = messageContentBlocks.count { block ->
        ((block as? JsonObject)?.get("type") as? JsonPrimitive)?.contentOrNull == "image"
    }.toString()

    listOf(
        "temperature",
        "top_p",
        "top_k",
        "max_tokens",
        "service_tier",
        "inference_geo",
        "stream",
    ).forEach { key ->
        (this[key] as? JsonPrimitive)?.contentOrNull?.let { parameters[key] = it }
    }
    listOf("type", "display", "budget_tokens").forEach { key ->
        (thinking?.get(key) as? JsonPrimitive)?.contentOrNull?.let {
            parameters["thinking.$key"] = it
        }
    }
    (outputConfig?.get("effort") as? JsonPrimitive)?.contentOrNull?.let {
        parameters["output_config.effort"] = it
    }
    (format?.get("type") as? JsonPrimitive)?.contentOrNull?.let {
        parameters["output_config.format.type"] = it
    }
    listOf("type", "disable_parallel_tool_use").forEach { key ->
        (toolChoice?.get(key) as? JsonPrimitive)?.contentOrNull?.let {
            parameters["tool_choice.$key"] = it
        }
    }
    (this["stop_sequences"] as? JsonArray)?.let {
        parameters["stop_sequences.count"] = it.size.toString()
    }
    ((this["metadata"] as? JsonObject)?.get("user_id") as? JsonPrimitive)?.contentOrNull?.let {
        parameters["metadata.user_id"] = "configured"
    }
    parameters["tools.count"] = tools.size.toString()
    parameters["tools.function.count"] = tools.count { tool ->
        (tool as? JsonObject)?.containsKey("input_schema") == true
    }.toString()
    parameters["tools.server.count"] = tools.count { tool ->
        ((tool as? JsonObject)?.get("type") as? JsonPrimitive)
            ?.contentOrNull
            ?.isNotBlank() == true
    }.toString()
    (this["cache_control"] as? JsonObject)?.let { cacheControl ->
        (cacheControl["type"] as? JsonPrimitive)?.contentOrNull?.let {
            parameters["cache_control.type"] = it
        }
        (cacheControl["ttl"] as? JsonPrimitive)?.contentOrNull?.let {
            parameters["cache_control.ttl"] = it
        }
    }
    parameters["customBody"] = if (hasCustomBody) "configured" else "none"

    return ProviderRequestDiagnostics(
        provider = providerSetting.name.ifBlank { "Claude" },
        model = (this["model"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
        channel = providerSetting.requestChannel(),
        operation = operation,
        parameters = parameters,
        requestId = requestId,
    )
}

private fun buildClaudeToolChoice(params: TextGenerationParams): JsonObject? {
    val options = params.claudeOptions
    val disableParallel = options.parallelToolCalls.disableParallelToolUse
    val type = options.toolChoice.apiValue ?: if (disableParallel != null) "auto" else return null
    return buildJsonObject {
        put("type", type)
        if (type != "none") disableParallel?.let { put("disable_parallel_tool_use", it) }
    }
}

private fun parseClaudeJsonSchema(schemaText: String): JsonObject? = schemaText
    .takeIf(String::isNotBlank)
    ?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }

private fun List<String>.normalizedClaudeStopSequences(): List<String> =
    map(String::trim).filter(String::isNotEmpty).distinct().take(4)

private const val ANTHROPIC_API_HOST = "api.anthropic.com"
