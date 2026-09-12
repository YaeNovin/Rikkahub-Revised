package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.*
import me.rerere.ai.provider.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

class AdditionalVideoAPITest {
    @Test fun `compatible grok video model is selectable with normalized identifier`() {
        val provider = OpenAIProvider(OkHttpClient())
        val setting = ProviderSetting.OpenAI(baseUrl = "https://gateway.example/api/v1")
        val constraints = provider.videoGenerationConstraints(setting, model(" xai/GROK-IMAGINE-VIDEO-1.5 "))
        assertTrue(constraints.supportsGeneration)
        assertTrue("1080p" in constraints.supportedResolutions)
        assertFalse(provider.videoGenerationConstraints(setting, model("grok-imagine-image")).supportsGeneration)
        assertFalse(provider.videoGenerationConstraints(setting, model("grok-imagine-video").copy(type = ModelType.CHAT)).supportsGeneration)
    }

    @Test fun `compatible grok requests preserve configured gateway prefix and model alias`() = runBlocking {
        val requests = mutableListOf<okhttp3.Request>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests.add(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body((if (requests.size == 1) """{"request_id":"job"}""" else """{"status":"pending"}""").toResponseBody()).build()
        }.build()
        val provider = OpenAIProvider(client)
        val setting = ProviderSetting.OpenAI(baseUrl = "https://gateway.example/api/v1/", apiKey = "test")
        val selected = model("xai/grok-imagine-video")
        provider.createVideoGenerationTask(setting, VideoGenerationParams(selected, "scene"))
        provider.getVideoGenerationTask(setting, selected, "job")
        assertEquals("/api/v1/videos/generations", requests[0].url.encodedPath)
        assertEquals("/api/v1/videos/job", requests[1].url.encodedPath)
        val buffer = okio.Buffer()
        requests[0].body!!.writeTo(buffer)
        assertEquals("xai/grok-imagine-video", Json.parseToJsonElement(buffer.readUtf8()).jsonObject["model"]!!.jsonPrimitive.content)
    }
    private fun model(id: String) = Model(modelId = id, type = ModelType.VIDEO)
    private val sui = ProviderSetting.OpenAI(baseUrl = "https://sui-xiang.com/v1")

    @Test fun `sui body has exactly four fields and string seconds`() {
        val body = additionalVideoBody(VideoRoute.SUI_XIANG, VideoGenerationParams(
            model("as-sd2.0-fast"), "scene", durationSeconds = 5, resolution = "1080p", seed = 123,
            customBody = listOf(CustomBody("image", JsonPrimitive("unwanted")))))
        assertEquals(setOf("model", "prompt", "seconds", "aspect_ratio"), body.keys)
        assertTrue(body["seconds"]!!.jsonPrimitive.isString)
        assertEquals("15", body["seconds"]!!.jsonPrimitive.content)
    }

    @Test fun `routing is restricted to actual hosts and video models`() {
        assertEquals(VideoRoute.SUI_XIANG, sui.additionalVideoRoute(model("video-ds-2.0")))
        assertNull(sui.copy(baseUrl = "https://sui-xiang.com.example.org/v1").additionalVideoRoute(model("video-ds-2.0")))
        assertNull(sui.additionalVideoRoute(model("as-sd2.0-fast").copy(type = ModelType.CHAT)))
        assertEquals(VideoRoute.XAI, ProviderSetting.OpenAI(baseUrl = "https://api.x.ai/v1").additionalVideoRoute(model("grok-imagine-video-1.5")))
    }

    @Test fun `xai done and failure responses map to task state`() {
        val done = parseAdditionalVideoTask(VideoRoute.XAI, Json.parseToJsonElement("""{"status":"done","video":{"url":"https://vidgen.x.ai/video.mp4"}}""").jsonObject, sui, "request-1")
        assertEquals(VideoGenerationTaskStatus.SUCCEEDED, done.status)
        assertEquals("request-1", done.taskId)
        val failed = parseAdditionalVideoTask(VideoRoute.XAI, Json.parseToJsonElement("""{"status":"failed","error":{"code":"invalid_argument","message":"bad prompt"}}""").jsonObject, sui, "request-2")
        assertEquals("bad prompt", failed.error?.message)
    }

