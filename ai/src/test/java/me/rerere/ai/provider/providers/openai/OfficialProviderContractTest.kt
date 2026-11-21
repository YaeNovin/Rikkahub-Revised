package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.*
import me.rerere.ai.core.*
import me.rerere.ai.provider.*
import me.rerere.ai.ui.*
import me.rerere.ai.util.KeyRoulette
import me.rerere.ai.util.json
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class OfficialProviderContractTest {
    private val tool = Tool("clock", "No arguments", execute = { emptyList() })
    private fun model(id: String) = Model(modelId = id).withDetectedCapabilities()
    private fun chat(base: String, params: TextGenerationParams, messages: List<UIMessage> = listOf(UIMessage.user("hello"))): JsonObject {
        val method = ChatCompletionsAPI::class.java.getDeclaredMethod("buildChatCompletionRequest", List::class.java,
            TextGenerationParams::class.java, ProviderSetting.OpenAI::class.java, Boolean::class.javaPrimitiveType).apply { isAccessible = true }
        return method.invoke(ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default()), messages, params,
            ProviderSetting.OpenAI(baseUrl = base), true) as JsonObject
    }

    @Test fun `MiMo infer abilities by display name and preserve actual model id`() {
        val model = Model(modelId = "deployment-abc", displayName = "MiMo V2.6 Pro").withDetectedCapabilities()
        assertTrue(model.isMiMo26())
        assertTrue(model.supportsFunctionCalling())
        assertTrue(model.supportsReasoningCapability())
        assertTrue(Modality.VIDEO in model.inputModalities)
        assertEquals(1_000_000, model.contextWindowTokens)
        assertEquals(listOf(ReasoningLevel.OFF, ReasoningLevel.AUTO), resolveReasoningLevelSupport(model, ProviderSetting.OpenAI()).levels)
        val body = chat("https://api.xiaomimimo.com/v1", TextGenerationParams(model, reasoningLevel = ReasoningLevel.AUTO))
        assertEquals("deployment-abc", body["model"]!!.jsonPrimitive.content)
    }

    @Test fun `MiMo chat maps toggles token cap tools penalties video and JSON instruction`() {
        val params = TextGenerationParams(model("mimo-v2.6-pro"), temperature = .2f, topP = .5f,
            reasoningLevel = ReasoningLevel.AUTO, maxTokens = 1_000_000, tools = listOf(tool),
            openAIOptions = OpenAIGenerationOptions(responseFormat = OpenAIResponseFormat.JSON_OBJECT,
                toolChoice = OpenAIToolChoice.REQUIRED, frequencyPenalty = .3f))
        val body = chat("https://api.xiaomimimo.com/v1", params,
            listOf(UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Video("https://example.test/video.mp4")))))
        assertEquals("enabled", body["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertFalse(body.containsKey("reasoning_effort"))
        assertFalse(body.containsKey("temperature"))
        assertFalse(body.containsKey("top_p"))
        assertEquals(131_072, body["max_completion_tokens"]!!.jsonPrimitive.int)
        assertEquals("auto", body["tool_choice"]!!.jsonPrimitive.content)
        assertEquals(.3f, body["frequency_penalty"]!!.jsonPrimitive.float)
        assertEquals("object", body["tools"]!!.jsonArray.single().jsonObject["function"]!!.jsonObject["parameters"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        val messages = body["messages"]!!.jsonArray
        assertTrue(messages.first().jsonObject["content"]!!.jsonPrimitive.content.contains("JSON"))
        assertEquals("video_url", messages.last().jsonObject["content"]!!.jsonArray.single().jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test fun `MiMo off permits sampling and none actually disables tools`() {
        val body = chat("https://api.xiaomimimo.com/v1", TextGenerationParams(model("mimo-v2.6-flash"),
            reasoningLevel = ReasoningLevel.OFF, temperature = 2f, topP = 0f, tools = listOf(tool),
            openAIOptions = OpenAIGenerationOptions(toolChoice = OpenAIToolChoice.NONE)))
        assertEquals("disabled", body["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(1.5f, body["temperature"]!!.jsonPrimitive.float)
        assertEquals(.01f, body["top_p"]!!.jsonPrimitive.float)
        assertFalse(body.containsKey("tools"))
        assertFalse(body.containsKey("tool_choice"))
    }

    @Test fun `Kimi responses uses documented effort and schema without OpenAI extensions`() {
        val setting = ProviderSetting.OpenAI(baseUrl = "https://api.moonshot.ai/v1", useResponseApi = true)
        val body = ResponseAPI(OkHttpClient()).buildRequestBody(setting, listOf(UIMessage.user("hello")),
            TextGenerationParams(model("kimi-k3.1-pro"), maxTokens = 2_000_000, tools = listOf(tool), reasoningLevel = ReasoningLevel.HIGH,
                openAIOptions = OpenAIGenerationOptions(responseFormat = OpenAIResponseFormat.JSON_SCHEMA,
                    responseJsonSchema = """{"type":"object","properties":{"x":{"type":"integer"}}}""")), false)
        assertFalse(body.containsKey("include"))
        assertFalse(body.containsKey("store"))
        assertFalse(body["reasoning"]!!.jsonObject.containsKey("summary"))
        assertEquals("high", body["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertEquals(1_048_576, body["max_output_tokens"]!!.jsonPrimitive.int)
        assertEquals(false, body["tools"]!!.jsonArray.single().jsonObject["strict"]!!.jsonPrimitive.boolean)
        assertEquals("json_schema", body["text"]!!.jsonObject["format"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test fun `GLM responses routes to separate base and accepts singleton content and missing total`() {
        val setting = ProviderSetting.OpenAI(baseUrl = "https://open.bigmodel.cn/api/paas/v4", useResponseApi = true)
        assertEquals("https://open.bigmodel.cn/api/v1/responses", setting.responsesUrl())
        assertEquals("https://proxy.example/api/paas/v4/responses", setting.copy(baseUrl = "https://proxy.example/api/paas/v4").responsesUrl())
        val params = TextGenerationParams(model("glm-5.3"), tools = listOf(tool), reasoningLevel = ReasoningLevel.AUTO)
        val body = ResponseAPI(OkHttpClient()).buildRequestBody(setting, listOf(UIMessage.user("hello")), params, false)
        assertFalse(body.containsKey("include"))
        assertFalse(body.containsKey("reasoning"))
        val output = ResponseAPI(OkHttpClient()).parseResponseOutput(json.parseToJsonElement("""{"id":"r1","model":"glm-5.3","status":"completed","output":[{"type":"message","role":"assistant","content":{"type":"output_text","text":"OK"}}],"usage":{"input_tokens":3,"output_tokens":7}}""").jsonObject)
        assertEquals("OK", output.message.toText())
        assertEquals(10, output.usage!!.totalTokens)
    }

    @Test fun `GLM Responses normalizes native effort output cap and tool choice`() {
        val setting = ProviderSetting.OpenAI(baseUrl = "https://open.bigmodel.cn/api/paas/v4", useResponseApi = true)
        for ((level, expected) in listOf(ReasoningLevel.OFF to "none", ReasoningLevel.LOW to "high", ReasoningLevel.MAX to "max")) {
            val body = ResponseAPI(OkHttpClient()).buildRequestBody(setting, listOf(UIMessage.user("hello")),
                TextGenerationParams(model("glm-5.3"), tools = listOf(tool), reasoningLevel = level, maxTokens = 1_000_000,
                    openAIOptions = OpenAIGenerationOptions(toolChoice = OpenAIToolChoice.REQUIRED)), false)
            assertEquals(expected, body["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
            assertEquals(1, body["reasoning"]!!.jsonObject.size)
            assertEquals(131_072, body["max_output_tokens"]!!.jsonPrimitive.int)
            assertEquals("auto", body["tool_choice"]!!.jsonPrimitive.content)
            assertFalse(body.containsKey("include"))
        }
    }

    @Test fun `DeepSeek normalizes forced tools after custom overrides and adds JSON hint`() {
        val body = chat("https://api.deepseek.com/v1", TextGenerationParams(model("deepseek-v4.1-flash"),
            reasoningLevel = ReasoningLevel.HIGH, tools = listOf(tool),
            deepSeekOptions = DeepSeekGenerationOptions(responseFormat = DeepSeekResponseFormat.JSON_OBJECT),
            customBody = listOf(CustomBody("tool_choice", JsonPrimitive("required")), CustomBody("top_p", JsonPrimitive(.5)))))
        assertEquals("auto", body["tool_choice"]!!.jsonPrimitive.content)
        assertEquals(.95f, body["top_p"]!!.jsonPrimitive.float)
        assertTrue(body["messages"]!!.jsonArray.first().jsonObject["content"]!!.jsonPrimitive.content.contains("JSON"))
    }

    @Test fun `native MiniMax retains video token usage and reasoning but omits unrelated fields`() {
        for (host in listOf("api.minimax.cn", "api.minimax.io", "api.minimaxi.com")) {
        val body = chat("https://$host/v1", TextGenerationParams(model("MiniMax-M3.1-Flash"),
            reasoningLevel = ReasoningLevel.MAX, maxTokens = 1_000_000, tools = listOf(tool),
            openAIOptions = OpenAIGenerationOptions(toolChoice = OpenAIToolChoice.REQUIRED, parallelToolCalls = OpenAIParallelToolCalls.ENABLED)),
            listOf(UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Video("https://example.test/video.mp4")))))
        assertEquals(524_288, body["max_completion_tokens"]!!.jsonPrimitive.int)
        assertEquals("max", body["reasoning_effort"]!!.jsonPrimitive.content)
        assertFalse(body.containsKey("tool_choice"))
        assertFalse(body.containsKey("parallel_tool_calls"))
        assertEquals("video_url", body["messages"]!!.jsonArray.single().jsonObject["content"]!!.jsonArray.single().jsonObject["type"]!!.jsonPrimitive.content)
        }
    }

    @Test fun `regional OpenAI endpoints retain native Responses and US-only Ultrafast constraints`() {
        for (region in listOf("us", "eu")) {
            val setting = ProviderSetting.OpenAI(baseUrl = "https://$region.api.openai.com/v1")
            val params = TextGenerationParams(model("gpt-6.2-sol"), tools = listOf(tool),
                openAIOptions = OpenAIGenerationOptions(serviceTier = OpenAIServiceTier.ULTRAFAST))
            assertTrue(setting.shouldUseResponsesApi(params))
            val body = ResponseAPI(OkHttpClient()).buildRequestBody(setting,
                listOf(UIMessage.system("stable"), UIMessage.user("hello")), params, false)
            assertFalse(body.containsKey("instructions"))
            assertEquals(region == "us", body.containsKey("service_tier"))
        }
        assertEquals(OfficialChatVendor.OTHER, officialChatVendor("https://api.openai.com.proxy.example/v1"))
    }
}
