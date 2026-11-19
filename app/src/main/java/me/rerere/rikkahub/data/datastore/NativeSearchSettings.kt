package me.rerere.rikkahub.data.datastore

import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.providers.google.isGeminiModel
import me.rerere.ai.provider.providers.google.resolvedFor

fun Settings.isBuiltInSearchEnabled(model: Model?): Boolean {
    model ?: return false
    return searchAvailability(getCurrentAssistant(), model, model.findProvider(providers)).native
}

fun Settings.isExternalSearchEnabled(model: Model?): Boolean = model?.let {
    searchAvailability(getCurrentAssistant(), it, it.findProvider(providers)).external
} == true

fun Settings.withChatSearchMode(builtIn: Boolean, external: Boolean): Settings {
    val assistant = getCurrentAssistant()
    val model = getCurrentChatModel()
    val nativeGemini = model?.isGeminiModel() == true && model.findProvider(providers) is ProviderSetting.Google
    return copy(
        assistants = assistants.map { current ->
            if (current.id != assistant.id) current else current.copy(
                enableWebSearch = external,
                geminiOptions = if (nativeGemini) current.geminiOptions.copy(
                    search = current.geminiOptions.search.resolvedFor(requireNotNull(model)).copy(enabled = builtIn)
                ) else current.geminiOptions,
            )
        },
        providers = if (nativeGemini || model == null) providers else providers.map { provider ->
            provider.editModel(model.copy(tools = if (builtIn) model.tools + BuiltInTools.Search else model.tools - BuiltInTools.Search))
        },
    )
}
