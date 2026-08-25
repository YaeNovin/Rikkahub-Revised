package me.rerere.ai.provider.providers.openai

import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.ProviderRequestChannel
import me.rerere.ai.provider.ProviderSetting
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class OpenAIModelParameterSupport(
    val available: Boolean,
    val retired: Boolean,
    val supportsVerbosity: Boolean,
    val supportsReasoningOptions: Boolean,
    val supportsReasoningContext: Boolean,
    val supportsReasoningMode: Boolean,
    val supportsUltrafast: Boolean,
)

fun resolveOpenAIModelParameterSupport(modelId: String): OpenAIModelParameterSupport {
    val normalized = modelId.normalizedOpenAIModelId()
    val retired = normalized.isRetiredOpenAITextModel()
    val isTextModel = normalized.isOpenAITextModel()
    val isGpt5 = GPT_5_SERIES.containsMatchIn(normalized)
    val isGpt56 = GPT_5_6_SERIES.containsMatchIn(normalized)
    return OpenAIModelParameterSupport(
        available = isTextModel && !retired,
        retired = retired,
        supportsVerbosity = isTextModel && !retired && isGpt5,
        supportsReasoningOptions = isTextModel && !retired && isGpt5,
        supportsReasoningContext = isTextModel && !retired && isGpt56,
        supportsReasoningMode = isTextModel && !retired && isGpt56,
        supportsUltrafast = normalized == "gpt-5.6-sol" ||
            normalized.startsWith("gpt-5.6-sol-"),
    )
}

fun ProviderSetting.OpenAI.requestChannel(): ProviderRequestChannel {
    val host = baseUrl.toHttpUrlOrNull()?.host
    return when (host) {
        OPENAI_API_HOST -> ProviderRequestChannel.OPENAI_API
        XAI_API_HOST -> ProviderRequestChannel.XAI_API
        else -> ProviderRequestChannel.COMPATIBLE_ENDPOINT
    }
}

internal fun String.normalizedOpenAIModelId(): String =
    substringAfterLast('/').trim().lowercase()

internal fun isOpenAIGpt5Model(modelId: String): Boolean =
    GPT_5_SERIES.containsMatchIn(modelId.normalizedOpenAIModelId())

internal fun resolveOpenAIMaximumReasoningEffort(modelId: String): ReasoningLevel {
    val normalized = modelId.normalizedOpenAIModelId()
    return when {
        GPT_5_6_SERIES.containsMatchIn(normalized) -> ReasoningLevel.MAX
        GPT_5_2_OR_LATER.containsMatchIn(normalized) -> ReasoningLevel.XHIGH
        else -> ReasoningLevel.HIGH
    }
}

private fun String.isOpenAITextModel(): Boolean {
    if (!startsWith("gpt-")) return false
    if (OPENAI_NON_TEXT_MODEL_MARKERS.any(::contains)) return false
    return startsWith("gpt-3.5-turbo") ||
        startsWith("gpt-4") ||
        startsWith("gpt-5")
}

private fun String.isRetiredOpenAITextModel(): Boolean {
    if (this in RETIRED_OPENAI_TEXT_MODELS) return true
    if (startsWith("gpt-5-codex") || startsWith("gpt-5.1-codex") || startsWith("gpt-5.2-codex")) {
        return true
    }
    return RETIRED_OPENAI_TEXT_SNAPSHOTS.any { it.matches(this) }
}

internal const val OPENAI_API_HOST = "api.openai.com"

private val GPT_5_SERIES = Regex("^gpt-5(?:[.-]|$)")
private val GPT_5_6_SERIES = Regex("^gpt-5[.-]6(?:[.-]|$)")
private val GPT_5_2_OR_LATER = Regex("^gpt-5[.-](?:[2-9]|\\d{2,})(?:[.-]|$)")

private val OPENAI_NON_TEXT_MODEL_MARKERS = setOf(
    "audio",
    "codex",
    "image",
    "realtime",
    "search-preview",
    "transcribe",
    "tts",
)

private val RETIRED_OPENAI_TEXT_MODELS = setOf(
    "chatgpt-4o-latest",
    "gpt-4-turbo-preview",
    "gpt-4-vision-preview",
    "gpt-4.5-preview",
    "gpt-5-chat-latest",
    "gpt-5.1-chat-latest",
    "gpt-5.2-chat-latest",
    "gpt-5.3-chat-latest",
)

private val RETIRED_OPENAI_TEXT_SNAPSHOTS = listOf(
    Regex("^gpt-3[.]5-turbo-(?:0301|0613|16k-0613)$"),
    Regex("^gpt-4-(?:0314|1106-preview|0125-preview)$"),
    Regex("^gpt-4-32k(?:-0314|-0613)?$"),
    Regex("^gpt-4-1106-vision-preview$"),
)
