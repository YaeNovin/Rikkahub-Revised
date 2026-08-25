package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.GrokGenerationOptions
import me.rerere.ai.provider.GrokParallelToolCalls
import me.rerere.ai.provider.GrokResponseFormat
import me.rerere.ai.provider.GrokServiceTier
import me.rerere.ai.provider.GrokToolChoice
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderRequestChannel
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.util.KeyRoulette
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GrokOptionsRequestTest {
    private lateinit var chatApi: ChatCompletionsAPI
    private lateinit var responseApi: ResponseAPI

    @Before
    fun setUp() {
        chatApi = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())
        responseApi = ResponseAPI(OkHttpClient())
    }

    @Test
    fun `recognizes Grok text aliases and excludes specialized media models`() {
        listOf("grok-3", "grok-4.6", "x-ai/grok-4.20-multi-agent", "xai:grok-4.5").forEach {
            assertTrue("model=$it", resolveGrokModelParameterSupport(it).available)
        }
        listOf("grok-imagine-image", "grok-2-image", "grok-imagine-video", "gpt-5.6").forEach {
            assertFalse("model=$it", resolveGrokModelParameterSupport(it).available)
        }
        assertEquals(
            ProviderRequestChannel.XAI_API,
            ProviderSetting.OpenAI(baseUrl = "https://api.x.ai/v1").requestChannel(),
        )
        assertTrue(resolveGrokModelParameterSupport("xai:grok-4.5").reasoningModel)
        assertFalse(resolveGrokModelParameterSupport("xai:grok-3").supportsPresencePenalty)
    }

    @Test
    fun `chat completions sends supported Grok controls and schema`() {
        val body = buildChatRequest(
            params = TextGenerationParams(
                model = Model(modelId = "grok-2-latest", abilities = listOf(ModelAbility.TOOL)),
                maxTokens = 8_192,
                tools = listOf(testTool()),
                grokOptions = fullOptions(),
            ),
        )

        assertEquals("priority", body["service_tier"]?.jsonPrimitive?.content)
        assertEquals(false, body["parallel_tool_calls"]?.jsonPrimitive?.boolean)
        assertEquals("required", body["tool_choice"]?.jsonPrimitive?.content)
        assertEquals(42L, body["seed"]?.jsonPrimitive?.long)
        assertEquals(2, body["stop"]?.jsonArray?.size)
        assertEquals(0.4f, body["frequency_penalty"]?.jsonPrimitive?.float)
        assertEquals(0.3f, body["presence_penalty"]?.jsonPrimitive?.float)
        assertEquals(8_192, body["max_completion_tokens"]?.jsonPrimitive?.content?.toInt())
        assertFalse(body.containsKey("max_tokens"))
        val responseFormat = body["response_format"]?.jsonObject
        assertEquals("json_schema", responseFormat?.get("type")?.jsonPrimitive?.content)
        assertEquals(
            "answer",
            responseFormat?.get("json_schema")?.jsonObject?.get("name")?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `reasoning Grok omits rejected chat sampling controls`() {
        val body = buildChatRequest(
            params = TextGenerationParams(
                model = Model(
                    modelId = "grok-4.6",
                    abilities = listOf(ModelAbility.REASONING),
                ),
                reasoningLevel = ReasoningLevel.MAX,
                grokOptions = fullOptions(),
            ),
        )

        assertEquals("xhigh", body["reasoning_effort"]?.jsonPrimitive?.content)
        assertFalse(body.containsKey("stop"))
        assertFalse(body.containsKey("frequency_penalty"))
        assertFalse(body.containsKey("presence_penalty"))
        assertEquals(42L, body["seed"]?.jsonPrimitive?.long)
    }

    @Test
    fun `Grok reasoning effort follows model-specific official limits`() {
        val grok45 = buildChatRequest(reasoningParams("grok-4.5", ReasoningLevel.MAX))
        val grok46 = buildChatRequest(reasoningParams("grok-4.6", ReasoningLevel.MAX))
        val grok43Off = buildChatRequest(reasoningParams("grok-4.3", ReasoningLevel.OFF))
        val grok41 = buildChatRequest(reasoningParams("grok-4-1-fast", ReasoningLevel.HIGH))

        assertEquals("high", grok45["reasoning_effort"]?.jsonPrimitive?.content)
        assertEquals("xhigh", grok46["reasoning_effort"]?.jsonPrimitive?.content)
        assertEquals("none", grok43Off["reasoning_effort"]?.jsonPrimitive?.content)
        assertFalse(grok41.containsKey("reasoning_effort"))
    }

    @Test
    fun `responses sends Grok-specific sampling tool and format controls`() {
        val body = responseApi.buildRequestBody(
            providerSetting = ProviderSetting.OpenAI(
                baseUrl = "https://api.x.ai/v1",
                useResponseApi = true,
            ),
            messages = listOf(UIMessage.user("hello")),
            params = TextGenerationParams(
                model = Model(
                    modelId = "grok-4.6",
                    abilities = listOf(ModelAbility.REASONING, ModelAbility.TOOL),
                ),
                tools = listOf(testTool()),
                reasoningLevel = ReasoningLevel.XHIGH,
                grokOptions = fullOptions(),
            ),
            stream = true,
        )

        assertEquals("priority", body["service_tier"]?.jsonPrimitive?.content)
        assertEquals(false, body["parallel_tool_calls"]?.jsonPrimitive?.boolean)
        assertEquals("required", body["tool_choice"]?.jsonPrimitive?.content)
        assertEquals(6, body["max_turns"]?.jsonPrimitive?.content?.toInt())
        assertEquals(0.1f, body["min_p"]?.jsonPrimitive?.float)
        assertEquals(40, body["top_k"]?.jsonPrimitive?.content?.toInt())
        assertEquals("xhigh", body["reasoning"]?.jsonObject?.get("effort")?.jsonPrimitive?.content)
        assertEquals(
            "json_schema",
            body["text"]?.jsonObject?.get("format")?.jsonObject?.get("type")?.jsonPrimitive?.content,
        )
        assertFalse(body.containsKey("seed"))
        assertFalse(body.containsKey("stop"))
        assertFalse(body.containsKey("frequency_penalty"))
    }

    @Test
    fun `non Grok models never receive stored Grok options`() {
        val body = buildChatRequest(
            params = TextGenerationParams(
                model = Model(modelId = "claude-compatible"),
                grokOptions = fullOptions(),
            ),
        )

        listOf(
            "service_tier",
            "parallel_tool_calls",
            "tool_choice",
            "seed",
            "stop",
            "frequency_penalty",
            "presence_penalty",
            "response_format",
        ).forEach { assertFalse("field=$it", body.containsKey(it)) }
    }

    private fun reasoningParams(modelId: String, level: ReasoningLevel) = TextGenerationParams(
        model = Model(modelId = modelId, abilities = listOf(ModelAbility.REASONING)),
        reasoningLevel = level,
    )

    private fun fullOptions() = GrokGenerationOptions(
        serviceTier = GrokServiceTier.PRIORITY,
        parallelToolCalls = GrokParallelToolCalls.DISABLED,
        toolChoice = GrokToolChoice.REQUIRED,
        seed = 42,
        stopSequences = listOf("END", "STOP"),
        responseFormat = GrokResponseFormat.JSON_SCHEMA,
        responseJsonSchema = """{"type":"object","properties":{"answer":{"type":"string"}}}""",
        responseSchemaName = "answer",
        presencePenalty = 0.3f,
        frequencyPenalty = 0.4f,
        minP = 0.1f,
        topK = 40,
        maxTurns = 6,
    )

    private fun testTool(): Tool = Tool(
        name = "lookup",
        description = "test",
        parameters = { InputSchema.Obj(properties = JsonObject(emptyMap())) },
        execute = { emptyList() },
    )

    private fun buildChatRequest(params: TextGenerationParams): JsonObject {
        val method = ChatCompletionsAPI::class.java.getDeclaredMethod(
            "buildChatCompletionRequest",
            List::class.java,
            TextGenerationParams::class.java,
            ProviderSetting.OpenAI::class.java,
            Boolean::class.javaPrimitiveType,
        )
        method.isAccessible = true
        return method.invoke(
            chatApi,
            listOf(UIMessage.user("hello")),
            params,
            ProviderSetting.OpenAI(baseUrl = "https://api.x.ai/v1"),
            false,
        ) as JsonObject
    }
}
