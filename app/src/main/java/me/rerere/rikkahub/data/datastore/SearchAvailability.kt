package me.rerere.rikkahub.data.datastore

import me.rerere.ai.provider.*
import me.rerere.ai.provider.providers.google.isGeminiModel
import me.rerere.ai.provider.providers.google.resolvedFor
import me.rerere.ai.provider.providers.google.supportsGoogleToolCombination
import me.rerere.rikkahub.data.model.Assistant

internal data class SearchAvailability(val external: Boolean, val native: Boolean, val urlContext: Boolean) {
    val enabled: Boolean get() = external || native
    fun prompt(): String = if (!enabled && !urlContext) "" else buildString {
        appendLine("Current request search availability (supersedes earlier assistant statements):")
        if (external) appendLine("External web search is enabled through the declared search_web function. Use that function for live information when needed; it does not require the model vendor's native search protocol.")
        if (native) appendLine("Provider-native web search is enabled in this API request. Use the supplied built-in search tool when live information is needed; it does not need to be enabled again.")
        if (urlContext) appendLine("Google URL context is enabled in this API request for public URLs provided in messages.")
        append("Use actual tool results for citations. If a tool call fails, report that failure accurately; never claim a search succeeded without results.")
    }
}

internal fun searchAvailability(assistant: Assistant, model: Model, provider: ProviderSetting?): SearchAvailability {
    val google = provider is ProviderSetting.Google && model.isGeminiModel()
    val options = assistant.geminiOptions.search.resolvedFor(model)
    val native = model.supportsNativeWebSearch(provider) &&
        (if (google) options.enabled == true else BuiltInTools.Search in model.tools)
    val external = !native && (assistant.enableWebSearch || (!model.supportsNativeWebSearch(provider) && BuiltInTools.Search in model.tools))
    return SearchAvailability(
        external = external,
        native = native,
        urlContext = google && options.urlContext == true && (!external || model.supportsGoogleToolCombination()),
    )
}

internal fun effectiveGoogleSearchOptions(assistant: Assistant, model: Model, provider: ProviderSetting?): GeminiSearchOptions {
    val availability = searchAvailability(assistant, model, provider)
    return assistant.geminiOptions.search.resolvedFor(model).copy(enabled = availability.native, urlContext = availability.urlContext)
}
