package me.rerere.ai.provider.providers.claude

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.DeepSeekToolChoice
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.providers.openai.DeepSeekModelParameterSupport

internal fun JsonObjectBuilder.applyDeepSeekAnthropicRequestOptions(
    params: TextGenerationParams,
    support: DeepSeekModelParameterSupport,
    hasTools: Boolean,
) {
    if (!support.available) return
    val options = params.deepSeekOptions

    options.stopSequences.normalizedDeepSeekAnthropicStopSequences()
        .takeIf(List<String>::isNotEmpty)
        ?.let { sequences ->
            put("stop_sequences", buildJsonArray {
                sequences.forEach { add(JsonPrimitive(it)) }
            })
        }
    if (hasTools) {
        buildDeepSeekAnthropicToolChoice(options.toolChoice)?.let { put("tool_choice", it) }
    }
    options.userId.normalizedDeepSeekAnthropicUserId()?.let { userId ->
        put("metadata", buildJsonObject { put("user_id", userId) })
    }

    deepSeekAnthropicReasoningEffort(params.reasoningLevel)?.let { effort ->
        put("output_config", buildJsonObject { put("effort", effort) })
    }
}

private fun buildDeepSeekAnthropicToolChoice(choice: DeepSeekToolChoice): JsonObject? {
    val type = when (choice) {
        DeepSeekToolChoice.DEFAULT -> return null
        DeepSeekToolChoice.AUTO -> "auto"
        DeepSeekToolChoice.NONE -> "none"
        DeepSeekToolChoice.REQUIRED -> "any"
    }
    return buildJsonObject { put("type", type) }
}

private fun deepSeekAnthropicReasoningEffort(level: ReasoningLevel): String? = when (level) {
    ReasoningLevel.OFF,
    ReasoningLevel.AUTO -> null
    ReasoningLevel.MINIMAL,
    ReasoningLevel.LOW -> "low"
    ReasoningLevel.MEDIUM,
    ReasoningLevel.HIGH,
    ReasoningLevel.XHIGH -> "high"
    ReasoningLevel.MAX -> "max"
}

private fun List<String>.normalizedDeepSeekAnthropicStopSequences(): List<String> =
    map(String::trim).filter(String::isNotEmpty).distinct().take(16)

private fun String.normalizedDeepSeekAnthropicUserId(): String? =
    trim().takeIf { it.isNotEmpty() && DEEPSEEK_ANTHROPIC_USER_ID.matches(it) }

private val DEEPSEEK_ANTHROPIC_USER_ID = Regex("[A-Za-z0-9_-]{1,512}")
