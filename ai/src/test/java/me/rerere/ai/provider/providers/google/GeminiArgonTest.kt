package me.rerere.ai.provider.providers.google

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.*
import me.rerere.ai.registry.ModelRegistry
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.StreamChunk
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType.Companion.toMediaType
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class GeminiArgonTest {
    @Test fun `Argon has vision tools shared context and only documented thinking levels`() {
        val model = Model(modelId = "gemini-4-argon")
        assertTrue(ModelRegistry.MODEL_ABILITIES.getData(model.modelId).containsAll(listOf(ModelAbility.TOOL, ModelAbility.REASONING)))
        assertEquals(1_048_576, inferContextWindowTokens(model.modelId))
        val support = resolveReasoningLevelSupport(model, ProviderSetting.Google())
        assertEquals(listOf(ReasoningLevel.AUTO, ReasoningLevel.LOW, ReasoningLevel.MEDIUM, ReasoningLevel.HIGH), support.levels)
        assertEquals(ReasoningLevel.HIGH, support.coerce(ReasoningLevel.MAX))
        assertTrue(isGeminiArgon("google/gemini-4-argon"))
    }

    @Test fun `custom sampling cache and token budgets cannot override Argon contract`() {
        val body = normalizeArgonRequest(buildJsonObject {
            put("cachedContent", "old-cache")
            put("generationConfig", buildJsonObject {
                put("temperature", .7); put("topP", .9); put("topK", 20); put("candidateCount", 3)
                put("maxOutputTokens", 262144)
                put("thinkingConfig", buildJsonObject { put("thinkingBudget", 8000); put("thinkingLevel", "medium") })
                put("responseMimeType", "application/json")
            })
        })
        assertFalse(body.containsKey("cachedContent"))
        val config = body.getValue("generationConfig").jsonObject
        for (key in listOf("temperature", "topP", "topK", "candidateCount")) assertFalse(config.containsKey(key))
        assertEquals(262144, config.getValue("maxOutputTokens").jsonPrimitive.int)
        assertEquals("medium", config["thinkingConfig"]!!.jsonObject["thinkingLevel"]!!.jsonPrimitive.content)
        assertFalse(config["thinkingConfig"]!!.jsonObject.containsKey("thinkingBudget"))
    }

    @Test fun `continuation sends original request and joins the model parts without increasing budget`(): Unit = runBlocking {
        val requests = mutableListOf<JsonObject>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val buffer = Buffer(); request.body!!.writeTo(buffer)
            requests += Json.parseToJsonElement(buffer.readUtf8()).jsonObject
            val payload = if (requests.size == 1)
                """{"candidates":[{"content":{"role":"model","parts":[{"text":""}]},"finishReason":"CONTINUATION","continuationToken":"checkpoint-one"}],"usageMetadata":{"promptTokenCount":2,"thoughtsTokenCount":4,"totalTokenCount":6}}"""
            else """{"candidates":[{"content":{"role":"model","parts":[{"text":"Final answer"}]},"finishReason":"MAX_TOKENS","continuationToken":"budget-stop"}],"usageMetadata":{"promptTokenCount":3,"candidatesTokenCount":5,"totalTokenCount":8}}"""
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(payload.toResponseBody()).build()
        }.build()
        val result = GoogleProvider(client).generateText(
            ProviderSetting.Google(apiKey = "fixture-key"), listOf(UIMessage.user("Original prompt")),
            TextGenerationParams(Model(modelId = "gemini-4-argon"), maxTokens = 262144, reasoningLevel = ReasoningLevel.HIGH),
        )
        assertEquals(2, requests.size)
        assertEquals(requests[0]["contents"], requests[1]["contents"])
        assertEquals(requests[0]["generationConfig"], requests[1]["generationConfig"])
        assertEquals("checkpoint-one", requests[1]["continuationToken"]!!.jsonPrimitive.content)
        assertEquals("Final answer", result.message.toText())
        assertEquals("MAX_TOKENS", result.finishReason)
        assertEquals(14, result.usage!!.totalTokens)
    }

    @Test fun `repeated and missing checkpoints fail while budget exhaustion never automatically resumes`() {
        val seen = mutableSetOf<String>()
        val candidate = Json.parseToJsonElement("""{"finishReason":"CONTINUATION","continuationToken":"same"}""").jsonObject
        assertEquals("same", nextArgonContinuation(candidate, seen))
        assertTrue(runCatching { nextArgonContinuation(candidate, seen) }.isFailure)
        assertTrue(runCatching { nextArgonContinuation(buildJsonObject { put("finishReason", "CONTINUATION") }, seen) }.isFailure)
        assertNull(nextArgonContinuation(JsonObject(candidate + ("finishReason" to JsonPrimitive("MAX_TOKENS"))), seen))
    }

    @Test fun `compatible Argon requests cannot reintroduce retired sampling and penalties`() {
        val api = me.rerere.ai.provider.providers.openai.ChatCompletionsAPI(OkHttpClient(), me.rerere.ai.util.KeyRoulette.default())
        val method = api.javaClass.getDeclaredMethod("buildChatCompletionRequest", List::class.java,
            TextGenerationParams::class.java, ProviderSetting.OpenAI::class.java, Boolean::class.javaPrimitiveType).apply { isAccessible = true }
        val body = method.invoke(api, listOf(UIMessage.user("fixture")), TextGenerationParams(
            Model(modelId = "google/gemini-4-argon", abilities = listOf(ModelAbility.REASONING)),
            temperature = 1f, topP = .9f, reasoningLevel = ReasoningLevel.MAX,
            geminiOptions = GeminiGenerationOptions(presencePenalty = 1f, frequencyPenalty = 1f),
            customBody = listOf("temperature", "top_p", "top_k", "n", "presence_penalty", "frequency_penalty")
                .map { CustomBody(it, JsonPrimitive(1)) },
        ), ProviderSetting.OpenAI(baseUrl = "https://relay.example/v1"), false) as JsonObject
        for (key in listOf("temperature", "top_p", "top_k", "n", "presence_penalty", "frequency_penalty")) assertFalse(key, body.containsKey(key))
        assertEquals("high", body["reasoning_effort"]!!.jsonPrimitive.content)
    }

    @Test fun `stream continuation accumulates usage and emits a single terminal finish`(): Unit = runBlocking {
        val requests = mutableListOf<JsonObject>()
        val provider = streamingFixture(requests)
        val chunks = withTimeout(10_000) {
            provider.streamText(ProviderSetting.Google(apiKey = "fixture-key"), listOf(UIMessage.user("Original")),
                TextGenerationParams(Model(modelId = "gemini-4-argon"), maxTokens = 262144)).toList()
        }
        assertEquals(2, requests.size)
        assertEquals(requests[0]["contents"], requests[1]["contents"])
        assertEquals("checkpoint-stream", requests[1]["continuationToken"]!!.jsonPrimitive.content)
        assertEquals("part-onepart-two", chunks.filterIsInstance<StreamChunk.TextDelta>().joinToString("") { it.text })
        assertEquals(listOf("STOP"), chunks.filterIsInstance<StreamChunk.Finish>().map { it.finishReason })
        assertEquals(12, chunks.filterIsInstance<StreamChunk.Usage>().last().usage.totalTokens)
    }

    @Test fun `cancelling streaming output cannot start another paid continuation`(): Unit = runBlocking {
        val requests = mutableListOf<JsonObject>()
        withTimeout(10_000) {
            streamingFixture(requests).streamText(ProviderSetting.Google(apiKey = "fixture-key"),
                listOf(UIMessage.user("Original")), TextGenerationParams(Model(modelId = "gemini-4-argon")))
                .filterIsInstance<StreamChunk.TextDelta>().take(1).toList()
        }
        assertEquals(1, requests.size)
    }

    private fun streamingFixture(requests: MutableList<JsonObject>): GoogleProvider = GoogleProvider(
        OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val buffer = Buffer(); request.body!!.writeTo(buffer)
            requests += Json.parseToJsonElement(buffer.readUtf8()).jsonObject
            val first = requests.size == 1
            val candidate = buildJsonObject {
                put("content", buildJsonObject {
                    put("role", "model")
                    put("parts", buildJsonArray { add(buildJsonObject { put("text", if (first) "part-one" else "part-two") }) })
                })
                put("finishReason", if (first) "CONTINUATION" else "STOP")
                if (first) put("continuationToken", "checkpoint-stream")
            }
            val payload = buildJsonObject {
                put("candidates", buildJsonArray { add(candidate) })
                put("usageMetadata", buildJsonObject { put("promptTokenCount", 2); put("candidatesTokenCount", 4); put("totalTokenCount", 6) })
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .header("Content-Type", "text/event-stream")
                .body("data: $payload\n\ndata: [DONE]\n\n".toResponseBody("text/event-stream".toMediaType())).build()
        }.build(),
    )
}
