package me.rerere.ai.provider.providers.openai

import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.cappedEffort

data class GrokModelParameterSupport(
    val available: Boolean,
    val reasoningModel: Boolean,
    val supportsPresencePenalty: Boolean,
    val supportsReasoningEffort: Boolean,
    val supportsDisableReasoning: Boolean,
    val maximumReasoningEffort: ReasoningLevel,
    val supportsLogProbs: Boolean,
)

fun resolveGrokModelParameterSupport(modelId: String): GrokModelParameterSupport {
    val normalized = modelId.normalizedGrokModelId()
    val available = normalized.startsWith("grok-") &&
        GROK_NON_TEXT_MODEL_MARKERS.none(normalized::contains)
    val is43 = GROK_4_3_SERIES.containsMatchIn(normalized)
    val is45 = GROK_4_5_SERIES.containsMatchIn(normalized)
    val is46 = GROK_4_6_SERIES.containsMatchIn(normalized)
    val isMultiAgent420 = GROK_4_20_MULTI_AGENT_SERIES.containsMatchIn(normalized)
    val reasoningModel = GROK_4_SERIES.containsMatchIn(normalized) ||
        normalized.startsWith("grok-3-mini")
    val supportsReasoningEffort = available && (is43 || is45 || is46 || isMultiAgent420)
    return GrokModelParameterSupport(
        available = available,
        reasoningModel = available && reasoningModel,
        supportsPresencePenalty = available && !normalized.startsWith("grok-3"),
        supportsReasoningEffort = supportsReasoningEffort,
        supportsDisableReasoning = available && is43,
        maximumReasoningEffort = if (is46 || isMultiAgent420) {
            ReasoningLevel.XHIGH
        } else {
            ReasoningLevel.HIGH
        },
        supportsLogProbs = available && !GROK_4_20_OR_LATER.containsMatchIn(normalized),
    )
}

internal fun GrokModelParameterSupport.reasoningEffort(level: ReasoningLevel): String? {
    if (!supportsReasoningEffort || level == ReasoningLevel.AUTO) return null
    if (level == ReasoningLevel.OFF) return if (supportsDisableReasoning) "none" else "low"
    return level.cappedEffort(maximumReasoningEffort)
}

internal fun String.normalizedGrokModelId(): String =
    substringAfterLast('/').substringAfterLast(':').trim().lowercase()

internal const val XAI_API_HOST = "api.x.ai"

private val GROK_4_3_SERIES = Regex("^grok-4[.-]3(?:[.-]|$)")
private val GROK_4_SERIES = Regex("^grok-4(?:[.-]|$)")
private val GROK_4_5_SERIES = Regex("^grok-4[.-]5(?:[.-]|$)")
private val GROK_4_6_SERIES = Regex("^grok-4[.-]6(?:[.-]|$)")
private val GROK_4_20_MULTI_AGENT_SERIES = Regex("^grok-4[.-]20(?:[.-].*)?multi-agent(?:[.-]|$)")
private val GROK_4_20_OR_LATER = Regex("""^grok-4[.-](?:2\d|[3-9]\d|\d{3,})(?:[.-]|$)""")
private val GROK_NON_TEXT_MODEL_MARKERS = setOf(
    "image",
    "imagine",
    "video",
    "voice",
    "audio",
)
