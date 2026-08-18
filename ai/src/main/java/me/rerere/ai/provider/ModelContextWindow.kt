package me.rerere.ai.provider

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

private val CONTEXT_WINDOW_FIELDS = listOf(
    "inputTokenLimit",
    "input_token_limit",
    "contextLength",
    "context_length",
    "contextWindow",
    "context_window",
    "contextWindowTokens",
    "context_window_tokens",
    "maxContextTokens",
    "max_context_tokens",
    "maxContextLength",
    "max_context_length",
    "maxInputTokens",
    "max_input_tokens",
    "maxSequenceLength",
    "max_sequence_length",
)

private val CONTEXT_WINDOW_CONTAINERS = listOf(
    "architecture",
    "capabilities",
    "limits",
    "metadata",
    "model_info",
    "model_limits",
    "top_provider",
)

internal enum class ModelDiscoveryProtocol {
    OPENAI,
    GOOGLE,
    ANTHROPIC,
}

/**
 * Extracts a model's input context capacity from common provider discovery response shapes.
 * Providers that do not expose a capacity leave the value unset so it can still be configured manually.
 */
internal fun JsonObject.contextWindowTokensOrNull(): Int? {
    CONTEXT_WINDOW_FIELDS.forEach { field ->
        this[field].contextWindowTokenCountOrNull()?.let { return it }
    }
    CONTEXT_WINDOW_CONTAINERS.forEach { container ->
        (this[container] as? JsonObject)?.contextWindowTokensOrNull()?.let { return it }
    }
    return null
}

/**
 * Uses provider metadata first, then a conservative protocol-specific fallback.
 * OpenAI and Anthropic model-list responses usually omit context capacity, while
 * Google normally returns inputTokenLimit directly.
 */
internal fun JsonObject.contextWindowTokensOrNull(
    modelId: String,
    protocol: ModelDiscoveryProtocol,
): Int? = contextWindowTokensOrNull() ?: when (protocol) {
    ModelDiscoveryProtocol.OPENAI -> knownOpenAIContextWindowTokens(modelId)
        ?: knownGoogleContextWindowTokens(modelId)
        ?: knownAnthropicContextWindowTokens(modelId)
    ModelDiscoveryProtocol.GOOGLE -> knownGoogleContextWindowTokens(modelId)
    ModelDiscoveryProtocol.ANTHROPIC -> knownAnthropicContextWindowTokens(modelId)
}

/** Fills missing capacities without replacing values configured by the user. */
fun mergeDiscoveredContextWindows(
    configuredModels: List<Model>,
    discoveredModels: List<Model>,
): List<Model> {
    val discoveredById = discoveredModels.associateBy(Model::modelId)
    return configuredModels.map { configured ->
        val discoveredTokens = discoveredById[configured.modelId]?.contextWindowTokens
        if (configured.contextWindowTokens == null && discoveredTokens != null) {
            configured.copy(contextWindowTokens = discoveredTokens)
        } else {
            configured
        }
    }
}

/** Parses the compact K/M notation accepted by the manual context-window setting. */
fun parseContextWindowTokens(value: String): Int? {
    val match = CONTEXT_WINDOW_INPUT.matchEntire(value.trim()) ?: return null
    val amount = match.groupValues[1].toLongOrNull() ?: return null
    val multiplier = when (match.groupValues[2].uppercase()) {
        "K" -> 1_000L
        "M" -> 1_000_000L
        else -> 1L
    }
    if (amount > MAX_CONTEXT_WINDOW_TOKENS / multiplier) return null
    val tokens = amount * multiplier
    return tokens.takeIf { it in 1L..MAX_CONTEXT_WINDOW_TOKENS }?.toInt()
}

fun formatContextWindowTokens(tokens: Int?): String = when {
    tokens == null || tokens <= 0 -> ""
    tokens % 1_000_000 == 0 -> "${tokens / 1_000_000}M"
    tokens % 1_000 == 0 -> "${tokens / 1_000}K"
    else -> tokens.toString()
}

private fun JsonElement?.contextWindowTokenCountOrNull(): Int? {
    val value = (this as? JsonPrimitive)?.contentOrNull ?: return null
    return parseContextWindowTokens(value)
}

private fun knownOpenAIContextWindowTokens(modelId: String): Int? {
    val id = modelId.normalizedModelId()
    return when {
        id.startsWith("gpt-5") -> 400_000
        id.startsWith("gpt-4.1") -> 1_047_576
        id.startsWith("o1") || id.startsWith("o3") || id.startsWith("o4") -> 200_000
        id.startsWith("gpt-4o") || id.startsWith("chatgpt-4o") -> 128_000
        id.startsWith("gpt-4.5") || id.startsWith("gpt-4-turbo") -> 128_000
        id.startsWith("gpt-4-0125-preview") || id.startsWith("gpt-4-1106-preview") -> 128_000
        id.startsWith("gpt-4-vision-preview") -> 128_000
        id.startsWith("gpt-4-32k") -> 32_768
        id.startsWith("gpt-4") -> 8_192
        id.startsWith("gpt-3.5-turbo") -> 16_385
        else -> null
    }
}

private fun knownGoogleContextWindowTokens(modelId: String): Int? {
    val id = modelId.normalizedModelId()
    return when {
        id.startsWith("gemini-1.5-pro") -> 2_097_152
        id == "gemini-pro" || id.startsWith("gemini-1.0-pro") -> 30_720
        id.startsWith("gemini-") -> 1_048_576
        else -> null
    }
}

private fun knownAnthropicContextWindowTokens(modelId: String): Int? {
    val id = modelId.normalizedModelId()
    return when {
        id.startsWith("claude-2.1") -> 200_000
        id.startsWith("claude-2") || id.startsWith("claude-instant") -> 100_000
        id.startsWith("claude-") -> 200_000
        else -> null
    }
}

private fun String.normalizedModelId(): String = substringAfterLast('/').trim().lowercase()

private const val MAX_CONTEXT_WINDOW_TOKENS = 10_000_000L
private val CONTEXT_WINDOW_INPUT = Regex("^(\\d+)([kKmM])?$")
