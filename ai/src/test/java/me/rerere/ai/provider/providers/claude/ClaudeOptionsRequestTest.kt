package me.rerere.ai.provider.providers.claude

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.ClaudeGenerationOptions
import me.rerere.ai.provider.ClaudeInferenceGeo
import me.rerere.ai.provider.ClaudeParallelToolCalls
import me.rerere.ai.provider.ClaudeResponseFormat
import me.rerere.ai.provider.ClaudeServiceTier
import me.rerere.ai.provider.ClaudeThinkingDisplay
import me.rerere.ai.provider.ClaudeToolChoice
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderRequestChannel
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ClaudeOptionsRequestTest {
    private lateinit var provider: ClaudeProvider

    @Before
    fun setUp() {
        provider = ClaudeProvider(OkHttpClient())
    }

    @Test
    fun `recognizes only supported Claude model generations and official request channel`() {
        listOf(
            "claude-sonnet-4-6",
            "claude-sonnet-5",
            "claude-opus-4-6",
            "claude-opus-4-7",
            "claude-opus-4-8",
            "claude-opus-5",
            "claude-fable-5",
            "anthropic/claude-sonnet-4-6",
            "anthropic.claude-opus-4-6-v1:0",
        ).forEach { assertTrue("model=$it", resolveClaudeModelParameterSupport(it).available) }
        listOf(
            "gpt-5.6",
            "grok-4.6",
            "claudeish-model",
            "claude-3-5-sonnet-20241022",
            "claude-3-7-sonnet-20250219",
            "claude-sonnet-4-20250514",
            "claude-opus-4-20250514",
            "claude-sonnet-4-5-20250929",
            "claude-opus-4-5-20251101",
            "claude-haiku-4-5-20251001",
            "claude-mythos-5",
            "claude-mythos-preview",
        ).forEach {
            assertFalse("model=$it", resolveClaudeModelParameterSupport(it).available)
        }
        assertEquals(
            ProviderRequestChannel.ANTHROPIC_API,
            ProviderSetting.Claude(baseUrl = "https://api.anthropic.com/v1").requestChannel(),
        )
        val opus46 = resolveClaudeModelParameterSupport("claude-opus-4-6")
        assertFalse(opus46.supportsManualThinking)
        assertTrue(opus46.supportsAdaptiveThinking)
        assertTrue(opus46.supportsSamplingParameters)
    }

    @Test
    fun `current Claude sends supported service tool thinking and schema options`() {
        val body = buildRequest(
            model = Model(
                modelId = "claude-opus-4-8",
                abilities = listOf(ModelAbility.REASONING, ModelAbility.TOOL),
            ),
            reasoningLevel = ReasoningLevel.XHIGH,
            tools = listOf(testTool()),
            options = fullOptions(),
        )

        assertEquals("auto", body["service_tier"]?.jsonPrimitive?.content)
        assertEquals("us", body["inference_geo"]?.jsonPrimitive?.content)
        assertEquals(2, body["stop_sequences"]?.jsonArray?.size)
        assertEquals("any", body["tool_choice"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertEquals(
            true,
            body["tool_choice"]?.jsonObject?.get("disable_parallel_tool_use")?.jsonPrimitive?.boolean,
        )
        assertEquals("adaptive", body["thinking"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertEquals("omitted", body["thinking"]?.jsonObject?.get("display")?.jsonPrimitive?.content)
        assertEquals("xhigh", body["output_config"]?.jsonObject?.get("effort")?.jsonPrimitive?.content)
        assertEquals(
            "json_schema",
            body["output_config"]?.jsonObject?.get("format")?.jsonObject
                ?.get("type")?.jsonPrimitive?.content,
        )
        assertFalse(body.containsKey("top_k"))
    }

    @Test
    fun `Fable 5 keeps adaptive thinking enabled when reasoning is off`() {
        val body = buildRequest(
            model = Model(modelId = "claude-fable-5"),
            reasoningLevel = ReasoningLevel.OFF,
            options = fullOptions(),
        )

        assertEquals("adaptive", body["thinking"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertEquals("omitted", body["thinking"]?.jsonObject?.get("display")?.jsonPrimitive?.content)
    }

    @Test
    fun `Claude 46 sampling is sent only with thinking off`() {
        val body = buildRequest(
            model = Model(
                modelId = "claude-sonnet-4-6",
                abilities = listOf(ModelAbility.REASONING),
            ),
            reasoningLevel = ReasoningLevel.OFF,
            temperature = 0.4f,
            topP = 0.9f,
            options = fullOptions().copy(responseFormat = ClaudeResponseFormat.AUTO),
        )

        assertEquals("disabled", body["thinking"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertEquals(0.4f, body["temperature"]?.jsonPrimitive?.float)
        assertEquals(0.9f, body["top_p"]?.jsonPrimitive?.float)
        assertEquals(40, body["top_k"]?.jsonPrimitive?.int)
    }

    @Test
    fun `Claude 47 and later omit rejected sampling controls`() {
        val body = buildRequest(
            model = Model(modelId = "claude-opus-4-7"),
            reasoningLevel = ReasoningLevel.OFF,
            temperature = 0.4f,
            topP = 0.9f,
            options = fullOptions(),
        )

        assertFalse(body.containsKey("temperature"))
        assertFalse(body.containsKey("top_p"))
        assertFalse(body.containsKey("top_k"))
    }

    @Test
    fun `unsupported service tier and format are not sent`() {
        val opus5 = buildRequest(
            model = Model(modelId = "claude-opus-5"),
            options = fullOptions(),
        )
        val sonnet44 = buildRequest(
            model = Model(modelId = "claude-sonnet-4-4"),
            options = fullOptions(),
        )

        assertFalse(opus5.containsKey("service_tier"))
        assertFalse(
            sonnet44["output_config"]?.jsonObject?.containsKey("format") == true,
        )
    }

    @Test
    fun `non Claude model never receives stored Claude options`() {
        val body = buildRequest(
            model = Model(modelId = "custom-anthropic-model"),
            temperature = 0.4f,
            topP = 0.9f,
            options = fullOptions(),
        )

        listOf(
            "service_tier",
            "inference_geo",
            "stop_sequences",
            "top_k",
            "tool_choice",
            "output_config",
        )
            .forEach { assertFalse("field=$it", body.containsKey(it)) }
    }

    private fun fullOptions() = ClaudeGenerationOptions(
        serviceTier = ClaudeServiceTier.AUTO,
        inferenceGeo = ClaudeInferenceGeo.US,
        parallelToolCalls = ClaudeParallelToolCalls.DISABLED,
        toolChoice = ClaudeToolChoice.ANY,
        stopSequences = listOf("END", "STOP"),
        topK = 40,
        thinkingDisplay = ClaudeThinkingDisplay.OMITTED,
        responseFormat = ClaudeResponseFormat.JSON_SCHEMA,
        responseJsonSchema = """{"type":"object","properties":{"answer":{"type":"string"}},"required":["answer"],"additionalProperties":false}""",
    )

    private fun testTool(): Tool = Tool(
        name = "lookup",
        description = "test",
        parameters = { InputSchema.Obj(properties = JsonObject(emptyMap())) },
        execute = { emptyList() },
    )

    private fun buildRequest(
        model: Model,
        reasoningLevel: ReasoningLevel = ReasoningLevel.OFF,
        temperature: Float? = null,
        topP: Float? = null,
        tools: List<Tool> = emptyList(),
        options: ClaudeGenerationOptions,
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
            ProviderSetting.Claude(baseUrl = "https://api.anthropic.com/v1"),
            listOf(UIMessage.user("hello")),
            TextGenerationParams(
                model = model,
                reasoningLevel = reasoningLevel,
                temperature = temperature,
                topP = topP,
                tools = tools,
                claudeOptions = options,
            ),
            false,
        ) as JsonObject
    }
}
