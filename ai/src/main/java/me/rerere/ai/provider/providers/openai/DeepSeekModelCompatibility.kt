package me.rerere.ai.provider.providers.openai

import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.normalizeCompactVendorModelId

data class DeepSeekModelParameterSupport(
    val available: Boolean,
    val supportsVision: Boolean,
    val supportsReasoningEffort: Boolean,
)

fun resolveDeepSeekModelParameterSupport(modelId: String): DeepSeekModelParameterSupport {
    val normalized = modelId.normalizedDeepSeekModelId()
    val available = normalized.startsWith("deepseek") &&
        DEEPSEEK_NON_CHAT_MARKERS.none(normalized::contains)
    return DeepSeekModelParameterSupport(
        available = available,
        supportsVision = normalized == DEEPSEEK_VISION_MODEL,
        supportsReasoningEffort = normalized.startsWith("deepseek-v4"),
    )
}

fun isOfficialDeepSeekHost(host: String): Boolean =
    host.trim().lowercase() == DEEPSEEK_API_HOST

internal fun DeepSeekModelParameterSupport.reasoningEffort(level: ReasoningLevel): String? {
    if (!available || !supportsReasoningEffort || level == ReasoningLevel.AUTO) return null
    return when (level) {
        ReasoningLevel.OFF -> "none"
        ReasoningLevel.MINIMAL,
        ReasoningLevel.LOW -> "low"
        ReasoningLevel.MEDIUM,
        ReasoningLevel.HIGH,
        ReasoningLevel.XHIGH -> "high"
        ReasoningLevel.MAX -> "max"
        ReasoningLevel.AUTO -> null
    }
}

internal fun String.normalizedDeepSeekModelId(): String =
    substringAfterLast('/').substringAfterLast(':').trim().lowercase()
        .replace(Regex("[\\s_]+"), "-")
        .replace(Regex("^deepseek(?=[rv]?\\d)"), "deepseek-")
        .normalizeCompactVendorModelId()

internal const val DEEPSEEK_API_HOST = "api.deepseek.com"
private const val DEEPSEEK_VISION_MODEL = "deepseek-v4-flash-vision-exp"
private val DEEPSEEK_NON_CHAT_MARKERS = setOf(
    "embedding",
    "rerank",
    "ocr",
    "tts",
)
