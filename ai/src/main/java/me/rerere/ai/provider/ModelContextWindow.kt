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
    "maxModelLength",
    "max_model_len",
    "maxPositionEmbeddings",
    "max_position_embeddings",
    "tokenLimit",
    "token_limit",
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

private val NORMALIZED_CONTEXT_WINDOW_FIELDS = CONTEXT_WINDOW_FIELDS.mapTo(mutableSetOf()) {
    it.normalizedMetadataKey()
}
private val NORMALIZED_CONTEXT_WINDOW_CONTAINERS = CONTEXT_WINDOW_CONTAINERS.mapTo(mutableSetOf()) {
    it.normalizedMetadataKey()
}

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
    entries.forEach { (field, value) ->
        if (field.normalizedMetadataKey() in NORMALIZED_CONTEXT_WINDOW_FIELDS) {
            value.contextWindowTokenCountOrNull()?.let { return it }
        }
    }
    entries.forEach { (container, value) ->
        if (container.normalizedMetadataKey() in NORMALIZED_CONTEXT_WINDOW_CONTAINERS) {
            (value as? JsonObject)?.contextWindowTokensOrNull()?.let { return it }
        }
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
    val discoveredById = discoveredModels.associateBy { it.modelId.normalizedModelId() }
    return configuredModels.map { configured ->
        val discoveredTokens = discoveredById[configured.modelId.normalizedModelId()]?.contextWindowTokens
        if (configured.contextWindowTokens == null && discoveredTokens != null) {
            configured.copy(contextWindowTokens = discoveredTokens)
        } else {
            configured
        }
    }
}

/** Returns a conservative known capacity for manual model configuration. */
fun inferContextWindowTokens(modelId: String): Int? =
    knownOpenAIContextWindowTokens(modelId)
        ?: knownGoogleContextWindowTokens(modelId)
        ?: knownAnthropicContextWindowTokens(modelId)

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
        id.startsWith("gpt-5.4-mini") || id.startsWith("gpt-5.4-nano") -> 400_000
        id.startsWith("gpt-5.4") || id.startsWith("gpt-5.5") || id.startsWith("gpt-5.6") -> 1_050_000
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
        id.startsWith("gemini-3.1-flash-image") -> 131_072
        id.startsWith("gemini-3-pro-image") -> 65_536
        id.startsWith("gemini-2.5-flash-image") -> 65_536
        id.startsWith("gemini-1.5-pro") -> 2_097_152
        id == "gemini-pro" || id.startsWith("gemini-1.0-pro") -> 30_720
        id.startsWith("gemini-") -> 1_048_576
        else -> null
    }
}

private fun knownAnthropicContextWindowTokens(modelId: String): Int? {
    val id = modelId.normalizedModelId()
    val tokens = id.split(MODEL_ID_SEPARATOR).filter(String::isNotEmpty)
    return when {
        tokens.matchesClaudeFamilyVersion("opus", major = "5") ||
            tokens.matchesClaudeFamilyVersion("sonnet", major = "5") ||
            tokens.matchesClaudeFamilyVersion("fable", major = "5") ||
            tokens.matchesClaudeFamilyVersion("mythos", major = "5") ||
            tokens.matchesClaudeFamilyVersion("opus", major = "4", minor = setOf("6", "7", "8")) ||
            tokens.matchesClaudeFamilyVersion("sonnet", major = "4", minor = setOf("6")) -> 1_000_000
        id.startsWith("claude-2.1") -> 200_000
        id.startsWith("claude-2") || id.startsWith("claude-instant") -> 100_000
        id.startsWith("claude-") -> 200_000
        else -> null
    }
}

private fun String.normalizedModelId(): String = substringAfterLast('/').trim().lowercase()

private fun String.normalizedMetadataKey(): String = filter(Char::isLetterOrDigit).lowercase()

private fun List<String>.matchesClaudeFamilyVersion(
    family: String,
    major: String,
    minor: Set<String>? = null,
): Boolean {
    if ("claude" !in this) return false
    return if (minor == null) {
        windowed(size = 2).any { it == listOf(family, major) || it == listOf(major, family) }
    } else {
        windowed(size = 3).any { tokens ->
            minor.any { minorVersion ->
                tokens == listOf(family, major, minorVersion) ||
                    tokens == listOf(major, minorVersion, family)
            }
        }
    }
}

private const val MAX_CONTEXT_WINDOW_TOKENS = 10_000_000L
private val CONTEXT_WINDOW_INPUT = Regex("^(\\d+)([kKmM])?$")
private val MODEL_ID_SEPARATOR = Regex("[-_.]+")
