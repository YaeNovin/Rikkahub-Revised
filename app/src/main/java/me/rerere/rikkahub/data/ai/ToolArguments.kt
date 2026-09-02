package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

internal fun Json.parseToolArguments(
    input: String,
    toolName: String,
): JsonObject {
    val raw = input.ifBlank { "{}" }
    val element = runCatching { parseToJsonElement(raw) }.getOrElse { error ->
        throw IllegalArgumentException(
            "Invalid tool arguments JSON for $toolName: ${error.message}",
            error,
        )
    }
    return runCatching { element.jsonObject }.getOrElse { error ->
        throw IllegalArgumentException(
            "Tool arguments for $toolName must be a JSON object",
            error,
        )
    }
}
