package me.rerere.ai.provider

import kotlinx.serialization.json.*
import me.rerere.ai.core.ReasoningLevel

enum class ModernVendorModel { MINIMAX_M31, GLM_53, KIMI_K3, KIMI_K27 }

fun Model.modernVendorModel(): ModernVendorModel? {
    val id = parameterModelId().lowercase().replace(Regex("[ ._]"), "-")
    return when {
        Regex("(?:^|[^a-z0-9])minimax-?m3-1(?:-|$)").containsMatchIn(id) -> ModernVendorModel.MINIMAX_M31
        Regex("(?:^|[^a-z0-9])glm-5-3(?:-|$)").containsMatchIn(id) -> ModernVendorModel.GLM_53
        Regex("(?:^|[^a-z0-9])(?:kimi-)?k3(?:-1(?:-pro)?(?:-highspeed)?)?(?:$|-)").containsMatchIn(id) -> ModernVendorModel.KIMI_K3
        Regex("(?:^|[^a-z0-9])kimi-k2-7-code(?:-|$)").containsMatchIn(id) -> ModernVendorModel.KIMI_K27
        else -> null
    }
}

fun Model.modernVendorReasoningLevels(): List<ReasoningLevel>? = when (modernVendorModel()) {
    ModernVendorModel.MINIMAX_M31 -> listOf(ReasoningLevel.AUTO, ReasoningLevel.LOW, ReasoningLevel.MEDIUM,
        ReasoningLevel.HIGH, ReasoningLevel.XHIGH, ReasoningLevel.MAX)
    ModernVendorModel.GLM_53, ModernVendorModel.KIMI_K3 -> listOf(ReasoningLevel.AUTO,
        ReasoningLevel.LOW, ReasoningLevel.HIGH, ReasoningLevel.MAX)
    ModernVendorModel.KIMI_K27 -> listOf(ReasoningLevel.AUTO)
    null -> null
}

/** Contract-specific validation also applies to opaque proxy IDs resolved through displayName. */
fun JsonObject.withModernVendorOptions(params: TextGenerationParams, protocol: String): JsonObject {
    val vendor = params.model.modernVendorModel() ?: return this
    val level = params.reasoningLevel
    val effort = when (level) {
        ReasoningLevel.AUTO -> "max"
        ReasoningLevel.OFF, ReasoningLevel.MINIMAL -> if (protocol == "responses" && vendor == ModernVendorModel.GLM_53) "none" else "low"
        ReasoningLevel.LOW -> "low"
        ReasoningLevel.MEDIUM -> if (vendor == ModernVendorModel.MINIMAX_M31) "medium" else "high"
        ReasoningLevel.HIGH -> "high"
        ReasoningLevel.XHIGH -> if (vendor == ModernVendorModel.MINIMAX_M31) "xhigh" else "max"
        ReasoningLevel.MAX -> "max"
    }
    val body = toMutableMap()
    body.remove("thinking")
    body.remove("reasoning_effort")
    if (vendor == ModernVendorModel.MINIMAX_M31) {
        // M3.1 always returns split reasoning and does not support admission tiers.
        body.remove("service_tier")
        body.remove("reasoning_split")
        body.remove("temperature")
        body.remove("top_p")
    }
    if (vendor == ModernVendorModel.KIMI_K3 || vendor == ModernVendorModel.KIMI_K27) {
        listOf("temperature", "top_p", "top_k", "n", "presence_penalty", "frequency_penalty").forEach(body::remove)
    }
    if (vendor == ModernVendorModel.KIMI_K27) {
        body.remove("reasoning")
        if ((body["tool_choice"] as? JsonPrimitive)?.contentOrNull == "required")
            body["tool_choice"] = JsonPrimitive("auto")
    }
    if (protocol == "anthropic") {
        body.remove("top_k")
        if (vendor == ModernVendorModel.MINIMAX_M31) {
            body["thinking"] = buildJsonObject { put("type", "adaptive") }
            body["output_config"] = JsonObject((body["output_config"] as? JsonObject).orEmpty() + ("effort" to JsonPrimitive(effort)))
        } else (body["output_config"] as? JsonObject)?.let { config ->
            body["output_config"] = JsonObject(config.filterKeys { it != "effort" })
        }
    } else if (protocol == "responses") {
        // Only normalize an effort already requested through the Responses contract.
        // Native vendor docs confirm Chat Completions, not every relay's Responses API.
        val reasoning = body["reasoning"] as? JsonObject
        if (vendor != ModernVendorModel.KIMI_K27 && reasoning?.containsKey("effort") == true)
            body["reasoning"] = JsonObject(reasoning + ("effort" to JsonPrimitive(effort)))
    } else {
        if (vendor == ModernVendorModel.GLM_53) body["thinking"] = buildJsonObject {
            put("type", "enabled"); put("clear_thinking", false)
        }
        if (vendor != ModernVendorModel.KIMI_K27) body["reasoning_effort"] = JsonPrimitive(effort)
    }
    return JsonObject(body)
}

internal fun knownModernVendorContext(modelId: String): Int? {
    val model = Model(modelId = modelId)
    if (model.isMiMo26()) return 1_000_000
    return when (model.modernVendorModel()) {
        ModernVendorModel.MINIMAX_M31, ModernVendorModel.GLM_53, ModernVendorModel.KIMI_K3 -> 1_000_000
        ModernVendorModel.KIMI_K27 -> 262_144
        null -> null
    }
}
