package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.rerere.ai.provider.ProviderRequestDiagnostics
import me.rerere.ai.provider.ProviderRequestOperation
import me.rerere.ai.provider.ProviderSetting

internal fun JsonObject.openAIRequestDiagnostics(
    providerSetting: ProviderSetting.OpenAI,
    operation: ProviderRequestOperation,
    api: String,
    requestId: String? = null,
): ProviderRequestDiagnostics {
    val reasoning = this["reasoning"] as? JsonObject
    val text = this["text"] as? JsonObject
    val parameters = linkedMapOf("api" to api)

    listOf(
        "temperature",
        "top_p",
        "max_tokens",
        "max_completion_tokens",
        "max_output_tokens",
        "reasoning_effort",
        "frequency_penalty",
        "repetition_penalty",
        "presence_penalty",
        "seed",
        "min_p",
        "top_k",
        "verbosity",
        "service_tier",
        "parallel_tool_calls",
        "tool_choice",
        "max_tool_calls",
        "max_turns",
        "preserve_thinking",
        "tool_stream",
        "vl_high_resolution_images",
        "logprobs",
        "top_logprobs",
        "store",
    ).forEach { key ->
        (this[key] as? JsonPrimitive)?.contentOrNull?.let { parameters[key] = it }
    }

    (text?.get("verbosity") as? JsonPrimitive)?.contentOrNull?.let {
        parameters["text.verbosity"] = it
    }
    ((this["response_format"] as? JsonObject)?.get("type") as? JsonPrimitive)
        ?.contentOrNull
        ?.let { parameters["response_format.type"] = it }
    ((text?.get("format") as? JsonObject)?.get("type") as? JsonPrimitive)
        ?.contentOrNull
        ?.let { parameters["text.format.type"] = it }
    (this["stop"] as? JsonArray)?.let { parameters["stop.count"] = it.size.toString() }
    if (containsKey("user_id")) parameters["user_id"] = "configured"
    if (containsKey("user")) parameters["user"] = "configured"
    findImageDetail()?.let { parameters["image.detail"] = it }
    listOf("effort", "summary", "context", "mode").forEach { key ->
        (reasoning?.get(key) as? JsonPrimitive)?.contentOrNull?.let {
            parameters["reasoning.$key"] = it
        }
    }
    (this["tools"] as? JsonArray)?.let { parameters["tools.count"] = it.size.toString() }

    return ProviderRequestDiagnostics(
        provider = providerSetting.name.ifBlank { "OpenAI" },
        model = (this["model"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
        channel = providerSetting.requestChannel(),
        operation = operation,
        parameters = parameters,
        requestId = requestId,
    )
}

private fun JsonObject.findImageDetail(): String? {
    fun kotlinx.serialization.json.JsonElement.find(): String? = when (this) {
        is JsonObject -> {
            (get("detail") as? JsonPrimitive)?.contentOrNull
                ?: values.firstNotNullOfOrNull { it.find() }
        }
        is JsonArray -> firstNotNullOfOrNull { it.find() }
        else -> null
    }
    return (get("messages") ?: get("input"))?.find()
}
