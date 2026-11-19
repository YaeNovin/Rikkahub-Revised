package me.rerere.ai.provider.providers.claude

import kotlinx.serialization.json.*
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.*

internal fun JsonObject.withOfficialMessagesContract(setting: ProviderSetting.Claude, params: TextGenerationParams): JsonObject {
    if (runCatching { java.net.URI(setting.baseUrl).host }.getOrNull() == "api.anthropic.com") {
        val thinking = ((get("thinking") as? JsonObject)?.get("type") as? JsonPrimitive)?.contentOrNull
        val choice = get("tool_choice") as? JsonObject
        if (thinking in setOf("enabled", "adaptive") && choice?.get("type") in setOf(JsonPrimitive("any"), JsonPrimitive("tool")))
            return JsonObject(this + ("tool_choice" to JsonObject(choice!!.filterKeys { it != "name" } + ("type" to JsonPrimitive("auto")))))
        return this
    }
    val vendor = officialChatVendor(setting.baseUrl)
    if (vendor == OfficialChatVendor.MINIMAX && params.model.isMiniMaxM3()) {
        val fields = toMutableMap()
        listOf("stop_sequences", "top_k", "temperature", "top_p", "inference_geo").forEach(fields::remove)
        (fields["max_tokens"] as? JsonPrimitive)?.intOrNull?.let { fields["max_tokens"] = JsonPrimitive(it.coerceIn(1, 524_288)) }
        (fields["output_config"] as? JsonObject)?.let { config ->
            val effort = config.filterKeys { it == "effort" }
            if (effort.isEmpty()) fields.remove("output_config") else fields["output_config"] = JsonObject(effort)
        }
        if (params.claudeOptions.toolChoice == ClaudeToolChoice.NONE) fields.remove("tools")
        if ((fields["tools"] as? JsonArray)?.isNotEmpty() == true) fields["tool_choice"] = buildJsonObject { put("type", "auto") }
        else fields.remove("tool_choice")
        return JsonObject(fields)
    }
    if (vendor != OfficialChatVendor.MIMO || !params.model.isMiMo26()) return this
    val fields = toMutableMap()
    val enabled = params.reasoningLevel != ReasoningLevel.OFF
    fields["thinking"] = buildJsonObject { put("type", if (enabled) "enabled" else "disabled") }
    (fields["max_tokens"] as? JsonPrimitive)?.intOrNull?.let { fields["max_tokens"] = JsonPrimitive(it.coerceIn(1, 131_072)) }
    listOf("output_config", "reasoning_effort", "service_tier", "inference_geo", "cache_control", "top_k").forEach(fields::remove)
    fun blocks(value: JsonElement?): JsonElement? = (value as? JsonArray)?.let { array -> JsonArray(array.map {
        if (it is JsonObject) JsonObject(it - "cache_control") else it
    }) } ?: value
    blocks(fields["system"])?.let { fields["system"] = it }
    blocks(fields["tools"])?.let { fields["tools"] = it }
    (fields["messages"] as? JsonArray)?.let { array -> fields["messages"] = JsonArray(array.map { value ->
        val message = value as? JsonObject ?: return@map value
        val content = blocks(message["content"]) ?: return@map value
        JsonObject(message + ("content" to content))
    }) }
    if (enabled) { fields.remove("temperature"); fields.remove("top_p") }
    else {
        params.temperature?.let { fields["temperature"] = JsonPrimitive(it.coerceIn(0f, 1.5f)) }
        params.topP?.let { fields["top_p"] = JsonPrimitive(it.coerceIn(.01f, 1f)) }
    }
    if (params.claudeOptions.toolChoice == ClaudeToolChoice.NONE) fields.remove("tools")
    if ((fields["tools"] as? JsonArray)?.isNotEmpty() == true) fields["tool_choice"] = buildJsonObject { put("type", "auto") }
    else fields.remove("tool_choice")
    return JsonObject(fields)
}
