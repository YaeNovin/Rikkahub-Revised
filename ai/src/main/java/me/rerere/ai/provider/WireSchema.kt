package me.rerere.ai.provider

import kotlinx.serialization.json.*
import me.rerere.ai.core.Tool
import me.rerere.ai.util.json

/** Functions with no arguments still require an object schema, never JSON null. */
fun Tool.wireParameters(): JsonObject = parameters()?.let { json.encodeToJsonElement(it).jsonObject }
    ?: buildJsonObject { put("type", "object"); put("properties", buildJsonObject {}); put("required", buildJsonArray {}) }

fun parseOutputSchema(text: String): JsonObject = try {
    require(text.isNotBlank()) { "请填写 JSON Schema。" }
    json.parseToJsonElement(text).jsonObject
} catch (error: IllegalArgumentException) {
    throw IllegalArgumentException("JSON Schema 必须是有效的 JSON 对象。", error)
}

/** Walk schema positions only; properties named 'items', 'required', etc. are user data. */
fun JsonObject.closedOutputSchema(requireAll: Boolean = false): JsonObject {
    fun visit(value: JsonElement): JsonElement {
        val node = value as? JsonObject ?: return value
        val result = node.toMutableMap()
        for (key in listOf("properties", "\$defs", "definitions", "patternProperties")) {
            (node[key] as? JsonObject)?.let { children -> result[key] = JsonObject(children.mapValues { visit(it.value) }) }
        }
        for (key in listOf("items", "additionalProperties", "not")) node[key]?.let { result[key] = visit(it) }
        for (key in listOf("anyOf", "oneOf", "allOf", "prefixItems")) {
            (node[key] as? JsonArray)?.let { result[key] = JsonArray(it.map(::visit)) }
        }
        val properties = result["properties"] as? JsonObject
        val objectType = node["type"] == JsonPrimitive("object") ||
            (node["type"] as? JsonArray)?.contains(JsonPrimitive("object")) == true || properties != null
        if (objectType) {
            require(node["additionalProperties"] == null || node["additionalProperties"] == JsonPrimitive(false)) {
                "严格结构化输出不支持任意对象键，请列出 properties，或使用 key/value 数组。"
            }
            result["additionalProperties"] = JsonPrimitive(false)
            if (properties == null) result["properties"] = buildJsonObject {}
            if (requireAll && properties == null) result["required"] = buildJsonArray {}
            if (requireAll && properties != null) {
                val required = (node["required"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content }.toSet()
                result["properties"] = JsonObject(properties.mapValues { (name, schema) ->
                    if (name in required) schema else buildJsonObject {
                        put("anyOf", buildJsonArray { add(schema); add(buildJsonObject { put("type", "null") }) })
                    }
                })
                result["required"] = JsonArray(properties.keys.map(::JsonPrimitive))
            }
        }
        return JsonObject(result)
    }
    return visit(this).jsonObject
}
