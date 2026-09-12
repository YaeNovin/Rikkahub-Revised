package me.rerere.ai.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoGenerationTest {
    @Test
    fun `known video ids are classified without stealing chat or image models`() {
        assertEquals(ModelType.VIDEO, inferModelTypeFromId("veo-3.1-generate-preview"))
        assertEquals(ModelType.VIDEO, inferModelTypeFromId("vendor/seedance-1.5-pro"))
        assertEquals(ModelType.VIDEO, inferModelTypeFromId("wan2.2-i2v-plus"))
        assertEquals(ModelType.IMAGE, inferModelTypeFromId("gpt-image-2"))
        assertEquals(ModelType.CHAT, inferModelTypeFromId("gemini-3.8-flash"))
    }

    @Test
    fun `constraints clamp supported values and remove unsupported options`() {
        val model = Model(modelId = "video-generation")
        val constraints = VideoGenerationConstraints(
            supportsGeneration = true,
            supportedInputModes = setOf(VideoGenerationInputMode.IMAGE_TO_VIDEO),
            supportedDurationsSeconds = setOf(5, 10),
            supportedAspectRatios = setOf("16:9"),
            supportedResolutions = setOf("720p"),
            supportedFrameRates = setOf(24),
            maxReferenceImages = 1,
            supportedReferenceImageRoles = setOf(VideoReferenceImageRole.REFERENCE),
            supportsSeed = true,
            supportsNegativePrompt = false,
            supportsAudio = true,
            supportsCameraFixed = true,
            supportsReturnLastFrame = true,
            supportsOutputCount = true,
            maxOutputVideos = 2,
        )
        val params = VideoGenerationParams(
            model = model,
            prompt = "  city at night  ",
            inputMode = VideoGenerationInputMode.KEYFRAMES_TO_VIDEO,
            referenceImages = listOf(
                VideoReferenceImage("file:///first.png"),
                VideoReferenceImage("file:///second.png"),
            ),
            durationSeconds = 8,
            aspectRatio = "9:16",
            resolution = "1080p",
            frameRate = 30,
            seed = 7,
            negativePrompt = "text",
            generateAudio = true,
            cameraFixed = true,
            returnLastFrame = true,
            outputCount = 9,
        ).constrained(constraints)

        assertEquals("city at night", params.prompt)
        assertEquals(VideoGenerationInputMode.IMAGE_TO_VIDEO, params.inputMode)
        assertEquals(10, params.durationSeconds)
        assertEquals(1, params.referenceImages.size)
        assertNull(params.aspectRatio)
        assertNull(params.resolution)
        assertNull(params.frameRate)
        assertEquals(7L, params.seed)
        assertNull(params.negativePrompt)
        assertEquals(true, params.generateAudio)
        assertEquals(true, params.cameraFixed)
        assertEquals(true, params.returnLastFrame)
        assertEquals(2, params.outputCount)
    }

    @Test
    fun `task snapshots reject incomplete terminal results`() {
        assertThrows(IllegalArgumentException::class.java) {
            VideoGenerationTaskSnapshot(
                taskId = "task-1",
                status = VideoGenerationTaskStatus.SUCCEEDED,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            VideoGenerationTaskSnapshot(
                taskId = "task-2",
                status = VideoGenerationTaskStatus.FAILED,
            )
        }
        val running = VideoGenerationTaskSnapshot(
            taskId = "task-3",
            status = VideoGenerationTaskStatus.RUNNING,
            progressPercent = 42,
        )
        assertTrue(running.outputs.isEmpty())
        assertFalse(running.status == VideoGenerationTaskStatus.SUCCEEDED)
    }

    @Test
    fun `constraints and outputs reject invalid provider metadata`() {
        assertThrows(IllegalArgumentException::class.java) {
            VideoGenerationConstraints(
                supportsGeneration = true,
                maxOutputVideos = 0,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            VideoGenerationOutput(downloadUrl = " ")
        }
        assertThrows(IllegalArgumentException::class.java) {
            VideoGenerationTaskSnapshot(
                taskId = "task-4",
                status = VideoGenerationTaskStatus.RUNNING,
                retryAfterMillis = -1,
            )
        }
    }

    @Test
    fun `input mode removes mutually exclusive reference media`() {
        val constraints = VideoGenerationConstraints(
            supportsGeneration = true,
            supportedInputModes = setOf(VideoGenerationInputMode.TEXT_TO_VIDEO),
            maxReferenceImages = 2,
            supportsReferenceVideo = true,
        )
        val params = VideoGenerationParams(
            model = Model(modelId = "video-generation"),
            prompt = "clouds",
            inputMode = VideoGenerationInputMode.IMAGE_TO_VIDEO,
            referenceImages = listOf(VideoReferenceImage("file:///frame.png")),
            referenceVideoUrl = "file:///source.mp4",
        ).constrained(constraints)

        assertEquals(VideoGenerationInputMode.TEXT_TO_VIDEO, params.inputMode)
        assertTrue(params.referenceImages.isEmpty())
        assertNull(params.referenceVideoUrl)
    }
}
