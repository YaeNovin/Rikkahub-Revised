package me.rerere.ai.provider

import me.rerere.ai.registry.ModelRegistry
import me.rerere.ai.provider.providers.google.isGeminiModel

/** Persisted and renamed compatible models may predate capability inference. */
fun Model.supportsFunctionCalling(): Boolean = type in setOf(ModelType.CHAT, ModelType.DECISION) &&
    (ModelAbility.TOOL in abilities || ModelAbility.TOOL in ModelRegistry.MODEL_ABILITIES.getData(parameterModelId()))

/** Tool availability follows the selected wire protocol, independently of a model's name. */
fun Model.supportsNativeWebSearch(provider: ProviderSetting?): Boolean = when (provider) {
    is ProviderSetting.Google -> isGeminiModel()
    is ProviderSetting.OpenAI -> provider.useResponseApi
    is ProviderSetting.Claude -> inferParameterFamily() == ModelParameterFamily.CLAUDE
    null -> false
}
