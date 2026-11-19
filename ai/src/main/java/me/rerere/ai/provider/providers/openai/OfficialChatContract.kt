package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.*
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.*

fun supportedOpenAIResponseFormats(setting: ProviderSetting.OpenAI, model: Model): List<OpenAIResponseFormat> = when (officialChatVendor(setting.baseUrl)) {
    OfficialChatVendor.KIMI -> when {
        setting.useResponseApi -> listOf(OpenAIResponseFormat.AUTO, OpenAIResponseFormat.JSON_SCHEMA)
        model.modernVendorModel() == ModernVendorModel.KIMI_K3 -> OpenAIResponseFormat.entries
        else -> listOf(OpenAIResponseFormat.AUTO, OpenAIResponseFormat.TEXT, OpenAIResponseFormat.JSON_OBJECT)
    }
    OfficialChatVendor.MINIMAX -> listOf(OpenAIResponseFormat.AUTO)
    OfficialChatVendor.MIMO, OfficialChatVendor.GLM, OfficialChatVendor.DEEPSEEK ->
        listOf(OpenAIResponseFormat.AUTO, OpenAIResponseFormat.TEXT, OpenAIResponseFormat.JSON_OBJECT)
    else -> OpenAIResponseFormat.entries
}

/** Native contracts are applied after custom bodies. Gate vendor-only rules by endpoint ownership. */
internal fun JsonObject.withOfficialChatContract(setting: ProviderSetting.OpenAI, params: TextGenerationParams, responses: Boolean): JsonObject {
    val vendor = officialChatVendor(setting.baseUrl)
    val fields = toMutableMap()
    val options = params.openAIOptions
    val family = params.model.inferParameterFamily()
    val genericOptions = family == null || family == ModelParameterFamily.OPENAI
    if (vendor in setOf(OfficialChatVendor.MIMO, OfficialChatVendor.KIMI, OfficialChatVendor.GLM)) fields.remove("service_tier")
    if (vendor in setOf(OfficialChatVendor.MIMO, OfficialChatVendor.KIMI, OfficialChatVendor.GLM)) fields.remove("service_tier")
    if (genericOptions && options.responseFormat != OpenAIResponseFormat.AUTO) {
        require(options.responseFormat in supportedOpenAIResponseFormats(setting, params.model)) {
            "当前供应商未声明支持此输出格式，请使用 JSON 对象或默认输出。"
        }
        val format = buildJsonObject {
            put("type", options.responseFormat.apiValue!!)
            if (options.responseFormat == OpenAIResponseFormat.JSON_SCHEMA) {
                val schema = parseOutputSchema(options.responseJsonSchema)
                require(schema["type"] == JsonPrimitive("object") || schema["properties"] is JsonObject) {
                    "此接口的严格 JSON Schema 根节点必须是 object。"
                }
                val name = options.responseSchemaName.takeIf { Regex("[a-zA-Z0-9_-]{1,64}").matches(it) } ?: "response"
                if (responses) {
                    put("name", name); put("strict", true); put("schema", schema.closedOutputSchema(requireAll = true))
                } else put("json_schema", buildJsonObject {
                    put("name", name); put("strict", true); put("schema", schema.closedOutputSchema(requireAll = true))
                })
            }
        }
        if (responses) fields["text"] = JsonObject((fields["text"] as? JsonObject).orEmpty() + ("format" to format))
        else fields["response_format"] = format
    }
    if (!responses && genericOptions) {
        if (vendor in setOf(OfficialChatVendor.MIMO, OfficialChatVendor.GLM, OfficialChatVendor.OTHER) ||
            (vendor == OfficialChatVendor.OPENAI && allowsOpenAISampling(params))) {
            options.presencePenalty?.takeIf { it in -2f..2f }?.let { fields["presence_penalty"] = JsonPrimitive(it) }
            options.frequencyPenalty?.takeIf { it in -2f..2f }?.let { fields["frequency_penalty"] = JsonPrimitive(it) }
            if (vendor == OfficialChatVendor.OPENAI || vendor == OfficialChatVendor.OTHER)
                options.seed?.let { fields["seed"] = JsonPrimitive(it) }
        }
        if (vendor != OfficialChatVendor.MINIMAX && options.stopSequences.isNotEmpty() &&
            (vendor != OfficialChatVendor.OPENAI || allowsOpenAISampling(params))) {
            fields["stop"] = JsonArray(options.stopSequences.filter(String::isNotEmpty)
                .take(if (vendor == OfficialChatVendor.GLM) 1 else 4).map(::JsonPrimitive))
        }
    }
    if (vendor == OfficialChatVendor.MIMO && params.model.isMiMo26()) {
        fields.remove("reasoning_effort")
        val enabled = if (responses) {
            val effort = ((fields["reasoning"] as? JsonObject)?.get("effort") as? JsonPrimitive)?.contentOrNull
            if (effort != null) effort !in setOf("none", "minimal") else params.reasoningLevel != ReasoningLevel.OFF
        } else {
            ((fields["thinking"] as? JsonObject)?.get("type") as? JsonPrimitive)?.contentOrNull
                ?.let { it != "disabled" } ?: (params.reasoningLevel != ReasoningLevel.OFF)
        }
        if (responses) {
            fields.remove("thinking")
            fields["reasoning"] = buildJsonObject { put("effort", if (enabled) "high" else "none") }
            fields.remove("include") // MiMo has reasoning text, no documented encrypted-content include.
        } else {
            fields.remove("reasoning")
            fields["thinking"] = buildJsonObject { put("type", if (enabled) "enabled" else "disabled") }
            fields["max_tokens"]?.let { if ("max_completion_tokens" !in fields) fields["max_completion_tokens"] = it }
            fields.remove("max_tokens")
        }
        if (fields["tool_choice"] == JsonPrimitive("none")) fields.remove("tools")
        if ((fields["tools"] as? JsonArray)?.isNotEmpty() == true) fields["tool_choice"] = JsonPrimitive("auto")
        else fields.remove("tool_choice")
        if (enabled) { fields.remove("temperature"); fields.remove("top_p") }
        else {
            (fields["temperature"] as? JsonPrimitive)?.floatOrNull?.let { fields["temperature"] = JsonPrimitive(it.coerceIn(0f, 1.5f)) }
            (fields["top_p"] as? JsonPrimitive)?.floatOrNull?.let { fields["top_p"] = JsonPrimitive(it.coerceIn(.01f, 1f)) }
        }
        val limitKey = if (responses) "max_output_tokens" else "max_completion_tokens"
        (fields[limitKey] as? JsonPrimitive)?.intOrNull?.let { fields[limitKey] = JsonPrimitive(it.coerceIn(1, 131_072)) }
        listOf("parallel_tool_calls", "verbosity", "service_tier", "thinking_budget", "seed").forEach(fields::remove)
    }
    if (vendor == OfficialChatVendor.MINIMAX && params.model.isMiniMaxM3() && !responses) {
        // The native API does not document these OpenAI-only fields.
        listOf("parallel_tool_calls", "tool_choice", "response_format", "frequency_penalty", "presence_penalty", "stop", "seed").forEach(fields::remove)
        (fields["max_completion_tokens"] as? JsonPrimitive)?.intOrNull?.let { fields["max_completion_tokens"] = JsonPrimitive(it.coerceIn(1, 524_288)) }
    }
    if (vendor == OfficialChatVendor.DEEPSEEK && !responses) {
        val enabled = ((fields["thinking"] as? JsonObject)?.get("type") as? JsonPrimitive)?.contentOrNull
            ?.let { it == "enabled" } ?: params.reasoningLevel.isEnabled
        if (enabled) {
            val choice = fields["tool_choice"]
            if (choice == JsonPrimitive("required") || choice is JsonObject) fields["tool_choice"] = JsonPrimitive("auto")
            fields.remove("temperature")
            (fields["top_p"] as? JsonPrimitive)?.floatOrNull?.let { fields["top_p"] = JsonPrimitive(it.coerceIn(.95f, 1f)) }
        } else fields.remove("top_p")
    }
    if (vendor == OfficialChatVendor.KIMI && responses) {
        require(params.model.modernVendorModel() == ModernVendorModel.KIMI_K3) {
            "Kimi 官方 Responses API 仅支持 K3 系列，请为当前模型使用 Chat Completions。"
        }
        listOf("store", "parallel_tool_calls", "service_tier", "temperature", "top_p").forEach(fields::remove)
        (fields["max_output_tokens"] as? JsonPrimitive)?.intOrNull?.let { fields["max_output_tokens"] = JsonPrimitive(it.coerceIn(1, 1_048_576)) }
        (fields["reasoning"] as? JsonObject)?.let { fields["reasoning"] = JsonObject(it.filterKeys { key -> key == "effort" }) }
        (fields["include"] as? JsonArray)?.let { include -> fields["include"] = JsonArray(include.filter {
            it in setOf(JsonPrimitive("web_search_call.results"), JsonPrimitive("web_search_call.action.sources"))
        }) }
    }
    if (vendor == OfficialChatVendor.GLM && responses) {
        val effort = ((fields["reasoning"] as? JsonObject)?.get("effort") as? JsonPrimitive)?.contentOrNull
        val normalized = when (effort) {
            "none", "minimal" -> "none"
            "low", "medium", "high" -> "high"
            "xhigh", "max" -> "max"
            else -> when (params.reasoningLevel) {
                ReasoningLevel.OFF, ReasoningLevel.MINIMAL -> "none"
                ReasoningLevel.AUTO -> null
                ReasoningLevel.LOW, ReasoningLevel.MEDIUM, ReasoningLevel.HIGH -> "high"
                else -> "max"
            }
        }
        fields.remove("reasoning")
        normalized?.let { fields["reasoning"] = buildJsonObject { put("effort", it) } }
        (fields["max_output_tokens"] as? JsonPrimitive)?.intOrNull?.let { fields["max_output_tokens"] = JsonPrimitive(it.coerceIn(1, 131_072)) }
        if (fields["tool_choice"] == JsonPrimitive("required") || fields["tool_choice"] is JsonObject) fields["tool_choice"] = JsonPrimitive("auto")
        (fields["temperature"] as? JsonPrimitive)?.floatOrNull?.let { fields["temperature"] = JsonPrimitive(it.coerceIn(0f, 1f)) }
        if (fields.containsKey("temperature")) fields.remove("top_p")
        else (fields["top_p"] as? JsonPrimitive)?.floatOrNull?.let { fields["top_p"] = JsonPrimitive(it.coerceIn(.01f, 1f)) }
    }
    return if (vendor != OfficialChatVendor.OTHER) JsonObject(fields).ensureJsonModeInstruction(responses) else JsonObject(fields)
}

private fun JsonObject.ensureJsonModeInstruction(responses: Boolean): JsonObject {
    val format = if (responses) ((get("text") as? JsonObject)?.get("format") as? JsonObject) else get("response_format") as? JsonObject
    if (format?.get("type") != JsonPrimitive("json_object")) return this
    val key = if (responses) "input" else "messages"
    val messages = get(key) as? JsonArray ?: return this
    fun textHasJson(content: JsonElement?): Boolean = when (content) {
        is JsonPrimitive -> content.content.contains("json", ignoreCase = true)
        is JsonArray -> content.any { textHasJson((it as? JsonObject)?.get("text")) }
        else -> false
    }
    if (messages.any { value -> val message = value as? JsonObject
        message?.get("role") in setOf(JsonPrimitive("system"), JsonPrimitive("developer"), JsonPrimitive("user")) && textHasJson(message?.get("content")) }) return this
    val instruction = buildJsonObject {
        put("role", if (responses) "developer" else "system")
        put("content", "Return the final answer as a JSON object, without Markdown fences or additional prose.")
    }
    return JsonObject(this + (key to JsonArray(listOf(instruction) + messages)))
}
