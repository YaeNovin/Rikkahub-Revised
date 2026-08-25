package me.rerere.ai.provider.providers.claude

import kotlinx.serialization.json.JsonObject
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
import me.rerere.ai.provider.ProviderRequestOperation
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.ClaudeReasoningMetadata
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.toMetadata
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DeepSeekAnthropicOptionsRequestTest {
    private lateinit var provider: ClaudeProvider

    @Before
    fun setUp() {
        provider = ClaudeProvider(OkHttpClient())
    }

    @Test
    fun `Anthropic channel sends supported DeepSeek controls and max thinking`() {
        val body = buildRequest(
            modelId = "deepseek-v4-pro",
            reasoningLevel = ReasoningLevel.MAX,
        )

        assertEquals("enabled", body["thinking"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertEquals("max", body["output_config"]?.jsonObject?.get("effort")?.jsonPrimitive?.content)
        assertEquals("any", body["tool_choice"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertEquals(2, body["stop_sequences"]?.jsonArray?.size)
        assertEquals("test-user_1", body["metadata"]?.jsonObject?.get("user_id")?.jsonPrimitive?.content)
        assertFalse(body.containsKey("temperature"))
        assertFalse(body.containsKey("top_p"))
        assertFalse(body.containsKey("response_format"))
        assertFalse(body.containsKey("logprobs"))
        assertFalse(body.containsKey("top_logprobs"))
        assertFalse(body["output_config"]?.jsonObject?.containsKey("format") == true)
    }

    @Test
    fun `Anthropic channel keeps DeepSeek sampling only while thinking is off`() {
        val body = buildRequest(
            modelId = "deepseek-v4-flash",
            reasoningLevel = ReasoningLevel.OFF,
        )

        assertEquals("disabled", body["thinking"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertEquals(1.7f, body["temperature"]?.jsonPrimitive?.float)
        assertEquals(0.8f, body["top_p"]?.jsonPrimitive?.float)
        assertFalse(body.containsKey("output_config"))
    }

    @Test
    fun `Anthropic automatic thinking omits effort and sampling overrides`() {
        val body = buildRequest(
            modelId = "deepseek-v4-flash",
            reasoningLevel = ReasoningLevel.AUTO,
        )

        assertEquals("enabled", body["thinking"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertFalse(body.containsKey("output_config"))
        assertFalse(body.containsKey("temperature"))
        assertFalse(body.containsKey("top_p"))
    }

    @Test
    fun `stored DeepSeek controls do not leak to other Anthropic models`() {
        val body = buildRequest(
            modelId = "custom-anthropic-model",
            reasoningLevel = ReasoningLevel.MAX,
        )

        listOf("thinking", "output_config", "tool_choice", "stop_sequences", "metadata")
            .forEach { field -> assertFalse("field=$field", body.containsKey(field)) }
    }

    @Test
    fun `tool requests preserve complete prior Anthropic thinking blocks`() {
        val reasoning = UIMessagePart.Reasoning("complete prior reasoning").apply {
            metadata = ClaudeReasoningMetadata(signature = "test-signature").toMetadata()
        }
        val body = buildRequest(
            modelId = "deepseek-v4-pro",
            reasoningLevel = ReasoningLevel.HIGH,
            messages = listOf(
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(reasoning, UIMessagePart.Text("prior answer")),
                ),
                UIMessage.user("continue"),
            ),
        )
        val thinking = body["messages"]?.jsonArray
            ?.first()?.jsonObject
            ?.get("content")?.jsonArray
            ?.first()?.jsonObject

        assertEquals("thinking", thinking?.get("type")?.jsonPrimitive?.content)
        assertEquals("complete prior reasoning", thinking?.get("thinking")?.jsonPrimitive?.content)
        assertEquals("test-signature", thinking?.get("signature")?.jsonPrimitive?.content)
        assertTrue(body["tools"]?.jsonArray?.isNotEmpty() == true)
    }

    @Test
    fun `diagnostics redact configured DeepSeek user id`() {
        val diagnostics = buildRequest(
            modelId = "deepseek-v4-pro",
            reasoningLevel = ReasoningLevel.HIGH,
        ).claudeRequestDiagnostics(
            providerSetting = ProviderSetting.Claude(baseUrl = DEEPSEEK_ANTHROPIC_BASE_URL),
            operation = ProviderRequestOperation.STREAM_TEXT,
        )

        assertEquals("configured", diagnostics.parameters["metadata.user_id"])
        assertFalse(diagnostics.parameters.values.contains("test-user_1"))
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

    private fun buildRequest(
        modelId: String,
        reasoningLevel: ReasoningLevel,
        messages: List<UIMessage> = listOf(UIMessage.user("hello")),
    ): JsonObject {
        val method = ClaudeProvider::class.java.getDeclaredMethod(
            "buildMessageRequest",
            ProviderSetting.Claude::class.java,
            List::class.java,
            TextGenerationParams::class.java,
            Boolean::class.javaPrimitiveType,
        )
        method.isAccessible = true
        return method.invoke(
            provider,
            ProviderSetting.Claude(baseUrl = DEEPSEEK_ANTHROPIC_BASE_URL),
            messages,
            TextGenerationParams(
                model = Model(
                    modelId = modelId,
                    abilities = listOf(ModelAbility.REASONING, ModelAbility.TOOL),
                ),
                temperature = 1.7f,
                topP = 0.8f,
                tools = listOf(testTool()),
                reasoningLevel = reasoningLevel,
                deepSeekOptions = fullOptions(),
            ),
            false,
        ) as JsonObject
    }

    private fun testTool(): Tool = Tool(
        name = "lookup",
        description = "test",
        parameters = { InputSchema.Obj(properties = JsonObject(emptyMap())) },
        execute = { emptyList() },
    )

    private companion object {
        const val DEEPSEEK_ANTHROPIC_BASE_URL = "https://api.deepseek.com/anthropic"
    }
}
