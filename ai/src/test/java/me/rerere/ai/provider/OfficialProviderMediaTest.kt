package me.rerere.ai.provider

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.core.*
import me.rerere.ai.provider.providers.claude.ClaudeProvider
import me.rerere.ai.provider.providers.google.GoogleProvider
import me.rerere.ai.provider.providers.google.prepareGoogleRemoteImages
import me.rerere.ai.provider.providers.openai.ChatCompletionsAPI
import me.rerere.ai.provider.providers.openai.ResponseAPI
import me.rerere.ai.ui.*
import me.rerere.ai.util.KeyRoulette
import me.rerere.ai.util.encodeBase64
import me.rerere.ai.util.json
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class OfficialProviderMediaTest {
    private fun Request.jsonBody(): JsonObject = Buffer().let { body!!.writeTo(it); json.parseToJsonElement(it.readUtf8()).jsonObject }
    private fun reply(request: Request, body: String, mime: String = "application/json") = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body.toResponseBody(mime.toMediaType())).build()

    @Test fun `MiMo audio retains data URI and mandatory reasoning across a tool turn`() = runBlocking {
        var sent: JsonObject? = null
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            sent = chain.request().jsonBody()
            reply(chain.request(), """{"id":"c","model":"mimo-v2.6-pro","choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}""")
        }.build()
        val audio = "data:audio/wav;base64,YWJj"
        val messages = listOf(UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Audio(audio))),
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Reasoning("retained-thought"),
                UIMessagePart.Tool("call1", "clock", "{}", output = listOf(UIMessagePart.Text("done"))))))
        ChatCompletionsAPI(client, KeyRoulette.default()).generateText(
            ProviderSetting.OpenAI(baseUrl = "https://api.xiaomimimo.com/v1", includeHistoryReasoning = false), messages,
            TextGenerationParams(Model(modelId = "mimo-v2.6-pro").withDetectedCapabilities()))
        val history = sent!!["messages"]!!.jsonArray
        val block = history.first().jsonObject["content"]!!.jsonArray.single().jsonObject
        assertEquals("input_audio", block["type"]!!.jsonPrimitive.content)
        assertEquals(audio, block["input_audio"]!!.jsonObject["data"]!!.jsonPrimitive.content)
        assertFalse(block["input_audio"]!!.jsonObject.containsKey("format"))
        assertTrue(history.any { (it as? JsonObject)?.get("reasoning_content") == JsonPrimitive("retained-thought") })
    }

    @Test fun `MiniMax Messages includes video and URL images and suppresses incompatible Claude options`() = runBlocking {
        var sent: JsonObject? = null
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            sent = chain.request().jsonBody()
            reply(chain.request(), """{"id":"m","model":"MiniMax-M3.1-Flash","role":"assistant","content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn"}""")
        }.build()
        ClaudeProvider(client).generateText(ProviderSetting.Claude(baseUrl = "https://api.minimax.io/anthropic/v1"),
            listOf(UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Image("https://example.test/image.png"),
                UIMessagePart.Video("https://example.test/video.mp4"), UIMessagePart.Video("data:video/mp4;base64,YWJj")))),
            TextGenerationParams(Model(modelId = "MiniMax-M3.1-Flash").withDetectedCapabilities(), maxTokens = 1_000_000,
                reasoningLevel = ReasoningLevel.OFF, tools = listOf(Tool("clock", "time", execute = { emptyList() })),
                claudeOptions = ClaudeGenerationOptions(toolChoice = ClaudeToolChoice.NONE, stopSequences = listOf("stop"))))
        val body = sent!!
        assertEquals(524_288, body["max_tokens"]!!.jsonPrimitive.int)
        assertEquals("adaptive", body["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertFalse(body.containsKey("tools")); assertFalse(body.containsKey("tool_choice")); assertFalse(body.containsKey("stop_sequences"))
        val blocks = body["messages"]!!.jsonArray.single().jsonObject["content"]!!.jsonArray
        assertEquals(listOf("image", "video", "video"), blocks.map { it.jsonObject["type"]!!.jsonPrimitive.content })
        assertEquals("url", blocks[0].jsonObject["source"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("https://example.test/video.mp4", blocks[1].jsonObject["source"]!!.jsonObject["url"]!!.jsonPrimitive.content)
        assertEquals("YWJj", blocks[2].jsonObject["source"]!!.jsonObject["data"]!!.jsonPrimitive.content)
    }

    @Test fun `Google downloads an image once without provider credentials and sends raw base64`() = runBlocking {
        var downloads = 0
        var sent: JsonObject? = null
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            if (request.url.host == "images.example.test") {
                downloads++
                assertNull(request.header("x-goog-api-key")); assertNull(request.header("Authorization")); assertNull(request.header("X-Secret"))
                reply(request, "abc", "image/png")
            } else {
                sent = request.jsonBody()
                assertEquals("provider-key", request.header("x-goog-api-key"))
                reply(request, """{"candidates":[{"content":{"role":"model","parts":[{"text":"ok"}]},"finishReason":"STOP"}]}""")
            }
        }.build()
        val image = UIMessagePart.Image("https://images.example.test/image.png")
        GoogleProvider(client).generateText(ProviderSetting.Google(apiKey = "provider-key"),
            listOf(UIMessage(role = MessageRole.USER, parts = listOf(image, image))),
            TextGenerationParams(Model(modelId = "gemini-4-carbon").withDetectedCapabilities(), customHeaders = listOf(CustomHeader("X-Secret", "secret"))))
        assertEquals(1, downloads)
        val parts = sent!!["contents"]!!.jsonArray.single().jsonObject["parts"]!!.jsonArray
        assertEquals(2, parts.size)
        assertEquals("YWJj", parts[0].jsonObject["inlineData"]!!.jsonObject["data"]!!.jsonPrimitive.content)
    }

    @Test fun `Google rejects a failed remote image instead of silently omitting the attachment`() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain -> reply(chain.request(), "<html>error</html>", "text/html") }.build()
        try {
            prepareGoogleRemoteImages(client, listOf(UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Image("https://example.test/missing")))))
            fail("Expected invalid media error")
        } catch (expected: IllegalArgumentException) { assertTrue(expected.message!!.contains("Content-Type")) }
    }

    @Test fun `Responses accepts GLM object content missing total usage and refusal content`() = runBlocking {
        for ((content, expected) in listOf("""{"type":"output_text","text":"ok"}""" to "ok",
            """{"type":"refusal","refusal":"Cannot comply"}""" to "Cannot comply")) {
            val client = OkHttpClient.Builder().addInterceptor { chain -> reply(chain.request(),
                """{"id":"r","model":"glm-5.3","output":[{"type":"message","role":"assistant","content":$content}],"usage":{"input_tokens":2,"output_tokens":3}}""") }.build()
            val result = ResponseAPI(client).generateText(ProviderSetting.OpenAI(baseUrl = "https://open.bigmodel.cn/api/paas/v4", useResponseApi = true),
                listOf(UIMessage.user("test")), TextGenerationParams(Model(modelId = "glm-5.3").withDetectedCapabilities()))
            assertEquals(expected, result.message.toText()); assertEquals(5, result.usage!!.totalTokens)
        }
    }

    @Test fun `data URI strips its prefix only for inline binary protocols`() {
        val image = UIMessagePart.Image("data:image/png;base64,YWJj")
        assertEquals("YWJj", image.encodeBase64(false).getOrThrow().base64)
        assertEquals(image.url, image.encodeBase64(true).getOrThrow().base64)
    }
}
