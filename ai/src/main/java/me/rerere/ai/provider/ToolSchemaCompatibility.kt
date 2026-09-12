package me.rerere.ai.provider

import kotlinx.serialization.json.*

/** Translate schema nodes only, never delete user property names that resemble schema keywords. */
fun JsonElement.toGoogleToolSchema(): JsonElement {
    val node = this as? JsonObject ?: return this
    val supportedEnum = (node["enum"] as? JsonArray)?.all { it is JsonPrimitive && it.isString } == true
    return buildJsonObject {
        node.forEach { (key, value) -> when (key) {
            "properties" -> put(key, JsonObject((value as JsonObject).mapValues { it.value.toGoogleToolSchema() }))
            "items" -> put(key, value.toGoogleToolSchema())
            "anyOf" -> put(key, JsonArray((value as JsonArray).map { it.toGoogleToolSchema() }))
            "const", "exclusiveMaximum", "exclusiveMinimum", "format", "additionalProperties" -> Unit
            "enum" -> if (supportedEnum) put(key, value)
            else -> put(key, value)
        } }
        if (node["enum"] != null && !supportedEnum) {
            put("description", (node["description"] as? JsonPrimitive)?.content.orEmpty() + " Allowed values: ${node["enum"]}.")
        }
    }
}
