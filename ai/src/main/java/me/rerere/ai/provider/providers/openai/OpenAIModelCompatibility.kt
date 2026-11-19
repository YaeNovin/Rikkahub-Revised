package me.rerere.ai.provider.providers.openai

import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.ProviderRequestChannel
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.normalizeCompactVendorModelId
import me.rerere.ai.provider.parameterModelId
import me.rerere.ai.provider.isDecisionModel
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class OpenAIModelParameterSupport(
    val available: Boolean,
    val retired: Boolean,
    val supportsVerbosity: Boolean,
    val supportsReasoningOptions: Boolean,
    val supportsReasoningContext: Boolean,
    val supportsReasoningMode: Boolean,
    val supportsUltrafast: Boolean,
    val requiresResponsesForToolCalling: Boolean,
)

fun resolveOpenAIModelParameterSupport(modelId: String): OpenAIModelParameterSupport {
    val normalized = modelId.normalizedOpenAIModelId()
    val retired = normalized.isRetiredOpenAITextModel()
    val isTextModel = normalized.isOpenAITextModel()
    val isGpt5 = GPT_5_SERIES.containsMatchIn(normalized)
    val isGpt6 = GPT_6_SERIES.containsMatchIn(normalized)
    val supportsPersistentReasoning = GPT_5_6_SERIES.containsMatchIn(normalized) || isGpt6
    return OpenAIModelParameterSupport(
        available = isTextModel && !retired,
        retired = retired,
        supportsVerbosity = isTextModel && !retired && (isGpt5 || isGpt6),
        supportsReasoningOptions = isTextModel && !retired && (isGpt5 || isGpt6),
        supportsReasoningContext = isTextModel && !retired && supportsPersistentReasoning,
        supportsReasoningMode = isTextModel && !retired && supportsPersistentReasoning,
        supportsUltrafast = ULTRAFAST_MODELS.any { normalized == it || normalized.startsWith("$it-") },
        requiresResponsesForToolCalling = isTextModel && !retired && isGpt6,
    )
}

fun ProviderSetting.OpenAI.requestChannel(): ProviderRequestChannel {
    val host = baseUrl.toHttpUrlOrNull()?.host
    return when (host) {
        OPENAI_API_HOST -> ProviderRequestChannel.OPENAI_API
        XAI_API_HOST -> ProviderRequestChannel.XAI_API
        else -> if (isOfficialOpenAIHost(host)) ProviderRequestChannel.OPENAI_API else ProviderRequestChannel.COMPATIBLE_ENDPOINT
    }
}

// Official GPT-6 tool calling uses Responses; compatible services retain their chosen protocol.
fun isOfficialOpenAIHost(host: String?): Boolean = host == OPENAI_API_HOST || host?.endsWith(".api.openai.com") == true

internal fun isUltrafastOpenAIHost(host: String?): Boolean = host in setOf(OPENAI_API_HOST, "us.api.openai.com")

fun supportsOpenAIUltrafastEndpoint(baseUrl: String): Boolean = isUltrafastOpenAIHost(baseUrl.toHttpUrlOrNull()?.host)

internal fun ProviderSetting.OpenAI.shouldUseResponsesApi(params: TextGenerationParams): Boolean {
    require(!params.model.isDecisionModel()) {
        "decision-model-preview 不生成聊天文本，请在聊天建议设置中选择为决策评估模型"
    }
    return useResponseApi || (requestChannel() == ProviderRequestChannel.OPENAI_API &&
        resolveOpenAIModelParameterSupport(params.model.parameterModelId()).requiresResponsesForToolCalling)
}

internal fun allowsOpenAISampling(params: TextGenerationParams): Boolean {
    val modelId = params.model.parameterModelId()
    if (!isOpenAIGpt5Model(modelId)) return true
    return GPT_6_SERIES.containsMatchIn(modelId.normalizedOpenAIModelId()) &&
        params.reasoningLevel == ReasoningLevel.OFF &&
        ReasoningLevel.OFF in resolveOpenAIReasoningLevels(modelId)
}

internal fun supportsOpenAIChatTools(params: TextGenerationParams): Boolean {
    val modelId = params.model.parameterModelId()
    return !resolveOpenAIModelParameterSupport(modelId).requiresResponsesForToolCalling ||
        (GPT_6_CHAT_TOOLS_WITHOUT_REASONING.containsMatchIn(modelId.normalizedOpenAIModelId()) &&
            params.reasoningLevel == ReasoningLevel.OFF)
}

internal fun String.normalizedOpenAIModelId(): String =
    substringAfterLast('/').trim().lowercase()
        .replace(Regex("[\\s_]+"), "-")
        .replace(Regex("^(gpt|chatgpt|o)(?=\\d)"), "$1-")
        .replace(Regex("^chatgpt-(?=(?:5|6)(?:[.-]|$))"), "gpt-")
        .normalizeCompactVendorModelId()

