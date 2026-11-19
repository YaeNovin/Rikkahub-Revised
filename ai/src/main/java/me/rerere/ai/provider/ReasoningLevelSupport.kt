package me.rerere.ai.provider

import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.providers.claude.resolveClaudeModelParameterSupport
import me.rerere.ai.provider.providers.openai.DEEPSEEK_API_HOST
import me.rerere.ai.provider.providers.openai.OPENAI_API_HOST
import me.rerere.ai.provider.providers.openai.XAI_API_HOST
import me.rerere.ai.provider.providers.openai.isAlibabaModelStudioHost
import me.rerere.ai.provider.providers.openai.resolveGrokModelParameterSupport
import me.rerere.ai.provider.providers.openai.resolveOpenAIReasoningLevels
import me.rerere.ai.provider.providers.openai.isOfficialOpenAIHost
import me.rerere.ai.provider.providers.google.isGeminiHydrogen
import me.rerere.ai.provider.providers.google.isGemini4Model
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class ReasoningLevelSupport(
    val levels: List<ReasoningLevel>,
    val modelSpecific: Boolean,
    val compatibleEndpoint: Boolean,
) {
    init {
        require(levels.isNotEmpty()) { "At least one reasoning level must be available" }
        require(levels.distinct().size == levels.size) { "Reasoning levels must be unique" }
    }

    fun coerce(level: ReasoningLevel): ReasoningLevel {
        if (level in levels) return level
        if (level == ReasoningLevel.OFF || level == ReasoningLevel.AUTO) {
            return ReasoningLevel.AUTO.takeIf(levels::contains) ?: levels.first()
        }
        val enabledLevels = levels.filter { it != ReasoningLevel.OFF && it != ReasoningLevel.AUTO }
        val next = enabledLevels.firstOrNull { it.ordinal >= level.ordinal }
        val previous = enabledLevels.lastOrNull { it.ordinal <= level.ordinal }
        return previous.takeIf {
            level == ReasoningLevel.XHIGH && it == ReasoningLevel.HIGH && next == ReasoningLevel.MAX
        } ?: next
            ?: enabledLevels.lastOrNull()
            ?: ReasoningLevel.AUTO.takeIf(levels::contains)
            ?: levels.first()
    }

    companion object {
        val ALL = ReasoningLevelSupport(
            levels = ReasoningLevel.entries,
            modelSpecific = false,
            compatibleEndpoint = false,
        )
    }
}

fun resolveReasoningLevelSupport(
    model: Model,
    provider: ProviderSetting?,
): ReasoningLevelSupport {
    if (provider is ProviderSetting.OpenAI && provider.useResponseApi &&
        officialChatVendor(provider.baseUrl) == OfficialChatVendor.GLM)
        return ReasoningLevelSupport(listOf(ReasoningLevel.OFF, ReasoningLevel.AUTO, ReasoningLevel.HIGH, ReasoningLevel.MAX),
            modelSpecific = true, compatibleEndpoint = false)
    if (model.isMiMo26()) return ReasoningLevelSupport(
        levels = listOf(ReasoningLevel.OFF, ReasoningLevel.AUTO), modelSpecific = true,
        compatibleEndpoint = provider is ProviderSetting.OpenAI || provider is ProviderSetting.Claude,
    )
    if (provider is ProviderSetting.OpenAI && !provider.useResponseApi && model.isGemini4Model())
        return resolveGoogleReasoningLevelSupport(model).copy(compatibleEndpoint = true)
    if (provider is ProviderSetting.OpenAI || provider is ProviderSetting.Claude) {
        model.modernVendorReasoningLevels()?.let { levels ->
            return ReasoningLevelSupport(
                levels = if (provider is ProviderSetting.Claude && model.modernVendorModel() != ModernVendorModel.MINIMAX_M31)
                    listOf(ReasoningLevel.AUTO) else levels,
                modelSpecific = true, compatibleEndpoint = true)
        }
    }
    if (!model.supportsReasoningCapability()) return ReasoningLevelSupport.ALL
    return when (provider) {
        is ProviderSetting.Google -> resolveGoogleReasoningLevelSupport(model)
        is ProviderSetting.Claude -> resolveClaudeReasoningLevelSupport(model)
        is ProviderSetting.OpenAI -> resolveOpenAIChannelReasoningLevelSupport(model, provider)
        null -> ReasoningLevelSupport.ALL
    }
}

