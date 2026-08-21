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
        ?: knownQwenContextWindowTokens(modelId)
        ?: knownDeepSeekContextWindowTokens(modelId)
        ?: knownDoubaoContextWindowTokens(modelId)
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
        ?: knownQwenContextWindowTokens(modelId)
        ?: knownDeepSeekContextWindowTokens(modelId)
        ?: knownDoubaoContextWindowTokens(modelId)

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

private fun knownQwenContextWindowTokens(modelId: String): Int? {
    val id = modelId.normalizedModelId()
    return when {
        id.startsWith("qwen-long") -> 10_000_000
        id.startsWith("qwen3.8-max") -> 1_000_000
        id.startsWith("qwen3.7-") -> 1_000_000
        id.startsWith("qwen3.6-plus") || id.startsWith("qwen3.6-flash") -> 1_000_000
        id.startsWith("qwen3.6-max") -> 256_000
        id.startsWith("qwen3.5-plus") || id.startsWith("qwen3.5-flash") -> 1_000_000
        QWEN_3_5_OPEN_SOURCE.matchesPrefix(id) -> 256_000
        id.startsWith("qwen3-coder-plus") || id.startsWith("qwen3-coder-flash") -> 1_000_000
        id.startsWith("qwen3-coder-next") ||
            id.startsWith("qwen3-coder-480b") ||
            id.startsWith("qwen3-coder-30b") -> 256_000
        id.startsWith("qwen3-max") -> 256_000
        QWEN_3_OPEN_SOURCE.matchesPrefix(id) -> 256_000
        id.startsWith("qwen2.5-") -> 1_000_000
        id.startsWith("qwen-plus-character") -> 32_000
        id.startsWith("qwen-flash-character") -> 8_000
        id == "qwen-plus" || id.startsWith("qwen-plus-") -> 1_000_000
        id == "qwen-flash" || id.startsWith("qwen-flash-") -> 1_000_000
        id == "qwen-turbo" || id.startsWith("qwen-turbo-") -> 1_000_000
        id.startsWith("qwen-mt-") -> 16_000
        id.startsWith("qwen-omni-turbo") -> 32_000
        id.startsWith("qwen-vl-max") -> 131_072
        id == "qwen-max" || id.startsWith("qwen-max-") -> 128_000
        id.startsWith("qwq-plus") || id.startsWith("qvq-max") -> 128_000
        else -> null
    }
}

private fun knownDeepSeekContextWindowTokens(modelId: String): Int? {
    val id = modelId.normalizedModelId()
    return when {
        id.startsWith("deepseek-v4") -> 1_000_000
        id.startsWith("deepseek-v3") -> 128_000
        id.startsWith("deepseek-r1") || id == "deepseek-chat" || id == "deepseek-reasoner" -> 128_000
        else -> null
    }
}

private fun knownDoubaoContextWindowTokens(modelId: String): Int? {
    val id = modelId.normalizedModelId()
    DOUBAO_DECLARED_CONTEXT.find(id)?.let { match ->
        return match.groupValues[1].toIntOrNull()?.times(1_000)
    }
    return when {
        id.startsWith("doubao-seed-evolving") -> 1_024_000
        id.startsWith("doubao-seed-code") -> 256_000
        id.startsWith("doubao-seed-1-6") || id.startsWith("doubao-seed-1.6") -> 256_000
        id.startsWith("doubao-seed-1-8") || id.startsWith("doubao-seed-1.8") -> 256_000
        id.startsWith("doubao-seed-2-1") || id.startsWith("doubao-seed-2.1") -> 256_000
        id.startsWith("ark-code") -> 256_000
        id.startsWith("doubao-1-5-vision-pro") || id.startsWith("doubao-1.5-vision-pro") -> 128_000
        else -> null
    }
}

private fun Set<String>.matchesPrefix(modelId: String): Boolean = any(modelId::startsWith)

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
private val DOUBAO_DECLARED_CONTEXT = Regex("(?:^|[-_.])(32|128|256)k(?:$|[-_.])")
private val QWEN_3_5_OPEN_SOURCE = setOf(
    "qwen3.5-397b-a17b",
    "qwen3.5-122b-a10b",
    "qwen3.5-35b-a3b",
    "qwen3.5-27b",
)
private val QWEN_3_OPEN_SOURCE = setOf(
    "qwen3-235b-a22b",
    "qwen3-next-80b-a3b",
    "qwen3-32b",
    "qwen3-30b-a3b",
    "qwen3-14b",
    "qwen3-8b",
    "qwen3-4b",
    "qwen3-1.7b",
    "qwen3-0.6b",
)
