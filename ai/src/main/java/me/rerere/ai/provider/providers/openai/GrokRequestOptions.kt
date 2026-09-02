package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.provider.GrokResponseFormat
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.supportsReasoningCapability
import me.rerere.ai.util.json

internal fun JsonObjectBuilder.applyGrokChatOptions(
    params: TextGenerationParams,
    hasFunctionTools: Boolean,
) {
    val support = resolveGrokModelParameterSupport(params.model.modelId)
    if (!support.available) return
    val options = params.grokOptions
    val isReasoningModel = support.reasoningModel || params.model.supportsReasoningCapability()

    options.serviceTier.apiValue?.let { put("service_tier", it) }
    if (hasFunctionTools) {
        options.parallelToolCalls.apiValue?.let { put("parallel_tool_calls", it) }
        options.toolChoice.apiValue?.let { put("tool_choice", it) }
    }
    options.seed?.let { put("seed", it) }

    if (!isReasoningModel) {
        options.frequencyPenalty
            ?.takeIf { it in -2f..2f }
            ?.let { put("frequency_penalty", it) }
        if (support.supportsPresencePenalty) {
            options.presencePenalty
                ?.takeIf { it in -2f..2f }
                ?.let { put("presence_penalty", it) }
        }
        options.stopSequences.normalizedStopSequences().takeIf(List<String>::isNotEmpty)?.let {
            put("stop", buildJsonArray { it.forEach(::add) })
        }
    }

    buildGrokChatResponseFormat(options.responseFormat, options.responseSchemaName, options.responseJsonSchema)
        ?.let { put("response_format", it) }
}

internal fun JsonObjectBuilder.applyGrokResponseOptions(
    params: TextGenerationParams,
    hasAnyTools: Boolean,
) {
    val support = resolveGrokModelParameterSupport(params.model.modelId)
    if (!support.available) return
    val options = params.grokOptions

    options.serviceTier.apiValue?.let { put("service_tier", it) }
    if (hasAnyTools) {
        options.parallelToolCalls.apiValue?.let { put("parallel_tool_calls", it) }
        options.toolChoice.apiValue?.let { put("tool_choice", it) }
        options.maxTurns?.takeIf { it > 0 }?.let { put("max_turns", it) }
    }
    options.minP?.takeIf { it in 0f..1f }?.let { put("min_p", it) }
    options.topK?.takeIf { it > 0 }?.let { put("top_k", it) }
    buildGrokResponseTextFormat(options.responseFormat, options.responseSchemaName, options.responseJsonSchema)
        ?.let { put("text", buildJsonObject { put("format", it) }) }
}

private fun buildGrokChatResponseFormat(
    format: GrokResponseFormat,
    schemaName: String,
    schemaText: String,
): JsonObject? = when (format) {
    GrokResponseFormat.AUTO -> null
    GrokResponseFormat.TEXT,
    GrokResponseFormat.JSON_OBJECT -> buildJsonObject { put("type", format.apiValue!!) }
    GrokResponseFormat.JSON_SCHEMA -> parseGrokSchema(schemaText)?.let { schema ->
        buildJsonObject {
            put("type", format.apiValue!!)
            put("json_schema", buildJsonObject {
                put("name", schemaName.normalizedSchemaName())
                put("schema", schema)
                put("strict", true)
            })
        }
    }
}

private fun buildGrokResponseTextFormat(
    format: GrokResponseFormat,
    schemaName: String,
    schemaText: String,
): JsonObject? = when (format) {
    GrokResponseFormat.AUTO -> null
    GrokResponseFormat.TEXT,
    GrokResponseFormat.JSON_OBJECT -> buildJsonObject { put("type", format.apiValue!!) }
    GrokResponseFormat.JSON_SCHEMA -> parseGrokSchema(schemaText)?.let { schema ->
        buildJsonObject {
            put("type", format.apiValue!!)
            put("name", schemaName.normalizedSchemaName())
            put("schema", schema)
            put("strict", true)
        }
    }
}

fun isValidGrokJsonSchema(schemaText: String): Boolean = parseGrokSchema(schemaText) != null

private fun parseGrokSchema(schemaText: String): JsonObject? = schemaText
    .takeIf(String::isNotBlank)
    ?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }

private fun List<String>.normalizedStopSequences(): List<String> =
    map(String::trim).filter(String::isNotEmpty).distinct().take(4)

private fun String.normalizedSchemaName(): String =
    trim().takeIf { SCHEMA_NAME.matches(it) } ?: "response"

private val SCHEMA_NAME = Regex("[A-Za-z0-9_-]{1,64}")
