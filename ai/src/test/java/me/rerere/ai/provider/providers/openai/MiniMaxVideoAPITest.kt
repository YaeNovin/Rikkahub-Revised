package me.rerere.ai.provider.providers.openai

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.provider.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class MiniMaxVideoAPITest {
    private val model = Model(modelId = "MiniMax-Hailuo-2.3", type = ModelType.VIDEO)
    @Test fun `anthropic setting uses native video endpoint without changing chat config`() {
        val setting = ProviderSetting.Claude(baseUrl = "https://api.minimaxi.com/anthropic/v1", apiKey = "test")
        assertEquals("https://api.minimaxi.com/v1", setting.miniMaxVideoSetting(model)?.baseUrl)
        assertEquals("https://api.minimaxi.com/anthropic/v1", setting.baseUrl)
        assertNull(setting.copy(baseUrl = "https://example.com/v1").miniMaxVideoSetting(model))
    }
    @Test fun `fast model is image only and legacy duration is fixed`() {
        assertEquals(setOf(VideoGenerationInputMode.IMAGE_TO_VIDEO), miniMaxVideoConstraints(model.copy(modelId = "MiniMax-Hailuo-2.3-Fast")).supportedInputModes)
        assertEquals(setOf(6), miniMaxVideoConstraints(model.copy(modelId = "T2V-01")).supportedDurationsSeconds)
        assertEquals(ModelType.VIDEO, inferModelTypeFromId("MiniMax-Hailuo-02"))
    }
    @Test fun `invalid high resolution duration is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { miniMaxVideoBody(VideoGenerationParams(model, "scene", durationSeconds = 10, resolution = "1080P")) }
    }
    @Test fun `optimizer off omits fast pretreatment and unrelated fields`() {
        val body = miniMaxVideoBody(VideoGenerationParams(model, "scene", promptEnhancement = false, fastPretreatment = true, seed = 2))
        assertFalse(body.containsKey("fast_pretreatment"))
        assertFalse(body.containsKey("seed"))
        assertEquals(false, body["prompt_optimizer"]!!.jsonPrimitive.boolean)
    }
    @Test fun `success retrieves file id before returning downloadable video`() = runBlocking {
        val requests = mutableListOf<Request>()
        val replies = listOf("""{"task_id":"123","base_resp":{"status_code":0}}""",
            """{"status":"Success","file_id":"456","base_resp":{"status_code":0}}""",
            """{"file":{"download_url":"https://cdn.example/video.mp4"},"base_resp":{"status_code":0}}""")
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val body = replies[requests.size]; requests.add(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body.toResponseBody()).build()
        }.build()
        val api = MiniMaxVideoAPI(client)
        val setting = ProviderSetting.OpenAI(baseUrl = "https://api.minimax.io/v1", apiKey = "test")
        val task = api.create(setting, VideoGenerationParams(model, "scene"))
        assertEquals(VideoGenerationTaskStatus.SUCCEEDED, api.get(setting, model, task.taskId).status)
        assertEquals("/v1/video_generation", requests[0].url.encodedPath)
        assertEquals("123", requests[1].url.queryParameter("task_id"))
        assertEquals("456", requests[2].url.queryParameter("file_id"))
    }
}
