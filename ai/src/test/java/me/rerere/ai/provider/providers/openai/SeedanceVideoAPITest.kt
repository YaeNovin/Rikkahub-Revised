package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.VideoGenerationInputMode
import me.rerere.ai.provider.VideoGenerationParams
import me.rerere.ai.provider.VideoGenerationTaskStatus
import me.rerere.ai.provider.VideoReferenceImage
import me.rerere.ai.provider.VideoReferenceImageRole
import me.rerere.ai.util.json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SeedanceVideoAPITest {
    private val model = Model(
        modelId = "doubao-seedance-1-5-pro-251215",
        type = ModelType.VIDEO,
    )

    @Test
    fun `capability is limited to Volcengine Ark and video models`() {
        val ark = ProviderSetting.OpenAI(baseUrl = "https://ark.cn-beijing.volces.com/api/v3")
        val proxy = ProviderSetting.OpenAI(baseUrl = "https://example.com/v1")

        assertTrue(ark.supportsArkSeedance(model))
        assertFalse(proxy.supportsArkSeedance(model))
        assertFalse(ark.supportsArkSeedance(Model(modelId = "doubao-pro", type = ModelType.CHAT)))
    }

    @Test
    fun `text to video request uses documented Ark fields`() {
        val body = buildSeedanceVideoRequestBody(
            params = VideoGenerationParams(
                model = model,
                prompt = "  cinematic ocean  ",
                durationSeconds = 8,
                aspectRatio = "16:9",
                resolution = "720p",
                seed = 42,
                generateAudio = true,
                watermark = false,
                cameraFixed = true,
                returnLastFrame = true,
            ),
            constraints = seedanceVideoGenerationConstraints(model),
        )

        assertEquals(model.modelId, body["model"]?.jsonPrimitive?.content)
        assertEquals("cinematic ocean", body["content"]?.jsonArray?.first()?.jsonObject
            ?.get("text")?.jsonPrimitive?.content)
        assertEquals("16:9", body["ratio"]?.jsonPrimitive?.content)
        assertEquals("720p", body["resolution"]?.jsonPrimitive?.content)
        assertEquals("true", body["generate_audio"]?.jsonPrimitive?.content)
        assertEquals("true", body["camera_fixed"]?.jsonPrimitive?.content)
        assertEquals("true", body["return_last_frame"]?.jsonPrimitive?.content)
    }

    @Test
    fun `keyframes request preserves first and last frame roles`() {
        val body = buildSeedanceVideoRequestBody(
            params = VideoGenerationParams(
                model = model,
                prompt = "day to night",
                inputMode = VideoGenerationInputMode.KEYFRAMES_TO_VIDEO,
                referenceImages = listOf(
                    VideoReferenceImage("https://example.com/first.png", VideoReferenceImageRole.FIRST_FRAME),
                    VideoReferenceImage("https://example.com/last.png", VideoReferenceImageRole.LAST_FRAME),
                ),
            ),
            constraints = seedanceVideoGenerationConstraints(model),
        )
        val content = body["content"]!!.jsonArray

        assertEquals("first_frame", content[1].jsonObject["role"]?.jsonPrimitive?.content)
        assertEquals("last_frame", content[2].jsonObject["role"]?.jsonPrimitive?.content)
        assertEquals(
            "https://example.com/first.png",
            content[1].jsonObject["image_url"]?.jsonObject?.get("url")?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `ordinary image reference becomes the first frame for Seedance 1`() {
        val body = buildSeedanceVideoRequestBody(
            params = VideoGenerationParams(
                model = model,
                prompt = "animate this image",
                inputMode = VideoGenerationInputMode.IMAGE_TO_VIDEO,
                referenceImages = listOf(VideoReferenceImage("https://example.com/source.png")),
            ),
            constraints = seedanceVideoGenerationConstraints(model),
        )

        assertEquals(
            "first_frame",
            body["content"]!!.jsonArray[1].jsonObject["role"]?.jsonPrimitive?.content,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `keyframes mode rejects a missing last frame`() {
        buildSeedanceVideoRequestBody(
            params = VideoGenerationParams(
                model = model,
                prompt = "transition",
                inputMode = VideoGenerationInputMode.KEYFRAMES_TO_VIDEO,
                referenceImages = listOf(
                    VideoReferenceImage(
                        "https://example.com/first.png",
                        VideoReferenceImageRole.FIRST_FRAME,
                    ),
                ),
            ),
            constraints = seedanceVideoGenerationConstraints(model),
        )
    }

    @Test
    fun `custom body cannot replace reserved video fields`() {
        val body = buildSeedanceVideoRequestBody(
            params = VideoGenerationParams(
                model = model,
                prompt = "safe prompt",
                customBody = listOf(
                    CustomBody("model", json.parseToJsonElement("\"other-model\"")),
                    CustomBody("duration", json.parseToJsonElement("99")),
                    CustomBody("callback_url", json.parseToJsonElement("\"https://example.com/callback\"")),
                ),
            ),
            constraints = seedanceVideoGenerationConstraints(model),
        )

        assertEquals(model.modelId, body["model"]?.jsonPrimitive?.content)
        assertFalse(body.containsKey("duration"))
        assertEquals("https://example.com/callback", body["callback_url"]?.jsonPrimitive?.content)
    }

    @Test
    fun `task parser maps success failure and timestamps`() {
        val success = parseSeedanceVideoTask(
            json.parseToJsonElement(
                """{
                    "id":"task-1",
                    "status":"succeeded",
                    "created_at":1700000000,
                    "content":{
                      "video_url":"https://example.com/result.mp4",
                      "last_frame_url":"https://example.com/last.png",
                      "duration":5.5
                    }
                }""",
            ).jsonObject,
        )
        val failure = parseSeedanceVideoTask(
            json.parseToJsonElement(
                """{
                    "id":"task-2",
                    "status":"failed",
                    "error":{"code":"InternalServiceError","message":"try later"}
                }""",
            ).jsonObject,
        )

        assertEquals(VideoGenerationTaskStatus.SUCCEEDED, success.status)
        assertEquals(1, success.outputs.size)
        assertEquals(5_500L, success.outputs.first().durationMillis)
        assertEquals(1_700_000_000_000L, success.createdAtEpochMillis)
        assertEquals(VideoGenerationTaskStatus.FAILED, failure.status)
        assertEquals("InternalServiceError", failure.error?.code)
        assertNotNull(failure.error)
        assertTrue(failure.error!!.retryable)
    }

    @Test
    fun `successful response without video url becomes retryable failure`() {
        val snapshot = parseSeedanceVideoTask(
            JsonObject(
                mapOf(
                    "id" to json.parseToJsonElement("\"task-missing\""),
                    "status" to json.parseToJsonElement("\"succeeded\""),
                ),
            ),
        )

        assertEquals(VideoGenerationTaskStatus.FAILED, snapshot.status)
        assertEquals("missing_video_url", snapshot.error?.code)
        assertTrue(snapshot.error?.retryable == true)
    }
}
