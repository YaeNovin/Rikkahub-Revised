package me.rerere.ai.provider

import me.rerere.ai.registry.ModelRegistry
import me.rerere.ai.provider.providers.claude.resolveClaudeModelParameterSupport
import me.rerere.ai.provider.providers.openai.resolveGrokModelParameterSupport

enum class ModelParameterFamily {
    OPENAI,
    GEMINI,
    CLAUDE,
    QWEN,
    DEEPSEEK,
    GROK,
}

/**
 * Infers the model family independently from the wire protocol. Compatible providers often expose
 * vendor models through OpenAI Chat Completions, so both the model id and display name matter.
 */
fun Model.inferParameterFamily(): ModelParameterFamily? {
    val identifiers = listOf(modelId, displayName)
        .map { it.trim().lowercase() }
        .filter(String::isNotEmpty)
        .map(String::normalizeCompactVendorModelId)

    return when {
        identifiers.any(CLAUDE_FAMILY::containsMatchIn) -> ModelParameterFamily.CLAUDE
        identifiers.any(GEMINI_FAMILY::containsMatchIn) -> ModelParameterFamily.GEMINI
        identifiers.any(DEEPSEEK_FAMILY::containsMatchIn) -> ModelParameterFamily.DEEPSEEK
        identifiers.any(QWEN_FAMILY::containsMatchIn) -> ModelParameterFamily.QWEN
        identifiers.any(GROK_FAMILY::containsMatchIn) -> ModelParameterFamily.GROK
        identifiers.any(OPENAI_FAMILY::containsMatchIn) -> ModelParameterFamily.OPENAI
        else -> null
    }
}

/** Keeps the configured API model id intact while allowing opaque third-party ids to use a clear display name. */
fun Model.parameterModelId(): String {
    val modelIdFamily = Model(modelId = modelId).inferParameterFamily()
    val displayNameFamily = Model(displayName = displayName).inferParameterFamily()
    return when {
        modelIdFamily == null && displayNameFamily != null -> displayName
        modelIdFamily == displayNameFamily &&
            modelId.none(Char::isDigit) && displayName.any(Char::isDigit) -> displayName
        else -> modelId
    }
}

/** Re-evaluates known model ids so persisted third-party models do not need to be re-added. */
fun Model.supportsReasoningCapability(): Boolean {
    val capabilityId = parameterModelId()
    if (ModelAbility.REASONING in abilities) return true
    if (ModelAbility.REASONING in ModelRegistry.MODEL_ABILITIES.getData(capabilityId)) return true
    return when (inferParameterFamily()) {
        ModelParameterFamily.CLAUDE -> resolveClaudeModelParameterSupport(capabilityId).let {
            it.supportsAdaptiveThinking || it.supportsManualThinking
        }
        ModelParameterFamily.GROK -> resolveGrokModelParameterSupport(capabilityId).reasoningModel
        else -> false
    }
}

/** Resolves a usable parameter page, falling back to the provider's native wire protocol. */
fun Model.resolveParameterFamily(provider: ProviderSetting?): ModelParameterFamily? {
    val inferred = inferParameterFamily()
    if (inferred != null && provider.supportsParameterFamily(inferred)) return inferred

    return when (provider) {
        is ProviderSetting.OpenAI -> ModelParameterFamily.OPENAI
        is ProviderSetting.Google -> ModelParameterFamily.GEMINI
        is ProviderSetting.Claude -> ModelParameterFamily.CLAUDE
        null -> null
    }
}

fun ProviderSetting?.supportsParameterFamily(family: ModelParameterFamily): Boolean = when (family) {
    ModelParameterFamily.OPENAI,
    ModelParameterFamily.QWEN,
    ModelParameterFamily.GROK,
        -> this is ProviderSetting.OpenAI

    ModelParameterFamily.GEMINI -> this is ProviderSetting.Google || this is ProviderSetting.OpenAI
    ModelParameterFamily.CLAUDE -> this is ProviderSetting.Claude || this is ProviderSetting.OpenAI
    ModelParameterFamily.DEEPSEEK -> this is ProviderSetting.OpenAI || this is ProviderSetting.Claude
}

private val CLAUDE_FAMILY = Regex("(?:^|[^a-z0-9])(?:claude(?:[-._]?\\d)?|anthropic)(?:[^a-z0-9]|$)")
private val GEMINI_FAMILY = Regex("(?:^|[^a-z0-9])gemini(?:[-._]?\\d)?(?:[^a-z0-9]|$)")
private val DEEPSEEK_FAMILY = Regex("(?:^|[^a-z0-9])deepseek(?:[-._]?[rv]?\\d)?(?:[^a-z0-9]|$)")
private val QWEN_FAMILY = Regex("(?:^|[^a-z0-9])(?:qwen(?:[-._]?\\d)?|qwq|qvq)(?:[^a-z0-9]|$)")
private val GROK_FAMILY = Regex("(?:^|[^a-z0-9])grok(?:[-._]?\\d)?(?:[^a-z0-9]|$)")
private val OPENAI_FAMILY = Regex("(?:^|[^a-z0-9])(?:gpt(?:[-._]?\\d)?|chatgpt|o[1345](?:[-._]?\\d)?)(?:[^a-z0-9]|$)")

/** Normalizes compact aliases frequently returned by compatible model catalogs. */
internal fun String.normalizeCompactVendorModelId(): String =
    lowercase()
        .replace(
            Regex("^claude-?(\\d)(\\d+)-(opus|sonnet|haiku|fable|mythos)"),
            "claude-$3-$1-$2",
        )
        .replace(Regex("^(gpt|chatgpt|gemini|grok)-?(\\d)(\\d+)"), "$1-$2-$3")
        .replace(Regex("^qwen-?(\\d)(\\d+)"), "qwen$1-$2")
        .replace(Regex("^deepseek-?([rv])(\\d+)"), "deepseek-$1$2")
