package me.rerere.ai.provider

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import me.rerere.ai.core.ReasoningLevel

@Serializable
data class MiniMaxGenerationOptions(
    val thinking: MiniMaxThinking = MiniMaxThinking.AUTO,
    val serviceTier: MiniMaxServiceTier = MiniMaxServiceTier.AUTO,
    val reasoningSplit: Boolean? = null,
)

@Serializable enum class MiniMaxThinking(val apiValue: String?) { AUTO(null), ADAPTIVE("adaptive"), DISABLED("disabled") }
@Serializable enum class MiniMaxServiceTier(val apiValue: String?) { AUTO(null), STANDARD("standard"), PRIORITY("priority") }

fun Model.isMiniMaxM3(): Boolean = inferParameterFamily() == ModelParameterFamily.MINIMAX &&
    Regex("(?:^|[^a-z0-9])minimax-?m3(?:-|$)").containsMatchIn(parameterModelId().lowercase().replace(Regex("[ ._]"), "-"))

/** Native M3 contracts. M2 and Responses relays keep their existing generic settings. */
fun JsonObject.withMiniMaxOptions(params: TextGenerationParams, protocol: String): JsonObject {
    if (!params.model.isMiniMaxM3() || protocol == "responses") return this
    val options = params.miniMaxOptions
    val flash = params.model.modernVendorModel() == ModernVendorModel.MINIMAX_M31
    return JsonObject(toMutableMap().apply {
        listOf("temperature", "top_p", "top_k").forEach(::remove)
        if (!flash) {
            remove("reasoning_effort")
            remove("reasoning")
            (get("output_config") as? JsonObject)?.let { put("output_config", JsonObject(it - "effort")) }
            val thinking = options.thinking.apiValue ?: when (params.reasoningLevel) {
                ReasoningLevel.OFF -> "disabled"
                ReasoningLevel.AUTO -> null
                else -> "adaptive"
            }
            thinking?.let { put("thinking", buildJsonObject { put("type", it) }) }
            options.serviceTier.apiValue?.let { put("service_tier", JsonPrimitive(it)) }
            if (protocol == "chat") options.reasoningSplit?.let { put("reasoning_split", JsonPrimitive(it)) }
        }
        if (protocol == "anthropic") {
            remove("reasoning_split")
            if (get("tool_choice") != null) put("tool_choice", buildJsonObject { put("type", "auto") })
        }
    })
}
