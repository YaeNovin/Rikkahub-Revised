package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.*
import me.rerere.ai.core.*
import me.rerere.ai.provider.*
import me.rerere.ai.ui.*
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class NewOfficialModelRequestTest {
    private val openAI = ProviderSetting.OpenAI()
    private val qwen = ProviderSetting.OpenAI(baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1")
    private val tool = Tool("lookup", "test", parameters = { InputSchema.Obj(properties = JsonObject(emptyMap())) }, execute = { emptyList() })
    private fun model(id: String) = Model(modelId = id).withDetectedCapabilities()
    private fun chat(params: TextGenerationParams, setting: ProviderSetting.OpenAI = qwen,
        messages: List<UIMessage> = listOf(UIMessage.user("hello"))): JsonObject {
        val method = ChatCompletionsAPI::class.java.getDeclaredMethod("buildChatCompletionRequest", List::class.java,
            TextGenerationParams::class.java, ProviderSetting.OpenAI::class.java, Boolean::class.javaPrimitiveType)
        method.isAccessible = true
        return method.invoke(ChatCompletionsAPI(OkHttpClient(), me.rerere.ai.util.KeyRoulette.default()), messages, params, setting, true) as JsonObject
    }

    @Test fun `GPT62 uses Responses developer prefix valid reasoning tiers and output limit`() {
        for (id in listOf("gpt-6.2-astra", "gpt-6.2-sol")) {
            val params = TextGenerationParams(model = model(id), tools = listOf(tool), temperature = .2f, topP = .8f,
                reasoningLevel = ReasoningLevel.MAX, maxTokens = 900_000,
                openAIOptions = OpenAIGenerationOptions(serviceTier = OpenAIServiceTier.ULTRAFAST,
                    reasoningMode = OpenAIReasoningMode.PRO, reasoningContext = OpenAIReasoningContext.ALL_TURNS,
                    promptCacheTtl = OpenAIPromptCacheTtl.TWELVE_HOURS),
                customBody = listOf(CustomBody("temperature", JsonPrimitive(.6)), CustomBody("logprobs", JsonPrimitive(true))))
            assertTrue(openAI.shouldUseResponsesApi(params))
            assertFalse(ReasoningLevel.OFF in resolveOpenAIReasoningLevels(id))
            assertFalse(ReasoningLevel.MINIMAL in resolveOpenAIReasoningLevels(id))
            val body = ResponseAPI(OkHttpClient()).buildRequestBody(openAI,
                listOf(UIMessage.system("stable"), UIMessage.user("question")), params, true)
            assertFalse(body.containsKey("temperature"))
            assertFalse(body.containsKey("top_p"))
            assertFalse(body.containsKey("logprobs"))
            assertFalse(body.containsKey("instructions"))
            assertEquals("developer", body["input"]!!.jsonArray.first().jsonObject["role"]!!.jsonPrimitive.content)
            assertEquals(128_000, body["max_output_tokens"]!!.jsonPrimitive.int)
            assertEquals("max", body["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
            assertEquals("pro", body["reasoning"]!!.jsonObject["mode"]!!.jsonPrimitive.content)
            assertEquals("ultrafast", body["service_tier"]!!.jsonPrimitive.content)
            assertEquals("12h", body["prompt_cache_options"]!!.jsonObject["ttl"]!!.jsonPrimitive.content)
        }
    }

    @Test fun `Qwen4 supports schema streaming search vision and correct combined thinking cap`() {
        val params = TextGenerationParams(model = model("qwen4-max"), tools = listOf(tool), maxTokens = 900_000,
            reasoningLevel = ReasoningLevel.XHIGH, qwenOptions = QwenGenerationOptions(
                responseFormat = QwenResponseFormat.JSON_SCHEMA, responseJsonSchema = """{"type":"object"}""",
                frequencyPenalty = .3f, preserveThinking = QwenOptionalToggle.ENABLED,
                toolStream = QwenOptionalToggle.ENABLED, enableSearch = QwenOptionalToggle.ENABLED,
                imageDetail = "high", imageMaxPixels = 16_777_216),
            customBody = listOf(CustomBody("thinking_budget", JsonPrimitive(2000))))
        val body = chat(params, messages = listOf(UIMessage(role = MessageRole.USER,
            parts = listOf(UIMessagePart.Image("https://example.test/img.png"), UIMessagePart.Video("https://example.test/video.mp4")))))
        assertTrue(body["enable_thinking"]!!.jsonPrimitive.boolean)
        assertEquals("xhigh", body["reasoning_effort"]!!.jsonPrimitive.content)
        assertFalse(body.containsKey("thinking_budget"))
        assertEquals(262_144, body["max_tokens"]!!.jsonPrimitive.int)
        assertEquals("json_schema", body["response_format"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertTrue(body["tool_stream"]!!.jsonPrimitive.boolean)
        assertTrue(body["preserve_thinking"]!!.jsonPrimitive.boolean)
        assertTrue(body["enable_search"]!!.jsonPrimitive.boolean)
        assertEquals(.3f, body["frequency_penalty"]!!.jsonPrimitive.float)
        val content = body["messages"]!!.jsonArray.single().jsonObject["content"]!!.jsonArray
        val image = content[0].jsonObject
        assertEquals(16_777_216, image["max_pixels"]!!.jsonPrimitive.int)
        assertFalse(image["image_url"]!!.jsonObject.containsKey("max_pixels"))
        assertEquals("high", image["image_url"]!!.jsonObject["detail"]!!.jsonPrimitive.content)
        assertEquals("video_url", content[1].jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test fun `Qwen4 non thinking uses separate lower output cap`() {
        val params = TextGenerationParams(model = model("qwen4-max"), reasoningLevel = ReasoningLevel.OFF, maxTokens = 200_000)
        val body = chat(params)
        assertFalse(body["enable_thinking"]!!.jsonPrimitive.boolean)
        assertFalse(body.containsKey("reasoning_effort"))
        assertEquals(131_072, body["max_tokens"]!!.jsonPrimitive.int)
        assertEquals(listOf(ReasoningLevel.OFF, ReasoningLevel.AUTO, ReasoningLevel.LOW, ReasoningLevel.HIGH, ReasoningLevel.XHIGH),
            resolveReasoningLevelSupport(params.model, qwen).levels)
    }

    @Test fun `display names including Chinese match capability without rewriting model ID`() {
        val model = Model(modelId = "opaque-deployment", displayName = "千问4 Max").withDetectedCapabilities()
        assertEquals(ModelParameterFamily.QWEN, model.inferParameterFamily())
        assertEquals(1_000_000, model.contextWindowTokens)
        assertTrue(Modality.VIDEO in model.inputModalities)
        assertTrue(model.supportsFunctionCalling())
        assertTrue(model.supportsReasoningCapability())
        assertEquals("opaque-deployment", chat(TextGenerationParams(model = model))["model"]!!.jsonPrimitive.content)
    }

    @Test fun `gateway contracts are preserved`() {
        val gateway = ProviderSetting.OpenAI(baseUrl = "https://gateway.example/v1")
        val params = TextGenerationParams(model = model("gpt-6.2-sol"),
            customBody = listOf(CustomBody("temperature", JsonPrimitive(.4))))
        assertFalse(gateway.shouldUseResponsesApi(params))
        val body = ResponseAPI(OkHttpClient()).buildRequestBody(gateway,
            listOf(UIMessage.system("instructions"), UIMessage.user("question")), params, false)
        assertTrue(body.containsKey("instructions"))
        assertTrue(body.containsKey("temperature"))
    }

    @Test fun `official Kimi and MiniMax use current completion limit field`() {
        for ((host, id) in listOf("https://api.moonshot.cn/v1" to "kimi-k3", "https://api.minimax.io/v1" to "MiniMax-M3.1-Flash")) {
            val body = chat(TextGenerationParams(model = model(id), maxTokens = 4096), ProviderSetting.OpenAI(baseUrl = host))
            assertEquals(4096, body["max_completion_tokens"]!!.jsonPrimitive.int)
            assertFalse(body.containsKey("max_tokens"))
        }
    }
}