internal fun isOpenAIGpt5Model(modelId: String): Boolean =
    modelId.normalizedOpenAIModelId().let { normalized ->
        GPT_5_SERIES.containsMatchIn(normalized) || GPT_6_SERIES.containsMatchIn(normalized)
    }

internal fun resolveOpenAIMaximumReasoningEffort(modelId: String): ReasoningLevel {
    return resolveOpenAIReasoningLevels(modelId)
        .lastOrNull { it != ReasoningLevel.AUTO && it != ReasoningLevel.OFF }
        ?: ReasoningLevel.HIGH
}

internal fun resolveOpenAIReasoningLevels(modelId: String): List<ReasoningLevel> {
    val normalized = modelId.normalizedOpenAIModelId().replace('_', '-')
    return when {
        GPT_6_SERIES.containsMatchIn(normalized) -> buildList {
            if (GPT_6_OPTIONAL_REASONING.containsMatchIn(normalized)) add(ReasoningLevel.OFF)
            addAll(
                listOf(
                    ReasoningLevel.AUTO,
                    ReasoningLevel.LOW,
                    ReasoningLevel.MEDIUM,
                    ReasoningLevel.HIGH,
                    ReasoningLevel.XHIGH,
                    ReasoningLevel.MAX,
                )
            )
        }

        GPT_5_6_SERIES.containsMatchIn(normalized) -> listOf(
            ReasoningLevel.OFF,
            ReasoningLevel.AUTO,
            ReasoningLevel.LOW,
            ReasoningLevel.MEDIUM,
            ReasoningLevel.HIGH,
            ReasoningLevel.XHIGH,
            ReasoningLevel.MAX,
        )

        GPT_5_2_OR_LATER.containsMatchIn(normalized) -> listOf(
            ReasoningLevel.OFF,
            ReasoningLevel.AUTO,
            ReasoningLevel.LOW,
            ReasoningLevel.MEDIUM,
            ReasoningLevel.HIGH,
            ReasoningLevel.XHIGH,
        )

        GPT_5_1_SERIES.containsMatchIn(normalized) -> listOf(
            ReasoningLevel.OFF,
            ReasoningLevel.AUTO,
            ReasoningLevel.LOW,
            ReasoningLevel.MEDIUM,
            ReasoningLevel.HIGH,
        )

        GPT_5_SERIES.containsMatchIn(normalized) -> listOf(
            ReasoningLevel.AUTO,
            ReasoningLevel.MINIMAL,
            ReasoningLevel.LOW,
            ReasoningLevel.MEDIUM,
            ReasoningLevel.HIGH,
        )

        OPENAI_REASONING_MODEL.containsMatchIn(normalized) || normalized.startsWith("gpt-oss") -> listOf(
            ReasoningLevel.AUTO,
            ReasoningLevel.LOW,
            ReasoningLevel.MEDIUM,
            ReasoningLevel.HIGH,
        )

        else -> listOf(
            ReasoningLevel.AUTO,
            ReasoningLevel.LOW,
            ReasoningLevel.MEDIUM,
            ReasoningLevel.HIGH,
        )
    }
}

private fun String.isOpenAITextModel(): Boolean {
    if (OPENAI_NON_TEXT_MODEL_MARKERS.any(::contains)) return false
    return OPENAI_REASONING_MODEL.containsMatchIn(this) ||
        startsWith("gpt-3.5-turbo") ||
        startsWith("gpt-4") ||
        startsWith("gpt-5") ||
        startsWith("gpt-6")
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
private val GPT_5_1_SERIES = Regex("^gpt-5[.-]1(?:[.-]|$)")
private val GPT_5_6_SERIES = Regex("^gpt-5[.-]6(?:[.-]|$)")
private val GPT_6_SERIES = Regex("^gpt-6(?:[.-]|$)")
private val GPT_6_OPTIONAL_REASONING = Regex("^gpt-6(?:[.-](?:sol|luna)|[.-]1[.-]terra)(?:[.-]|$)")
private val GPT_6_CHAT_TOOLS_WITHOUT_REASONING = Regex("^gpt-6[.-](?:sol|luna)(?:[.-]|$)")
private val ULTRAFAST_MODELS = setOf("gpt-5.6-sol", "gpt-6-astra", "gpt-6.1-sol", "gpt-6-1-sol", "gpt-6.2-astra", "gpt-6-2-astra", "gpt-6.2-sol", "gpt-6-2-sol")
private val GPT_5_2_OR_LATER = Regex("^gpt-5[.-](?:[2-9]|\\d{2,})(?:[.-]|$)")
private val OPENAI_REASONING_MODEL = Regex("^o(?:1|3|4)(?:[-.]|$)")

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