private fun resolveGoogleReasoningLevelSupport(model: Model): ReasoningLevelSupport {
    val id = model.normalizedReasoningModelId()
    val levels = when {
        model.isGeminiHydrogen() -> listOf(ReasoningLevel.AUTO, ReasoningLevel.LOW,
            ReasoningLevel.MEDIUM, ReasoningLevel.HIGH, ReasoningLevel.XHIGH)
        id.startsWith("gemini-4-") || id.startsWith("gemini-3-7-flash") || id.startsWith("gemini-3-8-flash") -> listOf(
            ReasoningLevel.AUTO,
            ReasoningLevel.LOW,
            ReasoningLevel.MEDIUM,
            ReasoningLevel.HIGH,
        )

        id.startsWith("gemini-3-5-flash") || id.startsWith("gemini-3-flash") -> listOf(
            ReasoningLevel.AUTO,
            ReasoningLevel.MINIMAL,
            ReasoningLevel.LOW,
            ReasoningLevel.MEDIUM,
            ReasoningLevel.HIGH,
        )

        id.startsWith("gemini-3") && id.contains("pro") -> listOf(
            ReasoningLevel.AUTO,
            ReasoningLevel.LOW,
            ReasoningLevel.HIGH,
        )

        id.startsWith("gemini-3") -> listOf(
            ReasoningLevel.AUTO,
            ReasoningLevel.LOW,
            ReasoningLevel.MEDIUM,
            ReasoningLevel.HIGH,
        )

        id.startsWith("gemini-2-5-pro") -> listOf(
            ReasoningLevel.AUTO,
            ReasoningLevel.LOW,
            ReasoningLevel.MEDIUM,
            ReasoningLevel.HIGH,
            ReasoningLevel.XHIGH,
            ReasoningLevel.MAX,
        )

        id.startsWith("gemini-2-5-flash") -> listOf(
            ReasoningLevel.OFF,
            ReasoningLevel.AUTO,
            ReasoningLevel.LOW,
            ReasoningLevel.MEDIUM,
            ReasoningLevel.HIGH,
            ReasoningLevel.XHIGH,
            ReasoningLevel.MAX,
        )

        else -> listOf(ReasoningLevel.AUTO)
    }
    return ReasoningLevelSupport(levels, modelSpecific = true, compatibleEndpoint = false)
}

private fun resolveClaudeReasoningLevelSupport(model: Model): ReasoningLevelSupport {
    val support = resolveClaudeModelParameterSupport(model.parameterModelId())
    if (!support.supportsEffort) {
        return ReasoningLevelSupport(
            levels = if (support.supportsManualThinking) {
                listOf(
                    ReasoningLevel.OFF,
                    ReasoningLevel.AUTO,
                    ReasoningLevel.LOW,
                    ReasoningLevel.MEDIUM,
                    ReasoningLevel.HIGH,
                    ReasoningLevel.XHIGH,
                    ReasoningLevel.MAX,
                )
            } else {
                listOf(ReasoningLevel.OFF, ReasoningLevel.AUTO)
            },
            modelSpecific = support.available,
            compatibleEndpoint = false,
        )
    }
    val levels = buildList {
        add(ReasoningLevel.OFF)
        add(ReasoningLevel.AUTO)
        add(ReasoningLevel.LOW)
        add(ReasoningLevel.MEDIUM)
        add(ReasoningLevel.HIGH)
        if (support.supportsXHighEffort) add(ReasoningLevel.XHIGH)
        if (support.supportsMaxEffort) add(ReasoningLevel.MAX)
    }
    return ReasoningLevelSupport(levels, modelSpecific = true, compatibleEndpoint = false)
}

