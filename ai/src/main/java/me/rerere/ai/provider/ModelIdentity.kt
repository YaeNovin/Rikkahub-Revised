package me.rerere.ai.provider

import me.rerere.ai.registry.ModelRegistry

/** Local detection only. Never use this value as the API request's model identifier. */
fun Model.matchingModelId(): String {
    val name = displayName.normalizedModelIdentity()
    return if (name.isNotEmpty() && (ModelRegistry.isKnownModel(name) ||
            inferModelParameterFamily(name) != null || inferModelTypeFromId(name) != ModelType.CHAT)) name
        else modelId.trim()
}

internal fun String.normalizedModelIdentity(): String = trim().lowercase()
    .replace(Regex("[\\s\\u2010-\\u2015]+"), "-")
    .substringAfterLast('/').substringAfterLast(':')
    .replace(Regex("^(?:openai|google|anthropic)[-]+(?=gpt|chatgpt|o[1345]|gemini|claude)"), "")
    .normalizeCompactVendorModelId()

/** Apply detection on creation, discovery or an explicit reset of the model defaults. */
fun Model.withDetectedCapabilities(): Model {
    val identity = matchingModelId()
    return copy(type = ModelRegistry.MODEL_TYPE.getData(identity),
        inputModalities = ModelRegistry.MODEL_INPUT_MODALITIES.getData(identity),
        outputModalities = ModelRegistry.MODEL_OUTPUT_MODALITIES.getData(identity),
        abilities = ModelRegistry.MODEL_ABILITIES.getData(identity),
        contextWindowTokens = inferContextWindowTokens(identity))
}

/** Rename updates automatic defaults while preserving manually edited capabilities and limits. */
fun Model.withDisplayName(name: String): Model {
    val defaults = withDetectedCapabilities()
    val renamed = copy(displayName = name)
    val next = renamed.withDetectedCapabilities()
    return renamed.copy(
        type = if (type == defaults.type) next.type else type,
        inputModalities = if (inputModalities == defaults.inputModalities) next.inputModalities else inputModalities,
        outputModalities = if (outputModalities == defaults.outputModalities) next.outputModalities else outputModalities,
        abilities = if (abilities == defaults.abilities) next.abilities else abilities,
        contextWindowTokens = if (contextWindowTokens == null || contextWindowTokens == defaults.contextWindowTokens)
            next.contextWindowTokens else contextWindowTokens,
    )
}

/** Upgrade old ID-derived defaults in memory; explicit overrides remain untouched. */
fun Model.withNameBasedCapabilities(): Model {
    if (type == ModelType.CHAT && isDecisionModelId(parameterModelId())) {
        return copy(type = ModelType.DECISION)
    }
    if (displayName.isBlank() || matchingModelId() == modelId.trim()) return this
    val idDefaults = copy(displayName = "").withDetectedCapabilities()
    val namedDefaults = withDetectedCapabilities()
    return copy(
        type = if (type == idDefaults.type) namedDefaults.type else type,
        inputModalities = if (inputModalities == idDefaults.inputModalities) namedDefaults.inputModalities else inputModalities,
        outputModalities = if (outputModalities == idDefaults.outputModalities) namedDefaults.outputModalities else outputModalities,
        abilities = if (abilities == idDefaults.abilities) namedDefaults.abilities else abilities,
        contextWindowTokens = if (contextWindowTokens == null || contextWindowTokens == idDefaults.contextWindowTokens)
            namedDefaults.contextWindowTokens else contextWindowTokens,
    )
}
