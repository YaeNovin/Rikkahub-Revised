package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.QwenGenerationOptions
import me.rerere.ai.provider.QwenOptionalToggle
import me.rerere.ai.provider.QwenResponseFormat
import me.rerere.ai.provider.QwenToolChoice
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.KeyRoulette
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class QwenOptionsRequestTest {
    private lateinit var chatApi: ChatCompletionsAPI
    private lateinit var responseApi: ResponseAPI

    @Before
    fun setUp() {
        chatApi = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())
        responseApi = ResponseAPI(OkHttpClient())
    }

    @Test
    fun `recognizes Qwen aliases and excludes non chat models`() {
        listOf(
            "qwen3.8-max",
            "Qwen/Qwen3.5-397B-A17B",
            "provider:qwen3-vl-plus",
            "qwq-plus",
            "qvq-max",
        ).forEach { assertTrue("model=$it", resolveQwenModelParameterSupport(it).available) }
        listOf(
            "qwen-image-plus",
            "qwen3-image-edit",
            "qwen-video",
            "qwen3-video",
            "qwen2.5-audio",
            "qwen3-embedding",
            "qwen-rerank-v2",
            "qwen-realtime",
            "gpt-5.6",
        ).forEach { assertFalse("model=$it", resolveQwenModelParameterSupport(it).available) }

        assertTrue(isAlibabaModelStudioHost("dashscope-us.aliyuncs.com"))
        assertTrue(isAlibabaModelStudioHost("workspace.cn-beijing.maas.aliyuncs.com"))
        assertFalse(isAlibabaModelStudioHost("api.openai.com"))
        assertTrue(resolveQwenModelParameterSupport("qwen3.7-plus").supportsJsonSchema)
        assertFalse(resolveQwenModelParameterSupport("qwen3.7-flash").supportsJsonSchema)
    }

    @Test
    fun `chat completions sends supported Qwen controls and schema`() {
        val body = buildChatRequest(
            params = TextGenerationParams(
                model = Model(modelId = "qwen3.7-plus", abilities = listOf(ModelAbility.TOOL)),
                maxTokens = 8_192,
                tools = listOf(testTool("lookup")),
                qwenOptions = fullOptions(),
            ),
            messages = listOf(multimodalUserMessage()),
            stream = true,
        )

        assertEquals(false, body["parallel_tool_calls"]?.jsonPrimitive?.boolean)
        assertEquals("required", body["tool_choice"]?.jsonPrimitive?.content)
        assertEquals(true, body["tool_stream"]?.jsonPrimitive?.boolean)
        assertEquals(40, body["top_k"]?.jsonPrimitive?.content?.toInt())
        assertEquals(1.2f, body["repetition_penalty"]?.jsonPrimitive?.float)
        assertEquals(0.3f, body["presence_penalty"]?.jsonPrimitive?.float)
        assertEquals(42L, body["seed"]?.jsonPrimitive?.long)
        assertEquals(2, body["stop"]?.jsonArray?.size)
        assertEquals(true, body["preserve_thinking"]?.jsonPrimitive?.boolean)
        assertEquals(true, body["vl_high_resolution_images"]?.jsonPrimitive?.boolean)
        assertFalse(body.containsKey("max_tokens"))
        assertFalse(body.containsKey("max_completion_tokens"))
        val responseFormat = body["response_format"]?.jsonObject
        assertEquals("json_schema", responseFormat?.get("type")?.jsonPrimitive?.content)
        assertEquals(
            "answer",
            responseFormat?.get("json_schema")?.jsonObject?.get("name")?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `chat omits fields whose runtime conditions are not met`() {
        val body = buildChatRequest(
            params = TextGenerationParams(
                model = Model(modelId = "qwen3.7-flash", abilities = listOf(ModelAbility.TOOL)),
                maxTokens = 4_096,
                tools = listOf(testTool("lookup")),
                qwenOptions = fullOptions(),
            ),
            stream = false,
        )

        assertFalse(body.containsKey("tool_stream"))
        assertFalse(body.containsKey("vl_high_resolution_images"))
        assertFalse(body.containsKey("response_format"))
        assertEquals(4_096, body["max_tokens"]?.jsonPrimitive?.content?.toInt())
    }

    @Test
    fun `responses sends only safe Qwen tool choice`() {
        val params = TextGenerationParams(
            model = Model(modelId = "qwen3.7-plus", abilities = listOf(ModelAbility.TOOL)),
            tools = listOf(testTool("lookup")),
            qwenOptions = fullOptions(),
        )
        val body = responseApi.buildRequestBody(
            providerSetting = ProviderSetting.OpenAI(
                baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
                useResponseApi = true,
            ),
            messages = listOf(UIMessage.user("hello")),
            params = params,
            stream = true,
        )

        assertEquals("required", body["tool_choice"]?.jsonPrimitive?.content)
        listOf(
            "parallel_tool_calls",
            "tool_stream",
            "top_k",
            "repetition_penalty",
            "presence_penalty",
            "seed",
            "stop",
            "preserve_thinking",
            "response_format",
        ).forEach { assertFalse("field=$it", body.containsKey(it)) }
    }

    @Test
    fun `responses omits required tool choice when more than one tool exists`() {
        val body = responseApi.buildRequestBody(
            providerSetting = ProviderSetting.OpenAI(useResponseApi = true),
            messages = listOf(UIMessage.user("hello")),
            params = TextGenerationParams(
                model = Model(modelId = "qwen3.7-plus", abilities = listOf(ModelAbility.TOOL)),
                tools = listOf(testTool("one"), testTool("two")),
                qwenOptions = QwenGenerationOptions(toolChoice = QwenToolChoice.REQUIRED),
            ),
            stream = true,
        )

        assertFalse(body.containsKey("tool_choice"))
    }

    @Test
    fun `non Qwen models never receive stored Qwen options`() {
        val body = buildChatRequest(
            params = TextGenerationParams(
                model = Model(modelId = "deepseek-chat", abilities = listOf(ModelAbility.TOOL)),
                tools = listOf(testTool("lookup")),
                qwenOptions = fullOptions(),
            ),
            stream = true,
        )

        listOf(
            "parallel_tool_calls",
            "tool_choice",
            "tool_stream",
            "top_k",
            "repetition_penalty",
            "presence_penalty",
            "seed",
            "stop",
            "preserve_thinking",
            "vl_high_resolution_images",
            "response_format",
        ).forEach { assertFalse("field=$it", body.containsKey(it)) }
    }

    private fun fullOptions() = QwenGenerationOptions(
        parallelToolCalls = QwenOptionalToggle.DISABLED,
        toolChoice = QwenToolChoice.REQUIRED,
        topK = 40,
        repetitionPenalty = 1.2f,
        presencePenalty = 0.3f,
        seed = 42,
        stopSequences = listOf("END", "STOP"),
        preserveThinking = QwenOptionalToggle.ENABLED,
        toolStream = QwenOptionalToggle.ENABLED,
        highResolutionVision = QwenOptionalToggle.ENABLED,
        responseFormat = QwenResponseFormat.JSON_SCHEMA,
        responseSchemaName = "answer",
        responseJsonSchema = """{"type":"object","properties":{"answer":{"type":"string"}}}""",
    )

    private fun multimodalUserMessage() = UIMessage(
        role = MessageRole.USER,
        parts = listOf(
            UIMessagePart.Text("describe"),
            UIMessagePart.Image("https://example.com/image.png"),
        ),
    )

    private fun testTool(name: String): Tool = Tool(
        name = name,
        description = "test",
        parameters = { InputSchema.Obj(properties = JsonObject(emptyMap())) },
        execute = { emptyList() },
    )

    private fun buildChatRequest(
        params: TextGenerationParams,
        messages: List<UIMessage> = listOf(UIMessage.user("hello")),
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
        return method.invoke(
            chatApi,
            messages,
            params,
            ProviderSetting.OpenAI(
                baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1"
            ),
            stream,
        ) as JsonObject
    }
}
