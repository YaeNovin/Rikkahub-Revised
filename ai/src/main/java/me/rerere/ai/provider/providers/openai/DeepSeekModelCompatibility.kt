package me.rerere.ai.provider.providers.openai

import me.rerere.ai.core.ReasoningLevel

data class DeepSeekModelParameterSupport(
    val available: Boolean,
    val supportsVision: Boolean,
)

fun resolveDeepSeekModelParameterSupport(modelId: String): DeepSeekModelParameterSupport {
    val normalized = modelId.normalizedDeepSeekModelId()
    return DeepSeekModelParameterSupport(
        available = normalized in CURRENT_DEEPSEEK_MODELS,
        supportsVision = normalized == DEEPSEEK_VISION_MODEL,
    )
}

fun isOfficialDeepSeekHost(host: String): Boolean =
    host.trim().lowercase() == DEEPSEEK_API_HOST

internal fun DeepSeekModelParameterSupport.reasoningEffort(level: ReasoningLevel): String? {
    if (!available || level == ReasoningLevel.AUTO) return null
    return when (level) {
        ReasoningLevel.OFF -> "none"
        ReasoningLevel.LOW -> "low"
        ReasoningLevel.MEDIUM,
        ReasoningLevel.HIGH,
        ReasoningLevel.XHIGH -> "high"
        ReasoningLevel.MAX -> "max"
        ReasoningLevel.AUTO -> null
    }
}

internal fun String.normalizedDeepSeekModelId(): String =
    substringAfterLast('/').substringAfterLast(':').trim().lowercase().replace('_', '-')

internal const val DEEPSEEK_API_HOST = "api.deepseek.com"
private const val DEEPSEEK_VISION_MODEL = "deepseek-v4-flash-vision-exp"
private val CURRENT_DEEPSEEK_MODELS = setOf(
    "deepseek-v4-flash",
    "deepseek-v4-pro",
    DEEPSEEK_VISION_MODEL,
)
