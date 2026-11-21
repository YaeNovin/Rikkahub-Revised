package me.rerere.rikkahub.data.datastore

import me.rerere.ai.provider.ProviderSetting
import me.rerere.common.http.MiniMaxEndpoints
import me.rerere.tts.provider.TTSProviderSetting

/** Applied on load and save, so restored/imported settings follow the current Chinese endpoint too. */
internal fun Settings.withCurrentMiniMaxEndpoints(): Settings = copy(
    providers = providers.map { provider ->
        when (provider) {
            is ProviderSetting.OpenAI -> provider.copy(baseUrl = MiniMaxEndpoints.migrateChineseBaseUrl(provider.baseUrl))
            is ProviderSetting.Claude -> provider.copy(baseUrl = MiniMaxEndpoints.migrateChineseBaseUrl(provider.baseUrl))
            is ProviderSetting.Google -> provider
        }
    },
    ttsProviders = ttsProviders.map { provider ->
        if (provider is TTSProviderSetting.MiniMax)
            provider.copy(baseUrl = MiniMaxEndpoints.migrateChineseBaseUrl(provider.baseUrl))
        else provider
    },
)