    @Test fun `kling keeps task endpoint kind across restart`() {
        val result = parseAdditionalVideoTask(VideoRoute.KLING, Json.parseToJsonElement("""{"code":0,"data":{"task_id":"42","task_status":"succeed","task_result":{"videos":[{"url":"https://example.com/result.mp4"}]}}}""").jsonObject, sui, kind = "image2video")
        assertEquals("image2video:42", result.taskId)
        assertEquals(1, result.outputs.size)
    }

    @Test fun `kling business errors are not accepted as queued tasks`() {
        assertThrows(IllegalArgumentException::class.java) {
            parseAdditionalVideoTask(VideoRoute.KLING, Json.parseToJsonElement("""{"code":1001,"message":"invalid token"}""").jsonObject, sui)
        }
    }

    @Test fun `kling JWT has bounded validity and valid signature shape`() {
        val token = klingBearer("access:secret", 1000)
        val pieces = token.split('.')
        assertEquals(3, pieces.size)
        val payload = Json.parseToJsonElement(String(Base64.getUrlDecoder().decode(pieces[1]))).jsonObject
        assertEquals("access", payload["iss"]!!.jsonPrimitive.content)
        assertEquals(2800L, payload["exp"]!!.jsonPrimitive.long)
        assertEquals(995L, payload["nbf"]!!.jsonPrimitive.long)
        assertEquals(32, Base64.getUrlDecoder().decode(pieces[2]).size)
    }

    @Test fun `xai generation uses image object and numeric duration`() {
        val body = additionalVideoBody(VideoRoute.XAI, VideoGenerationParams(model("grok-imagine-video-1.5"), "animate",
            inputMode = VideoGenerationInputMode.IMAGE_TO_VIDEO,
            referenceImages = listOf(VideoReferenceImage("https://example.com/first.png", VideoReferenceImageRole.FIRST_FRAME)), durationSeconds = 10))
        assertFalse(body["duration"]!!.jsonPrimitive.isString)
        assertEquals("https://example.com/first.png", body["image"]!!.jsonObject["url"]!!.jsonPrimitive.content)
    }

    @Test fun `sui content URL is derived from task id and never external response`() {
        val task = parseAdditionalVideoTask(VideoRoute.SUI_XIANG, Json.parseToJsonElement("""{"id":"task_1","status":"completed","url":"https://untrusted.example/video"}""").jsonObject, sui)
        assertEquals("https://sui-xiang.com/v1/videos/task_1/content", task.outputs.single().downloadUrl)
    }

    @Test fun `provider create and polling use distinct xai endpoints`() = runBlocking {
        val requests = mutableListOf<okhttp3.Request>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests.add(chain.request())
            val response = if (requests.size == 1) """{"request_id":"request-42"}"""
                else """{"status":"done","video":{"url":"https://vidgen.x.ai/result.mp4"}}"""
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(response.toResponseBody()).build()
        }.build()
        val provider = OpenAIProvider(client)
        val setting = ProviderSetting.OpenAI(baseUrl = "https://api.x.ai/v1", apiKey = "test-key")
        val selected = model("grok-imagine-video-1.5")
        val created = provider.createVideoGenerationTask(setting, VideoGenerationParams(selected, "scene"))
        val completed = provider.getVideoGenerationTask(setting, selected, created.taskId)
        assertEquals("/v1/videos/generations", requests[0].url.encodedPath)
        assertEquals("/v1/videos/request-42", requests[1].url.encodedPath)
        assertEquals("Bearer test-key", requests[1].header("Authorization"))
        assertEquals(VideoGenerationTaskStatus.SUCCEEDED, completed.status)
    }
}
