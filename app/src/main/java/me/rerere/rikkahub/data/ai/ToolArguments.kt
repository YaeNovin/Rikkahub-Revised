package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal fun Json.parseToolArguments(
    input: String,
    toolName: String,
): JsonObject {
    val raw = input.ifBlank { "{}" }
    var element = runCatching { parseToJsonElement(raw) }.getOrElse { error ->
        throw IllegalArgumentException(
            "Invalid tool arguments JSON for $toolName: ${error.message}",
            error,
        )
    }
    repeat(2) {
        val primitive = element as? JsonPrimitive
        if (primitive != null && primitive.isString) {
            element = runCatching { parseToJsonElement(primitive.contentOrNull.orEmpty()) }.getOrDefault(primitive)
        }
    }
    return runCatching { element.jsonObject }.getOrElse { error ->
        throw IllegalArgumentException(
            "Tool arguments for $toolName must be a JSON object",
            error,
        )
    }
}
