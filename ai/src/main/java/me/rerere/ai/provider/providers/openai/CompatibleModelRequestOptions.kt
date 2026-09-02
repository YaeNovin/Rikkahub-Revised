package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.rerere.ai.provider.ClaudeResponseFormat
import me.rerere.ai.provider.ClaudeToolChoice
import me.rerere.ai.provider.GeminiResponseMimeType
import me.rerere.ai.provider.ModelParameterFamily
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.inferParameterFamily

internal fun JsonObjectBuilder.applyCompatibleGeminiChatOptions(params: TextGenerationParams) {
    if (params.model.inferParameterFamily() != ModelParameterFamily.GEMINI) return
    val options = params.geminiOptions

    options.seed?.let { put("seed", it) }
    putStopSequences(options.stopSequences, maxCount = 5)
    options.presencePenalty?.let { put("presence_penalty", it) }
    options.frequencyPenalty?.let { put("frequency_penalty", it) }

    when (options.responseMimeType) {
        GeminiResponseMimeType.JSON -> putCompatibleResponseFormat(
            schema = parseSchema(options.responseJsonSchema),
            fallbackToJsonObject = true,
        )

        GeminiResponseMimeType.AUTO,
        GeminiResponseMimeType.TEXT,
        GeminiResponseMimeType.ENUM,
            -> Unit
    }
}

internal fun JsonObjectBuilder.applyCompatibleClaudeChatOptions(
    params: TextGenerationParams,
    hasFunctionTools: Boolean,
) {
    if (params.model.inferParameterFamily() != ModelParameterFamily.CLAUDE) return
    val options = params.claudeOptions

    putStopSequences(options.stopSequences, maxCount = 4)
    if (hasFunctionTools) {
        when (options.toolChoice) {
            ClaudeToolChoice.DEFAULT -> Unit
            ClaudeToolChoice.AUTO -> put("tool_choice", "auto")
            ClaudeToolChoice.ANY -> put("tool_choice", "required")
            ClaudeToolChoice.NONE -> put("tool_choice", "none")
        }
        options.parallelToolCalls.disableParallelToolUse?.let { disabled ->
            put("parallel_tool_calls", !disabled)
        }
    }
    if (options.responseFormat == ClaudeResponseFormat.JSON_SCHEMA) {
        parseSchema(options.responseJsonSchema)?.let { schema ->
            putCompatibleResponseFormat(schema = schema, fallbackToJsonObject = false)
        }
    }
}

private fun JsonObjectBuilder.putStopSequences(values: List<String>, maxCount: Int) {
    val normalized = values.map(String::trim).filter(String::isNotEmpty).distinct().take(maxCount)
    if (normalized.isEmpty()) return
    putJsonArray("stop") { normalized.forEach { add(JsonPrimitive(it)) } }
}

private fun JsonObjectBuilder.putCompatibleResponseFormat(
    schema: JsonObject?,
    fallbackToJsonObject: Boolean,
) {
    when {
        schema != null -> put("response_format", buildJsonObject {
            put("type", "json_schema")
            put("json_schema", buildJsonObject {
                put("name", "response")
                put("schema", schema)
            })
        })

        fallbackToJsonObject -> put("response_format", buildJsonObject {
            put("type", "json_object")
        })
    }
}

private fun parseSchema(raw: String): JsonObject? = raw
    .takeIf(String::isNotBlank)
    ?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
