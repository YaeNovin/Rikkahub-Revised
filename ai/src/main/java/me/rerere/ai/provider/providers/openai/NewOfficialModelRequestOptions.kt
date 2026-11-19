package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.*
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.parameterModelId

/** Apply native API constraints after custom bodies, without changing gateway contracts. */
internal fun JsonObject.withNewOfficialModelOptions(params: TextGenerationParams, host: String, responses: Boolean): JsonObject {
    val identity = params.model.parameterModelId().normalizedOpenAIModelId()
    val gpt62 = isOfficialOpenAIHost(host) && Regex("^gpt-6[.-]2(?:[.-]|$)").containsMatchIn(identity)
    val qwen4 = isAlibabaModelStudioHost(host) && isQwen4Max(params.model.parameterModelId())
    val cacheOptions = responses && isOfficialOpenAIHost(host) && resolveOpenAIModelParameterSupport(identity).supportsReasoningContext
    if (!gpt62 && !qwen4 && !cacheOptions) return this
    val body = toMutableMap()
    if (cacheOptions) params.openAIOptions.promptCacheTtl.apiValue?.let { ttl ->
            body["prompt_cache_options"] = JsonObject((body["prompt_cache_options"] as? JsonObject).orEmpty() + ("ttl" to JsonPrimitive(ttl)))
    }
    if (!gpt62 && !qwen4) return JsonObject(body)
    if (gpt62) {
        listOf("temperature", "top_p", "logprobs", "top_logprobs").forEach(body::remove)
        (body["reasoning"] as? JsonObject)?.let { reasoning ->
            val effort = (reasoning["effort"] as? JsonPrimitive)?.contentOrNull
            if (effort != null && effort !in setOf("low", "medium", "high", "xhigh", "max"))
                body["reasoning"] = JsonObject(reasoning + ("effort" to JsonPrimitive("medium")))
        }
        if (responses && body.remove("prompt_cache_retention") != null && "prompt_cache_options" !in body) {
            body["prompt_cache_options"] = buildJsonObject { put("ttl", "30m") }
        }
    }
    if (qwen4 && !responses) {
        (body["messages"] as? JsonArray)?.let { messages ->
            body["messages"] = JsonArray(messages.map messageMap@ { value ->
                val message = value as? JsonObject ?: return@messageMap value
                val content = message["content"] as? JsonArray ?: return@messageMap value
                JsonObject(message + ("content" to JsonArray(content.map partMap@ { partValue ->
                    val part = partValue as? JsonObject ?: return@partMap partValue
                    val image = part["image_url"] as? JsonObject ?: return@partMap partValue
                    val fields = part.toMutableMap()
                    params.qwenOptions.imageDetail?.takeIf { it in setOf("auto", "low", "high") }?.let {
                        fields["image_url"] = JsonObject(image + ("detail" to JsonPrimitive(it)))
                    }
                    params.qwenOptions.imageMaxPixels?.takeIf { it > 0 }?.let {
                        fields["max_pixels"] = JsonPrimitive(it.coerceAtMost(16_777_216))
                    }
                    JsonObject(fields)
                })))
            })
        }
        val enabled = (body["enable_thinking"] as? JsonPrimitive)?.booleanOrNull ?: params.reasoningLevel.isEnabled
        if (!enabled) { body.remove("reasoning_effort"); body.remove("thinking_budget") }
        else if ("reasoning_effort" in body) {
            body.remove("thinking_budget")
            val effort = (body["reasoning_effort"] as? JsonPrimitive)?.contentOrNull
            body["reasoning_effort"] = JsonPrimitive(when (effort) {
                "minimal", "low" -> "low"; "xhigh", "max" -> "xhigh"; else -> "high"
            })
        }
    }
    val thinking = (body["enable_thinking"] as? JsonPrimitive)?.booleanOrNull ?: params.reasoningLevel.isEnabled
    val limit = if (gpt62) 128_000 else if (thinking) 262_144 else 131_072
    val keys = if (responses) listOf("max_output_tokens") else listOf("max_completion_tokens", "max_tokens")
    keys.forEach { key -> (body[key] as? JsonPrimitive)?.intOrNull?.let { body[key] = JsonPrimitive(it.coerceIn(1, limit)) } }
    if (qwen4 && !responses && "max_completion_tokens" in body) body.remove("max_tokens")
    return JsonObject(body)
}
