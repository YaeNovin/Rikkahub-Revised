package me.rerere.rikkahub.data.model

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ImageGenerationConstraints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageGenerationRequestStateTest {
    @Test fun `media version survives saved settings and both image paths while native models clear it`() {
        val provider = me.rerere.ai.provider.providers.openai.OpenAIProvider(okhttp3.OkHttpClient())
        val model = Model(modelId = "tt-image-2.6")
        val media = provider.imageGenerationConstraints(
            me.rerere.ai.provider.ProviderSetting.OpenAI(baseUrl = "https://api.lk888.ai/v1"), model)
        val state = ImageGenerationRequestState(outputFormat = "webp", outputCompression = 73,
            moderation = "low", gptImage26Options = me.rerere.ai.provider.GptImage26Options(version = "sunburst"))
            .constrained(media)
        val saved = kotlinx.serialization.json.Json.encodeToString(ImageGenerationRequestState.serializer(), state)
        val restored = kotlinx.serialization.json.Json.decodeFromString(ImageGenerationRequestState.serializer(), saved)
        assertEquals("sunburst", restored.toParams(model).gptImage26Options.version)
        assertEquals("sunburst", restored.toEditParams(model, listOf("fixture.png")).gptImage26Options.version)
        assertEquals(73, restored.outputCompression)
        assertEquals("low", restored.moderation)
        val native = provider.imageGenerationConstraints(me.rerere.ai.provider.ProviderSetting.OpenAI(), Model(modelId = "gpt-image-2.6-flare"))
        assertEquals(null, restored.constrained(native).gptImage26Options.version)
        val legacy = kotlinx.serialization.json.Json.decodeFromString(ImageGenerationRequestState.serializer(), "{}")
        assertEquals(null, legacy.gptImage26Options.version)
    }
    @Test
    fun `Image 2_6 options survive both generation and edit paths with format constraints`() {
        val model = Model(modelId = "gpt-image-2.6-flare")
        val limits = me.rerere.ai.provider.providers.openai.OpenAIProvider(okhttp3.OkHttpClient())
            .imageGenerationConstraints(me.rerere.ai.provider.ProviderSetting.OpenAI(), model)
        val options = me.rerere.ai.provider.GptImage26Options(complexity = "medium", frameCount = 9,
            frameDurationMs = 200, svgModel = "svg-author", svgReasoningEffort = "high")
        val state = ImageGenerationRequestState(prompt = "fixture", count = 8, size = "3840x2160",
            outputFormat = "svg", background = "transparent", gptImage26Options = options).constrained(limits)
        assertEquals(1, state.count)
        assertEquals("transparent", state.background)
        assertEquals(options, state.toParams(model).gptImage26Options)
        assertEquals(options, state.toEditParams(model, listOf("fixture.png")).gptImage26Options)
        val gif = state.copy(outputFormat = "gif").constrained(limits)
        assertTrue(gif.size in setOf("auto", "512x512"))
        assertEquals(null, gif.background)
    }

    @Test
    fun `request state converts to shared params and normalizes count`() {
        val params = ImageGenerationRequestState(
            prompt = "  a cat  ", size = "1024x1024", count = 0,
            quality = "high", outputFormat = "png", seed = 42,
        ).toParams(Model(modelId = "image"))
        assertEquals("a cat", params.prompt)
        assertEquals("1024x1024", params.size)
        assertEquals(1, params.numOfImages)
        assertEquals("high", params.quality)
        assertEquals(42L, params.seed)
    }

    @Test
    fun `prompt is request local and not part of model identity`() {
        val model = Model(modelId = "image")
        val first = ImageGenerationRequestState(prompt = "first").toParams(model)
        val second = ImageGenerationRequestState(prompt = "second").toParams(model)
        assertEquals(model.modelId, first.model.modelId)
        assertEquals(model.modelId, second.model.modelId)
        assertTrue(first.prompt != second.prompt)
    }

    @Test
    fun `constraints drop unsupported options and clamp output count`() {
        val constraints = ImageGenerationConstraints(
            supportsGeneration = true,
            supportsEdit = false,
            supportsPartialImages = false,
            maxOutputImages = 2,
            supportsOutputCount = true,
            supportedSizes = setOf("1:1"),
            supportsCustomSize = false,
            supportedQualityValues = setOf("high"),
            supportedOutputFormats = setOf("png"),
            seedRange = 1L..10L,
            supportsNegativePrompt = false,
        )
        val constrained = ImageGenerationRequestState(
            size = "16:9",
            count = 8,
            quality = "low",
            outputFormat = "jpg",
            seed = 99,
            negativePrompt = "avoid text",
        ).constrained(constraints)

        assertEquals("1:1", constrained.size)
        assertEquals(2, constrained.count)
        assertEquals(null, constrained.quality)
        assertEquals(null, constrained.outputFormat)
        assertEquals(null, constrained.seed)
        assertEquals("", constrained.negativePrompt)
    }
}
