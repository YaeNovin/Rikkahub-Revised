package me.rerere.rikkahub.data.datastore

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.tts.provider.TTSProviderSetting
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Test

class MiniMaxEndpointMigrationTest {
    @Test fun storedChatAndSpeechSettingsMigrateWithoutChangingIdentityOrOptions() {
        val model = Model(modelId = "MiniMax-M3")
        val openai = ProviderSetting.OpenAI(baseUrl = "https://api.minimaxi.com/v1", apiKey = "synthetic", models = listOf(model), chatCompletionsPath = "/chat/completions")
        val claude = ProviderSetting.Claude(baseUrl = "https://api.minimaxi.com/anthropic/v1", apiKey = "synthetic", models = listOf(model))
        val speech = TTSProviderSetting.MiniMax(baseUrl = "https://api.minimaxi.com/v1", apiKey = "synthetic", voiceId = "male-qn-qingse", speed = 1.3f)
        val original = Settings(providers = listOf(openai, claude), ttsProviders = listOf(speech))
        val restored = JsonInstant.decodeFromString<Settings>(JsonInstant.encodeToString(original)).withCurrentMiniMaxEndpoints()
        assertEquals(openai.copy(baseUrl = "https://api.minimax.cn/v1"), restored.providers[0])
        assertEquals(claude.copy(baseUrl = "https://api.minimax.cn/anthropic/v1"), restored.providers[1])
        assertEquals(speech.copy(baseUrl = "https://api.minimax.cn/v1"), restored.ttsProviders.single())
        assertEquals(restored, restored.withCurrentMiniMaxEndpoints())
    }

    @Test fun internationalProxyAndOtherProtocolsArePreserved() {
        val providers = listOf(ProviderSetting.OpenAI(baseUrl = "https://api.minimax.io/v1"),
            ProviderSetting.Claude(baseUrl = "https://proxy.test/anthropic/v1", name = "MiniMax"),
            ProviderSetting.Google(baseUrl = "https://api.minimaxi.com/v1"))
        val speech = listOf(TTSProviderSetting.MiniMax(baseUrl = "https://api.minimax.io/v1"),
            TTSProviderSetting.OpenAI(baseUrl = "https://api.minimaxi.com/v1"))
        val original = Settings(providers = providers, ttsProviders = speech)
        assertEquals(original, original.withCurrentMiniMaxEndpoints())
    }

    @Test fun newDefaultsUseCurrentChineseEndpoints() {
        assertEquals("https://api.minimax.cn/anthropic/v1", DEFAULT_PROVIDERS.filterIsInstance<ProviderSetting.Claude>().single { it.name == "MiniMax" }.baseUrl)
        assertEquals("https://api.minimax.cn/v1", TTSProviderSetting.MiniMax().baseUrl)
    }
}