private fun resolveOpenAIChannelReasoningLevelSupport(
    model: Model,
    provider: ProviderSetting.OpenAI,
): ReasoningLevelSupport {
    val host = provider.baseUrl.toHttpUrlOrNull()?.host.orEmpty().lowercase()
    val normalized = model.normalizedReasoningModelId()
    val grok = resolveGrokModelParameterSupport(model.parameterModelId())
    if (grok.available) {
        val levels = buildList {
            if (grok.supportsDisableReasoning) add(ReasoningLevel.OFF)
            add(ReasoningLevel.AUTO)
            if (grok.supportsReasoningEffort) {
                add(ReasoningLevel.LOW)
                add(ReasoningLevel.MEDIUM)
                add(ReasoningLevel.HIGH)
                if (grok.maximumReasoningEffort.ordinal >= ReasoningLevel.XHIGH.ordinal) {
                    add(ReasoningLevel.XHIGH)
                }
            }
        }
        return ReasoningLevelSupport(levels, modelSpecific = true, compatibleEndpoint = host != XAI_API_HOST)
    }

    return when {
        isOfficialOpenAIHost(host) -> ReasoningLevelSupport(
            levels = resolveOpenAIReasoningLevels(model.parameterModelId()),
            modelSpecific = true,
            compatibleEndpoint = false,
        )

        host == "openrouter.ai" -> {
            val modelSupport = resolveCompatibleReasoningLevelSupport(model)
            val levels = if (model.inferParameterFamily() == null) {
                OPENROUTER_REASONING_LEVELS
            } else {
                modelSupport.levels.filter(OPENROUTER_REASONING_LEVELS::contains)
                    .ifEmpty { listOf(ReasoningLevel.AUTO) }
            }
            ReasoningLevelSupport(
                levels = levels,
                modelSpecific = modelSupport.modelSpecific,
                compatibleEndpoint = true,
            )
        }

        isAlibabaModelStudioHost(host) -> resolveAlibabaReasoningLevelSupport(normalized)
        host == DEEPSEEK_API_HOST -> resolveDeepSeekReasoningLevelSupport(normalized)
        host.isVolcengineArkHost() -> resolveVolcengineReasoningLevelSupport(normalized)
        host in TOGGLE_ONLY_HOSTS -> ReasoningLevelSupport(
            levels = listOf(ReasoningLevel.OFF, ReasoningLevel.AUTO),
            modelSpecific = true,
            compatibleEndpoint = false,
        )

        host == "api.mistral.ai" -> ReasoningLevelSupport(
            levels = listOf(ReasoningLevel.AUTO),
            modelSpecific = true,
            compatibleEndpoint = false,
        )

        else -> resolveCompatibleReasoningLevelSupport(model)
    }
}

private fun resolveAlibabaReasoningLevelSupport(normalized: String): ReasoningLevelSupport = when {
    me.rerere.ai.provider.providers.openai.isQwen4Max(normalized) -> ReasoningLevelSupport(
        levels = resolveQwenModelReasoningLevels(normalized),
        modelSpecific = true,
        compatibleEndpoint = false,
    )
    normalized.startsWith("qwen3-8") -> ReasoningLevelSupport(
        levels = listOf(
            ReasoningLevel.OFF,
            ReasoningLevel.AUTO,
            ReasoningLevel.LOW,
            ReasoningLevel.MEDIUM,
            ReasoningLevel.XHIGH,
        ),
        modelSpecific = true,
        compatibleEndpoint = false,
    )

    normalized.isDeepSeekThinkingOnlyModel() -> ReasoningLevelSupport(
        levels = listOf(ReasoningLevel.AUTO),
        modelSpecific = true,
        compatibleEndpoint = false,
    )

    (normalized.startsWith("deepseek-v4") || normalized == "deepseek-flash") -> ReasoningLevelSupport(
        levels = listOf(
            ReasoningLevel.OFF,
            ReasoningLevel.AUTO,
            ReasoningLevel.LOW,
            ReasoningLevel.HIGH,
            ReasoningLevel.MAX,
        ),
        modelSpecific = true,
        compatibleEndpoint = false,
    )

    normalized.isQwenThinkingOnlyModel() -> ReasoningLevelSupport(
        levels = listOf(
            ReasoningLevel.AUTO,
            ReasoningLevel.LOW,
            ReasoningLevel.MEDIUM,
            ReasoningLevel.HIGH,
            ReasoningLevel.XHIGH,
            ReasoningLevel.MAX,
        ),
        modelSpecific = true,
        compatibleEndpoint = false,
    )

    else -> ReasoningLevelSupport(
        levels = ReasoningLevel.entries,
        modelSpecific = true,
        compatibleEndpoint = false,
    )
}

