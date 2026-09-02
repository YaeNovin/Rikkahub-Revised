package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.OpenAIGenerationOptions
import me.rerere.ai.provider.OpenAIParallelToolCalls
import me.rerere.ai.provider.OpenAIReasoningContext
import me.rerere.ai.provider.OpenAIReasoningMode
import me.rerere.ai.provider.OpenAIReasoningSummary
import me.rerere.ai.provider.OpenAIServiceTier
import me.rerere.ai.provider.OpenAITextVerbosity
import me.rerere.ai.provider.OpenAIToolChoice
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

class OpenAIOptionsRequestTest {
    private lateinit var chatApi: ChatCompletionsAPI
    private lateinit var responseApi: ResponseAPI

    @Before
    fun setUp() {
        chatApi = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())
        responseApi = ResponseAPI(OkHttpClient())
    }

    @Test
    fun `chat completions sends supported GPT 5 options with current field names`() {
        val body = buildChatRequest(
            messages = listOf(UIMessage.user("hello")),
            params = gpt56Params(),
            providerSetting = ProviderSetting.OpenAI(baseUrl = "https://api.openai.com/v1"),
        )

        assertEquals("high", body["verbosity"]?.jsonPrimitive?.content)
        assertEquals("fast", body["service_tier"]?.jsonPrimitive?.content)
        assertEquals(false, body["parallel_tool_calls"]?.jsonPrimitive?.boolean)
        assertEquals("required", body["tool_choice"]?.jsonPrimitive?.content)
        assertEquals("max", body["reasoning_effort"]?.jsonPrimitive?.content)
        assertEquals(4096, body["max_completion_tokens"]?.jsonPrimitive?.content?.toInt())
        assertFalse(body.containsKey("max_tokens"))
        assertFalse(body.containsKey("temperature"))
        assertFalse(body.containsKey("top_p"))
    }

    @Test
    fun `responses sends nested GPT 5 6 options and built in tool limit`() {
        val params = gpt56Params().copy(
            model = gpt56Params().model.copy(tools = setOf(BuiltInTools.Search)),
            openAIOptions = gpt56Params().openAIOptions.copy(
                reasoningSummary = OpenAIReasoningSummary.DETAILED,
                reasoningContext = OpenAIReasoningContext.ALL_TURNS,
                reasoningMode = OpenAIReasoningMode.PRO,
                maxToolCalls = 4,
            ),
        )
        val body = responseApi.buildRequestBody(
            providerSetting = ProviderSetting.OpenAI(baseUrl = "https://api.openai.com/v1"),
            messages = listOf(UIMessage.user("hello")),
            params = params,
            stream = true,
        )

        assertEquals("high", body["text"]?.jsonObject?.get("verbosity")?.jsonPrimitive?.content)
        assertEquals("fast", body["service_tier"]?.jsonPrimitive?.content)
        assertEquals(false, body["parallel_tool_calls"]?.jsonPrimitive?.boolean)
        assertEquals("required", body["tool_choice"]?.jsonPrimitive?.content)
        assertEquals(4, body["max_tool_calls"]?.jsonPrimitive?.content?.toInt())
        val reasoning = body["reasoning"]?.jsonObject
        assertEquals("max", reasoning?.get("effort")?.jsonPrimitive?.content)
        assertEquals("detailed", reasoning?.get("summary")?.jsonPrimitive?.content)
        assertEquals("all_turns", reasoning?.get("context")?.jsonPrimitive?.content)
        assertEquals("pro", reasoning?.get("mode")?.jsonPrimitive?.content)
        assertFalse(body.containsKey("temperature"))
        assertFalse(body.containsKey("top_p"))
    }

    @Test
    fun `GPT 4 keeps sampling fields and omits GPT 5 verbosity`() {
        val body = buildChatRequest(
            messages = listOf(UIMessage.user("hello")),
            params = TextGenerationParams(
                model = Model(modelId = "gpt-4.1"),
                temperature = 0.4f,
                topP = 0.8f,
                maxTokens = 1024,
                openAIOptions = OpenAIGenerationOptions(
                    verbosity = OpenAITextVerbosity.HIGH,
                    serviceTier = OpenAIServiceTier.DEFAULT,
                ),
            ),
            providerSetting = ProviderSetting.OpenAI(baseUrl = "https://api.openai.com/v1"),
        )

        assertTrue(body.containsKey("temperature"))
        assertTrue(body.containsKey("top_p"))
        assertEquals(1024, body["max_tokens"]?.jsonPrimitive?.content?.toInt())
        assertEquals("default", body["service_tier"]?.jsonPrimitive?.content)
        assertFalse(body.containsKey("verbosity"))
    }

    @Test
    fun `non OpenAI reasoning models keep compatible max tokens field`() {
        val body = buildChatRequest(
            messages = listOf(UIMessage.user("hello")),
            params = TextGenerationParams(
                model = Model(
                    modelId = "deepseek-v4-pro",
                    abilities = listOf(ModelAbility.REASONING),
                ),
                maxTokens = 2048,
                reasoningLevel = ReasoningLevel.HIGH,
            ),
            providerSetting = ProviderSetting.OpenAI(baseUrl = "https://api.deepseek.com/v1"),
        )

        assertEquals(2048, body["max_tokens"]?.jsonPrimitive?.content?.toInt())
        assertFalse(body.containsKey("max_completion_tokens"))
    }

    @Test
    fun `official-only GPT 5 6 reasoning fields are omitted for compatible endpoints`() {
        val params = gpt56Params().copy(
            openAIOptions = gpt56Params().openAIOptions.copy(
                reasoningContext = OpenAIReasoningContext.ALL_TURNS,
                reasoningMode = OpenAIReasoningMode.PRO,
            )
        )
        val body = responseApi.buildRequestBody(
            providerSetting = ProviderSetting.OpenAI(baseUrl = "https://models.example.com/v1"),
            messages = listOf(UIMessage.user("hello")),
            params = params,
            stream = false,
        )

        val reasoning = body["reasoning"]?.jsonObject
        assertFalse(reasoning?.containsKey("context") == true)
        assertFalse(reasoning?.containsKey("mode") == true)
        assertEquals("max", reasoning?.get("effort")?.jsonPrimitive?.content)
    }

    @Test
    fun `active GPT text models are supported and retired or specialized models are excluded`() {
        listOf(
            "gpt-4o",
            "gpt-4o-2024-05-13",
            "gpt-4.1-mini",
            "gpt-5-2025-08-07",
            "gpt-5.4",
            "openai/gpt-5.6-sol",
        ).forEach {
            assertTrue("model=$it", resolveOpenAIModelParameterSupport(it).available)
        }
        listOf(
            "gpt-4.5-preview",
            "gpt-5.3-chat-latest",
            "gpt-5.1-codex",
            "gpt-5.4-codex",
            "gpt-4-0314",
            "gpt-image-2",
            "gpt-4o-realtime-preview",
        ).forEach {
            assertFalse("model=$it", resolveOpenAIModelParameterSupport(it).available)
        }
    }

    private fun gpt56Params(): TextGenerationParams = TextGenerationParams(
        model = Model(
            modelId = "gpt-5.6-sol",
            abilities = listOf(ModelAbility.REASONING, ModelAbility.TOOL),
        ),
        temperature = 0.5f,
        topP = 0.9f,
        maxTokens = 4096,
        tools = listOf(testTool()),
        reasoningLevel = ReasoningLevel.MAX,
        openAIOptions = OpenAIGenerationOptions(
            verbosity = OpenAITextVerbosity.HIGH,
            serviceTier = OpenAIServiceTier.FAST,
            parallelToolCalls = OpenAIParallelToolCalls.DISABLED,
            toolChoice = OpenAIToolChoice.REQUIRED,
        ),
    )

    private fun testTool(): Tool = Tool(
        name = "lookup",
        description = "test",
        parameters = { InputSchema.Obj(properties = JsonObject(emptyMap())) },
        execute = { emptyList() },
    )

    private fun buildChatRequest(
        messages: List<UIMessage>,
        params: TextGenerationParams,
        providerSetting: ProviderSetting.OpenAI,
        stream: Boolean = false,
    ): JsonObject {
        val method = ChatCompletionsAPI::class.java.getDeclaredMethod(
            "buildChatCompletionRequest",
            List::class.java,
            TextGenerationParams::class.java,
            ProviderSetting.OpenAI::class.java,
            Boolean::class.javaPrimitiveType,
        )
        method.isAccessible = true
        return method.invoke(chatApi, messages, params, providerSetting, stream) as JsonObject
    }
}
