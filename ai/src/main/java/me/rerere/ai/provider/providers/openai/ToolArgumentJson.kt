package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * OpenAI-compatible gateways sometimes return function arguments as a JSON string, while other
 * gateways return the already parsed object/array. Preserve either representation as the raw JSON
 * text expected by [me.rerere.ai.ui.UIMessagePart.Tool].
 */
internal fun JsonElement?.toToolArgumentString(): String? = this?.let { element ->
    when (element) {
        is JsonPrimitive -> element.contentOrNull ?: element.toString()
        else -> element.toString()
    }
}
