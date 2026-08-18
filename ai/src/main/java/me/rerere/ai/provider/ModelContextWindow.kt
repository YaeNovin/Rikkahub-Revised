package me.rerere.ai.provider

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

private val CONTEXT_WINDOW_FIELDS = listOf(
    "inputTokenLimit",
    "input_token_limit",
    "context_length",
    "context_window",
    "context_window_tokens",
    "max_context_tokens",
    "max_context_length",
    "max_input_tokens",
    "max_sequence_length",
)

private val CONTEXT_WINDOW_CONTAINERS = listOf(
    "architecture",
    "capabilities",
    "limits",
    "model_limits",
)

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

/** Parses the compact K/M notation accepted by the manual context-window setting. */
fun parseContextWindowTokens(value: String): Int? {
    val match = CONTEXT_WINDOW_INPUT.matchEntire(value.trim()) ?: return null
    val amount = match.groupValues[1].toLongOrNull() ?: return null
    val multiplier = when (match.groupValues[2].uppercase()) {
        "K" -> 1_000L
        "M" -> 1_000_000L
        else -> 1L
    }
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
    val value = (this as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: return null
    return value.takeIf { it in 1L..MAX_CONTEXT_WINDOW_TOKENS }?.toInt()
}

private const val MAX_CONTEXT_WINDOW_TOKENS = 10_000_000L
private val CONTEXT_WINDOW_INPUT = Regex("^(\\d+)([kKmM])?$")
