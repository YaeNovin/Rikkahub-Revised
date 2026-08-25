package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.provider.QwenResponseFormat
import me.rerere.ai.provider.QwenToolChoice
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.util.json

internal fun JsonObjectBuilder.applyQwenChatOptions(
    params: TextGenerationParams,
    hasFunctionTools: Boolean,
    stream: Boolean,
    hasImageInput: Boolean,
) {
    val support = resolveQwenModelParameterSupport(params.model.modelId)
    if (!support.available) return
    val options = params.qwenOptions

    if (hasFunctionTools) {
        options.parallelToolCalls.apiValue?.let { put("parallel_tool_calls", it) }
        options.toolChoice.apiValue?.let { put("tool_choice", it) }
        if (stream && support.supportsToolStream) {
            options.toolStream.apiValue?.let { put("tool_stream", it) }
        }
    }
    options.topK?.takeIf { it in 0..100 }?.let { put("top_k", it) }
    options.repetitionPenalty?.takeIf { it > 0f }?.let { put("repetition_penalty", it) }
    options.presencePenalty?.takeIf { it in -2f..2f }?.let { put("presence_penalty", it) }
    options.seed?.takeIf { it in 0..Int.MAX_VALUE.toLong() }?.let { put("seed", it) }
    options.stopSequences.normalizedQwenStopSequences().takeIf(List<String>::isNotEmpty)?.let {
        put("stop", buildJsonArray { it.forEach(::add) })
    }
    if (support.supportsPreserveThinking) {
        options.preserveThinking.apiValue?.let { put("preserve_thinking", it) }
    }
    if (hasImageInput && support.supportsHighResolutionVision) {
        options.highResolutionVision.apiValue?.let { put("vl_high_resolution_images", it) }
    }

    buildQwenChatResponseFormat(
        format = options.responseFormat,
        supportsJsonSchema = support.supportsJsonSchema,
        schemaName = options.responseSchemaName,
        schemaText = options.responseJsonSchema,
    )?.let { responseFormat ->
        put("response_format", responseFormat)
    }
}

internal fun TextGenerationParams.usesQwenStructuredOutput(): Boolean {
    val support = resolveQwenModelParameterSupport(model.modelId)
    return support.available && when (qwenOptions.responseFormat) {
        QwenResponseFormat.JSON_OBJECT -> true
        QwenResponseFormat.JSON_SCHEMA -> support.supportsJsonSchema &&
            isValidQwenJsonSchema(qwenOptions.responseJsonSchema)
        QwenResponseFormat.AUTO,
        QwenResponseFormat.TEXT -> false
    }
}

internal fun JsonObjectBuilder.applyQwenResponseOptions(
    params: TextGenerationParams,
    toolCount: Int,
) {
    if (!resolveQwenModelParameterSupport(params.model.modelId).available || toolCount == 0) return
    val toolChoice = params.qwenOptions.toolChoice
    if (toolChoice == QwenToolChoice.REQUIRED && toolCount != 1) return
    toolChoice.apiValue?.let { put("tool_choice", it) }
}

private fun buildQwenChatResponseFormat(
    format: QwenResponseFormat,
    supportsJsonSchema: Boolean,
    schemaName: String,
    schemaText: String,
): JsonObject? = when (format) {
    QwenResponseFormat.AUTO -> null
    QwenResponseFormat.TEXT,
    QwenResponseFormat.JSON_OBJECT -> buildJsonObject { put("type", format.apiValue!!) }
    QwenResponseFormat.JSON_SCHEMA -> if (supportsJsonSchema) {
        parseQwenSchema(schemaText)?.let { schema ->
            buildJsonObject {
                put("type", format.apiValue!!)
                put("json_schema", buildJsonObject {
                    put("name", schemaName.normalizedQwenSchemaName())
                    put("schema", schema)
                    put("strict", true)
                })
            }
        }
    } else {
        null
    }
}

fun isValidQwenJsonSchema(schemaText: String): Boolean = parseQwenSchema(schemaText) != null

private fun parseQwenSchema(schemaText: String): JsonObject? = schemaText
    .takeIf(String::isNotBlank)
    ?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }

private fun List<String>.normalizedQwenStopSequences(): List<String> =
    map(String::trim).filter(String::isNotEmpty).distinct().take(4)

private fun String.normalizedQwenSchemaName(): String =
    trim().takeIf { QWEN_SCHEMA_NAME.matches(it) } ?: "response"

private val QWEN_SCHEMA_NAME = Regex("[A-Za-z0-9_-]{1,64}")
