package me.rerere.rikkahub.data.model

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ImageGenerationConstraints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageGenerationRequestStateTest {
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
