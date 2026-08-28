package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.DeepSeekGenerationOptions
import me.rerere.ai.provider.DeepSeekImageDetail
import me.rerere.ai.provider.DeepSeekOptionalToggle
import me.rerere.ai.provider.DeepSeekResponseFormat
import me.rerere.ai.provider.DeepSeekToolChoice
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.KeyRoulette
import me.rerere.ai.registry.ModelRegistry
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DeepSeekOptionsRequestTest {
    private lateinit var chatApi: ChatCompletionsAPI
    private lateinit var responseApi: ResponseAPI

    @Before
    fun setUp() {
        chatApi = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())
        responseApi = ResponseAPI(OkHttpClient())
    }

    @Test
    fun `recognizes only currently served DeepSeek V4 models`() {
        listOf(
            "deepseek-v4-flash",
            "deepseek-v4-pro",
            "deepseek-v4-flash-vision-exp",
            "deepseek-ai/DeepSeek-V4-Pro",
            "provider:deepseek_v4_flash",
        ).forEach { assertTrue("model=$it", resolveDeepSeekModelParameterSupport(it).available) }

        listOf(
            "deepseek-chat",
            "deepseek-reasoner",
            "deepseek-v3.2",
            "deepseek-r1-0528",
            "deepseek-v4-flash-preview",
            "deepseek-v4-pro-preview",
            "gpt-5.6",
        ).forEach { assertFalse("model=$it", resolveDeepSeekModelParameterSupport(it).available) }

        assertTrue(resolveDeepSeekModelParameterSupport("deepseek-v4-flash-vision-exp").supportsVision)
        assertFalse(resolveDeepSeekModelParameterSupport("deepseek-v4-flash").supportsVision)
        assertTrue(
            Modality.IMAGE in ModelRegistry.MODEL_INPUT_MODALITIES.getData(
                "deepseek-v4-flash-vision-exp"
            )
        )
        assertTrue(isOfficialDeepSeekHost("api.deepseek.com"))
    }

    @Test
    fun `chat sends DeepSeek controls and omits sampling while thinking`() {
        val body = buildChatRequest(
            modelId = "deepseek-v4-pro",
            reasoningLevel = ReasoningLevel.HIGH,
        )

        assertEquals("required", body["tool_choice"]?.jsonPrimitive?.content)
        assertEquals(2, body["stop"]?.jsonArray?.size)
        assertEquals("json_object", body["response_format"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertEquals(true, body["logprobs"]?.jsonPrimitive?.boolean)
        assertEquals(7, body["top_logprobs"]?.jsonPrimitive?.content?.toInt())
        assertEquals("test-user_1", body["user_id"]?.jsonPrimitive?.content)
        assertEquals("enabled", body["thinking"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertEquals("high", body["reasoning_effort"]?.jsonPrimitive?.content)
        assertFalse(body.containsKey("temperature"))
        assertFalse(body.containsKey("top_p"))
    }

    @Test
    fun `chat keeps sampling when thinking is disabled`() {
        val body = buildChatRequest(
            modelId = "deepseek-v4-flash",
            reasoningLevel = ReasoningLevel.OFF,
        )

        assertEquals(0.7f, body["temperature"]?.jsonPrimitive?.float)
        assertEquals(0.8f, body["top_p"]?.jsonPrimitive?.float)
        assertEquals("disabled", body["thinking"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertFalse(body.containsKey("reasoning_effort"))
    }

    @Test
    fun `responses maps DeepSeek fields and vision detail to official shapes`() {
        val body = responseApi.buildRequestBody(
            providerSetting = ProviderSetting.OpenAI(
                baseUrl = "https://api.deepseek.com",
                useResponseApi = true,
            ),
            messages = listOf(multimodalUserMessage()),
            params = fullParams(
                modelId = "deepseek-v4-flash-vision-exp",
                reasoningLevel = ReasoningLevel.MAX,
            ),
            stream = true,
        )

        assertEquals("required", body["tool_choice"]?.jsonPrimitive?.content)
        assertEquals("json_object", body["text"]?.jsonObject?.get("format")?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertEquals(7, body["top_logprobs"]?.jsonPrimitive?.content?.toInt())
        assertEquals("test-user_1", body["user"]?.jsonPrimitive?.content)
        assertEquals("max", body["reasoning"]?.jsonObject?.get("effort")?.jsonPrimitive?.content)
        assertFalse(body["reasoning"]?.jsonObject?.containsKey("summary") == true)
        assertFalse(body.containsKey("include"))
        assertFalse(body.containsKey("temperature"))
        assertFalse(body.containsKey("top_p"))
        val image = body["input"]?.jsonArray
            ?.first()?.jsonObject
            ?.get("content")?.jsonArray
            ?.last()?.jsonObject
        assertEquals("original", image?.get("detail")?.jsonPrimitive?.content)
    }

    @Test
    fun `stored DeepSeek options never leak to retired or unrelated models`() {
        listOf("deepseek-chat", "deepseek-reasoner", "gpt-5.6").forEach { modelId ->
            val body = buildChatRequest(modelId = modelId, reasoningLevel = ReasoningLevel.OFF)
            listOf(
                "tool_choice",
                "stop",
                "response_format",
                "logprobs",
                "top_logprobs",
                "user_id",
            ).forEach { field -> assertFalse("model=$modelId field=$field", body.containsKey(field)) }
        }
    }

    private fun fullOptions() = DeepSeekGenerationOptions(
        toolChoice = DeepSeekToolChoice.REQUIRED,
        responseFormat = DeepSeekResponseFormat.JSON_OBJECT,
        stopSequences = listOf("END", "STOP"),
        logProbabilities = DeepSeekOptionalToggle.ENABLED,
        topLogProbs = 7,
        userId = "test-user_1",
        imageDetail = DeepSeekImageDetail.ORIGINAL,
    )

    private fun fullParams(modelId: String, reasoningLevel: ReasoningLevel) = TextGenerationParams(
        model = Model(
            modelId = modelId,
            abilities = listOf(ModelAbility.REASONING, ModelAbility.TOOL),
        ),
        temperature = 0.7f,
        topP = 0.8f,
        tools = listOf(testTool()),
        reasoningLevel = reasoningLevel,
        deepSeekOptions = fullOptions(),
    )

    private fun buildChatRequest(modelId: String, reasoningLevel: ReasoningLevel): JsonObject {
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
            listOf(UIMessage.user("return json")),
            fullParams(modelId, reasoningLevel),
            ProviderSetting.OpenAI(baseUrl = "https://api.deepseek.com"),
            false,
        ) as JsonObject
    }

    private fun multimodalUserMessage() = UIMessage(
        role = MessageRole.USER,
        parts = listOf(
            UIMessagePart.Text("describe"),
            UIMessagePart.Image("https://example.com/image.png"),
        ),
    )

    private fun testTool(): Tool = Tool(
        name = "lookup",
        description = "test",
        parameters = { InputSchema.Obj(properties = JsonObject(emptyMap())) },
        execute = { emptyList() },
    )
}
