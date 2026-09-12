package me.rerere.rikkahub.data.model

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.VideoGenerationConstraints
import me.rerere.ai.provider.VideoGenerationInputMode
import me.rerere.ai.provider.VideoReferenceImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoGenerationRequestStateTest {
    @Test
    fun `default image reference survives request state normalization`() {
        val result = VideoGenerationRequestState(
            prompt = "animate",
            inputMode = VideoGenerationInputMode.IMAGE_TO_VIDEO,
            referenceImages = listOf(VideoReferenceImage("file:///frame.png")),
        ).toParams(Model(modelId = "doubao-seedance-1-5-pro"), VideoGenerationConstraints(
            supportsGeneration = true,
            supportedInputModes = setOf(VideoGenerationInputMode.IMAGE_TO_VIDEO),
            maxReferenceImages = 1,
            supportedReferenceImageRoles = setOf(me.rerere.ai.provider.VideoReferenceImageRole.FIRST_FRAME),
        ))
        assertEquals(1, result.referenceImages.size)
        assertEquals(me.rerere.ai.provider.VideoReferenceImageRole.FIRST_FRAME, result.referenceImages.single().role)
    }
    @Test
    fun `request state converts through provider constraints`() {
        val model = Model(modelId = "veo", customHeaders = emptyList(), customBodies = emptyList())
        val params = VideoGenerationRequestState(
            prompt = "  ocean  ",
            inputMode = VideoGenerationInputMode.IMAGE_TO_VIDEO,
            referenceImages = listOf(VideoReferenceImage("file:///frame.png")),
            durationSeconds = 30,
            resolution = "1080p",
            negativePrompt = "logo",
            outputCount = 3,
        ).toParams(
            model = model,
            constraints = VideoGenerationConstraints(
                supportsGeneration = true,
                supportedInputModes = setOf(VideoGenerationInputMode.TEXT_TO_VIDEO),
                customDurationRangeSeconds = 4..12,
                supportedResolutions = setOf("720p"),
                maxReferenceImages = 0,
            ),
        )

        assertEquals("ocean", params.prompt)
        assertEquals(VideoGenerationInputMode.TEXT_TO_VIDEO, params.inputMode)
        assertEquals(12, params.durationSeconds)
        assertEquals(emptyList<VideoReferenceImage>(), params.referenceImages)
        assertNull(params.resolution)
        assertNull(params.negativePrompt)
        assertEquals(1, params.outputCount)
    }
}
