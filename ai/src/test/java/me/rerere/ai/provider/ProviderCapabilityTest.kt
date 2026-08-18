package me.rerere.ai.provider

import me.rerere.ai.provider.providers.openai.OpenAIProvider
import me.rerere.ai.ui.StreamChunk
import okhttp3.OkHttpClient
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderCapabilityTest {
    @Test
    fun `only providers that declare balance support expose the capability`() {
        val unsupported = object : Provider<ProviderSetting.OpenAI> {
            override suspend fun listModels(providerSetting: ProviderSetting.OpenAI): List<Model> = emptyList()

            override suspend fun generateText(
                providerSetting: ProviderSetting.OpenAI,
                messages: List<me.rerere.ai.ui.UIMessage>,
                params: TextGenerationParams,
            ): TextGenerationResult = error("Not used by this test")

            override suspend fun streamText(
                providerSetting: ProviderSetting.OpenAI,
                messages: List<me.rerere.ai.ui.UIMessage>,
                params: TextGenerationParams,
            ) = kotlinx.coroutines.flow.emptyFlow<StreamChunk>()
        }

        assertFalse(unsupported.supports(ProviderCapability.BALANCE))
        assertTrue(OpenAIProvider(OkHttpClient()).supports(ProviderCapability.BALANCE))
    }
}