private fun resolveDeepSeekReasoningLevelSupport(normalized: String): ReasoningLevelSupport = when {
    normalized.isDeepSeekThinkingOnlyModel() -> ReasoningLevelSupport(
        levels = listOf(ReasoningLevel.AUTO),
        modelSpecific = true,
        compatibleEndpoint = false,
    )

    (normalized.startsWith("deepseek-v4") || normalized == "deepseek-flash") -> ReasoningLevelSupport(
        levels = listOf(
            ReasoningLevel.OFF,
            ReasoningLevel.AUTO,
            ReasoningLevel.LOW,
            ReasoningLevel.HIGH,
            ReasoningLevel.MAX,
        ),
        modelSpecific = true,
        compatibleEndpoint = false,
    )

    else -> ReasoningLevelSupport(
        levels = listOf(ReasoningLevel.OFF, ReasoningLevel.AUTO),
        modelSpecific = true,
        compatibleEndpoint = false,
    )
}

private fun resolveVolcengineReasoningLevelSupport(normalized: String): ReasoningLevelSupport =
    ReasoningLevelSupport(
        levels = if (normalized.startsWith("doubao-seed-1-6") && normalized.contains("lite")) {
            listOf(
                ReasoningLevel.OFF,
                ReasoningLevel.AUTO,
                ReasoningLevel.LOW,
                ReasoningLevel.MEDIUM,
                ReasoningLevel.HIGH,
            )
        } else {
            listOf(ReasoningLevel.OFF, ReasoningLevel.AUTO)
        },
        modelSpecific = true,
        compatibleEndpoint = false,
    )

private fun resolveCompatibleReasoningLevelSupport(model: Model): ReasoningLevelSupport {
    val normalized = model.normalizedReasoningModelId()
    val levels = when (model.inferParameterFamily()) {
        ModelParameterFamily.OPENAI -> resolveOpenAIReasoningLevels(model.parameterModelId())
        ModelParameterFamily.GEMINI -> resolveGoogleReasoningLevelSupport(model).levels
        ModelParameterFamily.CLAUDE -> resolveClaudeReasoningLevelSupport(model).levels
        ModelParameterFamily.QWEN -> resolveQwenModelReasoningLevels(normalized)
        ModelParameterFamily.DEEPSEEK -> resolveDeepSeekModelReasoningLevels(normalized)
        ModelParameterFamily.GROK -> enabledEffortLevels(ReasoningLevel.HIGH)
        ModelParameterFamily.MINIMAX -> model.modernVendorReasoningLevels() ?: listOf(ReasoningLevel.AUTO, ReasoningLevel.OFF)
        null -> enabledEffortLevels(ReasoningLevel.HIGH)
    }
    return ReasoningLevelSupport(
        levels = levels,
        modelSpecific = model.inferParameterFamily() != null,
        compatibleEndpoint = true,
    )
}

