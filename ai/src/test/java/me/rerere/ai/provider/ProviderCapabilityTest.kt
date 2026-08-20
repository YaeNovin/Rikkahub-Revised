package me.rerere.ai.provider

import me.rerere.ai.provider.providers.openai.OpenAIProvider
import me.rerere.ai.ui.StreamChunk
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
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

    @Test
    fun `only OpenAI protocol exposes standalone image endpoints`() {
        val provider = OpenAIProvider(OkHttpClient())

        assertTrue(provider.supports(ProviderCapability.IMAGE_GENERATION))
        assertTrue(provider.supports(ProviderCapability.IMAGE_EDIT))
        assertFalse(provider.supports(ProviderCapability.PARTIAL_IMAGES))
    }

    @Test
    fun `xAI image constraints follow documented batch and reference limits`() {
        val provider = OpenAIProvider(OkHttpClient())
        val setting = ProviderSetting.OpenAI(baseUrl = "https://api.x.ai/v1")
        val constraints = provider.imageGenerationConstraints(
            providerSetting = setting,
            model = Model(modelId = "grok-imagine-image-2.0"),
        )

        assertTrue(constraints.supportsGeneration)
        assertTrue(constraints.supportsEdit)
        assertTrue(constraints.supportsSize)
        assertEquals(10, constraints.maxOutputImages)
        assertEquals(3, constraints.maxReferenceImages)
        assertTrue("16:9" in constraints.supportedSizes.orEmpty())
    }
}
