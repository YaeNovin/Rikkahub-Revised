package me.rerere.ai.provider

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.providers.claude.ClaudeProvider
import me.rerere.ai.provider.providers.google.GoogleProvider
import me.rerere.ai.provider.providers.openai.ChatCompletionsAPI
import me.rerere.ai.provider.providers.openai.ResponseAPI
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.util.KeyRoulette
import me.rerere.ai.util.json
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class OfficialProviderHeadersTest {
    private val tool = Tool("clock", "No arguments", execute = { emptyList() })
    private val chatReply = """{"id":"c","model":"fixture","choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":"ok"}}],"usage":{"prompt_tokens":2,"completion_tokens":1,"total_tokens":3}}"""
    private val responsesReply = """{"id":"r","model":"fixture","status":"completed","output":[{"type":"message","role":"assistant","content":[{"type":"output_text","text":"ok"}]}]}"""
    private val messagesReply = """{"id":"m","model":"fixture","role":"assistant","stop_reason":"end_turn","content":[{"type":"text","text":"ok"}],"usage":{"input_tokens":2,"output_tokens":1}}"""
    private fun params(id: String) = TextGenerationParams(Model(modelId = id).withDetectedCapabilities(), tools = listOf(tool),
        customHeaders = listOf(CustomHeader("Authorization", "stale"), CustomHeader("Authorization", "other"),
            CustomHeader("x-api-key", "stale"), CustomHeader("x-goog-api-key", "stale"), CustomHeader("api-key", "stale"),
            CustomHeader("anthropic-version", "stale")))

    @Test fun `official OpenAI compatible chat routes serialize auth and zero argument schemas`() = runBlocking {
        val cases = listOf("https://api.deepseek.com/v1" to "deepseek-v4.1-flash", "https://api.minimax.io/v1" to "MiniMax-M3.1-Flash",
            "https://api.moonshot.ai/v1" to "kimi-k3", "https://api.xiaomimimo.com/v1" to "mimo-v2.6-pro",
            "https://open.bigmodel.cn/api/paas/v4" to "glm-5.3")
        for ((url, modelId) in cases) {
            var captured: Request? = null
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                captured = chain.request()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(chatReply.toResponseBody("application/json".toMediaType())).build()
            }.build()
            val result = ChatCompletionsAPI(client, KeyRoulette.default()).generateText(
                ProviderSetting.OpenAI(baseUrl = url, apiKey = "fixture-key"), listOf(UIMessage.user("test")), params(modelId))
            assertEquals("ok", result.message.toText())
            val request = captured!!
            val header = if (officialChatVendor(url) == OfficialChatVendor.MIMO) "api-key" else "Authorization"
            assertEquals("$modelId auth", 1, request.headers.values(header).size)
            assertEquals(if (header == "api-key") "fixture-key" else "Bearer fixture-key", request.header(header))
            if (header == "api-key") { assertNull(request.header("Authorization")); assertNull(request.header("x-api-key")) }
            assertTrue(request.url.encodedPath.endsWith("/chat/completions"))
            val buffer = Buffer(); request.body!!.writeTo(buffer)
            val body = json.parseToJsonElement(buffer.readUtf8()).jsonObject
            assertEquals("object", body["tools"]!!.jsonArray.single().jsonObject["function"]!!.jsonObject["parameters"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        }
    }

    @Test fun `OpenAI Responses retains regional endpoint and a single bearer header`() = runBlocking {
        var captured: Request? = null
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            captured = chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(responsesReply.toResponseBody("application/json".toMediaType())).build()
        }.build()
        ResponseAPI(client).generateText(ProviderSetting.OpenAI(baseUrl = "https://us.api.openai.com/v1", apiKey = "fixture-key"),
            listOf(UIMessage.user("test")), params("gpt-6.2-sol"))
        assertEquals("https://us.api.openai.com/v1/responses", captured!!.url.toString())
        assertEquals(listOf("Bearer fixture-key"), captured!!.headers.values("Authorization"))
    }

    @Test fun `Claude and MiMo Messages use distinct auth headers and preserve their protocols`() = runBlocking {
        for ((url, id) in listOf("https://api.anthropic.com/v1" to "claude-sonnet-4-6", "https://api.xiaomimimo.com/anthropic/v1" to "mimo-v2.6-pro")) {
            var captured: Request? = null
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                captured = chain.request()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(messagesReply.toResponseBody("application/json".toMediaType())).build()
            }.build()
            ClaudeProvider(client).generateText(ProviderSetting.Claude(baseUrl = url, apiKey = "fixture-key"),
                listOf(UIMessage.user("test")), params(id).copy(reasoningLevel = me.rerere.ai.core.ReasoningLevel.AUTO))
            val request = captured!!
            val key = if (url.contains("xiaomimimo")) "api-key" else "x-api-key"
            assertEquals(listOf("fixture-key"), request.headers.values(key))
            if (url.contains("xiaomimimo")) { assertNull(request.header("Authorization")); assertNull(request.header("x-api-key")) }
            assertEquals(listOf("2023-06-01"), request.headers.values("anthropic-version"))
            val buffer = Buffer(); request.body!!.writeTo(buffer)
            val body = json.parseToJsonElement(buffer.readUtf8()).jsonObject
            assertEquals("object", body["tools"]!!.jsonArray.single().jsonObject["input_schema"]!!.jsonObject["type"]!!.jsonPrimitive.content)
            if (url.contains("xiaomimimo")) assertEquals("enabled", body["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        }
    }

    @Test fun `Google uses one key and full JSON function parameter schema`() = runBlocking {
        var captured: Request? = null
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            captured = chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"candidates":[{"content":{"role":"model","parts":[{"text":"ok"}]},"finishReason":"STOP"}]}""".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val schema = json.parseToJsonElement("""{"type":"object","properties":{"level":{"type":"integer","enum":[1,2]},"enabled":{"type":"boolean","const":true},"note":{"type":["string","null"]}}}""").jsonObject
        val numericTool = Tool("configure", "configure", parameters = { me.rerere.ai.core.InputSchema.Obj(schema["properties"]!!.jsonObject) }, execute = { emptyList() })
        GoogleProvider(client).generateText(ProviderSetting.Google(apiKey = "fixture-key"), listOf(UIMessage.user("test")), params("gemini-4-carbon").copy(tools = listOf(numericTool)))
        assertEquals(listOf("fixture-key"), captured!!.headers.values("x-goog-api-key"))
        val buffer = Buffer(); captured!!.body!!.writeTo(buffer)
        val function = json.parseToJsonElement(buffer.readUtf8()).jsonObject["tools"]!!.jsonArray.single().jsonObject["functionDeclarations"]!!.jsonArray.single().jsonObject
        assertTrue(function.containsKey("parametersJsonSchema"))
        assertFalse(function.containsKey("parameters"))
        assertEquals(schema["properties"], function["parametersJsonSchema"]!!.jsonObject["properties"])
    }

    @Test fun `custom authentication is retained when the provider key is blank`() {
        val request = Request.Builder().url("https://api.xiaomimimo.com/v1/chat/completions")
            .header("Authorization", "Bearer oauth-token").openAIAuthentication("https://api.xiaomimimo.com/v1", "").build()
        assertEquals("Bearer oauth-token", request.header("Authorization"))
        assertNull(request.header("api-key"))
    }
}