private fun resolveQwenModelReasoningLevels(normalized: String): List<ReasoningLevel> = when {
    me.rerere.ai.provider.providers.openai.isQwen4Max(normalized) -> listOf(
        ReasoningLevel.OFF, ReasoningLevel.AUTO, ReasoningLevel.LOW,
        ReasoningLevel.HIGH, ReasoningLevel.XHIGH,
    )
    normalized.startsWith("qwen3-8") -> listOf(
        ReasoningLevel.OFF,
        ReasoningLevel.AUTO,
        ReasoningLevel.LOW,
        ReasoningLevel.MEDIUM,
        ReasoningLevel.XHIGH,
    )

    normalized.isQwenThinkingOnlyModel() -> listOf(
        ReasoningLevel.AUTO,
        ReasoningLevel.LOW,
        ReasoningLevel.MEDIUM,
        ReasoningLevel.HIGH,
        ReasoningLevel.XHIGH,
        ReasoningLevel.MAX,
    )

    normalized.startsWith("qwen3") || normalized.startsWith("qwq") || normalized.startsWith("qvq") -> listOf(
        ReasoningLevel.OFF,
        ReasoningLevel.AUTO,
        ReasoningLevel.LOW,
        ReasoningLevel.MEDIUM,
        ReasoningLevel.HIGH,
        ReasoningLevel.XHIGH,
        ReasoningLevel.MAX,
    )

    else -> listOf(ReasoningLevel.AUTO)
}

private fun resolveDeepSeekModelReasoningLevels(normalized: String): List<ReasoningLevel> = when {
    (normalized.startsWith("deepseek-v4") || normalized == "deepseek-flash") -> listOf(
        ReasoningLevel.OFF,
        ReasoningLevel.AUTO,
        ReasoningLevel.LOW,
        ReasoningLevel.HIGH,
        ReasoningLevel.MAX,
    )

    normalized.isDeepSeekThinkingOnlyModel() -> listOf(ReasoningLevel.AUTO)
    normalized.startsWith("deepseek") -> listOf(ReasoningLevel.OFF, ReasoningLevel.AUTO)
    else -> listOf(ReasoningLevel.AUTO)
}

private fun enabledEffortLevels(maximum: ReasoningLevel): List<ReasoningLevel> = buildList {
    add(ReasoningLevel.AUTO)
    add(ReasoningLevel.LOW)
    add(ReasoningLevel.MEDIUM)
    add(ReasoningLevel.HIGH)
    if (maximum.ordinal >= ReasoningLevel.XHIGH.ordinal) add(ReasoningLevel.XHIGH)
    if (maximum.ordinal >= ReasoningLevel.MAX.ordinal) add(ReasoningLevel.MAX)
}

private fun Model.normalizedReasoningModelId(): String =
    parameterModelId().substringAfterLast('/').substringAfterLast(':').trim().lowercase()
        .replace(Regex("[\\s._]+"), "-")
        .replace(Regex("^(gemini|claude|gpt|chatgpt|grok)(?=\\d)"), "$1-")
        .replace(Regex("^(qwq|qvq)(?=\\d)"), "$1-")
        .replace(Regex("^deepseek(?=[rv]?\\d)"), "deepseek-")
        .normalizeCompactVendorModelId()

private fun String.isQwenThinkingOnlyModel(): Boolean =
    startsWith("qwq-") ||
        startsWith("qvq-") ||
        (startsWith("qwen3-") && contains("-thinking")) ||
        this == "qwen3-7-max-preview" ||
        startsWith("qwen3-7-max-2026-05-17")

private fun String.isDeepSeekThinkingOnlyModel(): Boolean =
    startsWith("deepseek-r1") || this == "deepseek-reasoner"

private fun String.isVolcengineArkHost(): Boolean = startsWith("ark.") && endsWith(".volces.com")

private val TOGGLE_ONLY_HOSTS = setOf(
    "aiping.cn",
    "open.bigmodel.cn",
    "api.moonshot.cn",
    "chat.intern-ai.org.cn",
)

private val OPENROUTER_REASONING_LEVELS = listOf(
    ReasoningLevel.OFF,
    ReasoningLevel.AUTO,
    ReasoningLevel.MINIMAL,
    ReasoningLevel.LOW,
    ReasoningLevel.MEDIUM,
    ReasoningLevel.HIGH,
    ReasoningLevel.XHIGH,
)
