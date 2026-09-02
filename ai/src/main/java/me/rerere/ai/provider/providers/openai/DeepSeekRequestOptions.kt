package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.provider.DeepSeekResponseFormat
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.parameterModelId

internal fun JsonObjectBuilder.applyDeepSeekChatOptions(
    params: TextGenerationParams,
    hasFunctionTools: Boolean,
) {
    if (!resolveDeepSeekModelParameterSupport(params.model.parameterModelId()).available) return
    val options = params.deepSeekOptions

    if (hasFunctionTools) {
        options.toolChoice.apiValue?.let { put("tool_choice", it) }
    }
    options.stopSequences.normalizedDeepSeekStopSequences()
        .takeIf(List<String>::isNotEmpty)
        ?.let { values -> put("stop", buildJsonArray { values.forEach(::add) }) }
    buildDeepSeekResponseFormat(options.responseFormat)?.let { put("response_format", it) }

    options.logProbabilities.apiValue?.let { put("logprobs", it) }
    if (options.logProbabilities.apiValue == true) {
        options.topLogProbs?.takeIf { it in 0..20 }?.let { put("top_logprobs", it) }
    }
    options.userId.normalizedDeepSeekUserId()?.let { put("user_id", it) }
}

internal fun JsonObjectBuilder.applyDeepSeekResponseOptions(
    params: TextGenerationParams,
    hasAnyTools: Boolean,
) {
    if (!resolveDeepSeekModelParameterSupport(params.model.parameterModelId()).available) return
    val options = params.deepSeekOptions

    if (hasAnyTools) {
        options.toolChoice.apiValue?.let { put("tool_choice", it) }
    }
    buildDeepSeekResponseFormat(options.responseFormat)?.let { format ->
        put("text", buildJsonObject { put("format", format) })
    }
    options.topLogProbs?.takeIf { it in 0..20 }?.let { put("top_logprobs", it) }
    options.userId.normalizedDeepSeekUserId()?.let { put("user", it) }
}

internal fun TextGenerationParams.usesDeepSeekThinkingMode(): Boolean =
    resolveDeepSeekModelParameterSupport(model.parameterModelId()).available && reasoningLevel.isEnabled

internal fun TextGenerationParams.deepSeekImageDetail(): String? =
    deepSeekOptions.imageDetail.apiValue
        ?.takeIf { resolveDeepSeekModelParameterSupport(model.parameterModelId()).supportsVision }

private fun buildDeepSeekResponseFormat(format: DeepSeekResponseFormat): JsonObject? =
    format.apiValue?.let { type -> buildJsonObject { put("type", type) } }

private fun List<String>.normalizedDeepSeekStopSequences(): List<String> =
    map(String::trim).filter(String::isNotEmpty).distinct().take(16)

private fun String.normalizedDeepSeekUserId(): String? =
    trim().takeIf { it.isNotEmpty() && DEEPSEEK_USER_ID.matches(it) }

private val DEEPSEEK_USER_ID = Regex("[A-Za-z0-9_-]{1,512}")
