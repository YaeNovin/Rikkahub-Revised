package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.ClaudeGenerationOptions
import me.rerere.ai.provider.ClaudeInferenceGeo
import me.rerere.ai.provider.ClaudeParallelToolCalls
import me.rerere.ai.provider.ClaudeResponseFormat
import me.rerere.ai.provider.ClaudeServiceTier
import me.rerere.ai.provider.ClaudeToolChoice
import me.rerere.ai.provider.GeminiGenerationOptions
import me.rerere.ai.provider.GeminiMediaResolution
import me.rerere.ai.provider.GeminiResponseMimeType
import me.rerere.ai.provider.GeminiSafetySettings
import me.rerere.ai.provider.GeminiSafetyThreshold
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.OpenAIParallelToolCalls
import me.rerere.ai.provider.OpenAIToolChoice
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.util.KeyRoulette
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CompatibleModelRequestOptionsTest {
    private val api = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())

    @Test
    fun `maps Gemini common options onto OpenAI chat completions`() {
        val body = buildRequest(
            model = Model(modelId = "opaque", displayName = "Gemini compatible"),
            params = { model ->
                TextGenerationParams(
                    model = model,
                    geminiOptions = GeminiGenerationOptions(
                        includeThoughts = false,
                        mediaResolution = GeminiMediaResolution.HIGH,
                        seed = 7,
                        stopSequences = listOf("ONE", "TWO", "THREE", "FOUR", "FIVE", "SIX"),
                        responseMimeType = GeminiResponseMimeType.JSON,
                        responseJsonSchema = """{"type":"object"}""",
                        presencePenalty = 0.2f,
                        frequencyPenalty = 0.3f,
                        safetySettings = GeminiSafetySettings(
                            harassment = GeminiSafetyThreshold.BLOCK_NONE,
                        ),
                    ),
                )
            },
        )

        assertEquals(7, body["seed"]?.jsonPrimitive?.int)
        assertEquals(
            listOf("ONE", "TWO", "THREE", "FOUR", "FIVE"),
            body["stop"]?.jsonArray?.map { it.jsonPrimitive.content },
        )
        assertEquals(0.2f, body["presence_penalty"]?.jsonPrimitive?.float)
        assertEquals(0.3f, body["frequency_penalty"]?.jsonPrimitive?.float)
        assertEquals("json_schema", body["response_format"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertFalse(body.containsKey("mediaResolution"))
        assertFalse(body.containsKey("safetySettings"))
        assertFalse(body.containsKey("includeThoughts"))
    }

    @Test
    fun `maps Claude common options and omits Anthropic-only fields`() {
        val tool = Tool(
            name = "lookup",
            description = "lookup",
            parameters = { InputSchema.Obj(properties = JsonObject(emptyMap())) },
            execute = { emptyList() },
        )
        val body = buildRequest(
            model = Model(
                modelId = "vendor/claude-custom",
                abilities = listOf(ModelAbility.TOOL),
            ),
            params = { model ->
                TextGenerationParams(
                    model = model,
                    tools = listOf(tool),
                    claudeOptions = ClaudeGenerationOptions(
                        serviceTier = ClaudeServiceTier.AUTO,
                        inferenceGeo = ClaudeInferenceGeo.US,
                        parallelToolCalls = ClaudeParallelToolCalls.DISABLED,
                        toolChoice = ClaudeToolChoice.ANY,
                        stopSequences = listOf("STOP"),
                        topK = 40,
                        responseFormat = ClaudeResponseFormat.JSON_SCHEMA,
                        responseJsonSchema = """{"type":"object"}""",
                    ),
                )
            },
        )

        assertEquals("STOP", body["stop"]?.jsonArray?.single()?.jsonPrimitive?.content)
        assertEquals("required", body["tool_choice"]?.jsonPrimitive?.content)
        assertEquals(false, body["parallel_tool_calls"]?.jsonPrimitive?.boolean)
        assertEquals("json_schema", body["response_format"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertFalse(body.containsKey("service_tier"))
        assertFalse(body.containsKey("inference_geo"))
        assertFalse(body.containsKey("top_k"))
        assertFalse(body.containsKey("thinking"))
    }

    @Test
    fun `unknown OpenAI compatible model receives protocol level tool options`() {
        val tool = Tool(
            name = "lookup",
            description = "lookup",
            parameters = { InputSchema.Obj(properties = JsonObject(emptyMap())) },
            execute = { emptyList() },
        )
        val body = buildRequest(
            model = Model(modelId = "vendor-chat", abilities = listOf(ModelAbility.TOOL)),
            params = { model ->
                TextGenerationParams(
                    model = model,
                    tools = listOf(tool),
                    openAIOptions = me.rerere.ai.provider.OpenAIGenerationOptions(
                        parallelToolCalls = OpenAIParallelToolCalls.DISABLED,
                        toolChoice = OpenAIToolChoice.REQUIRED,
                    ),
                )
            },
        )

        assertEquals(false, body["parallel_tool_calls"]?.jsonPrimitive?.boolean)
        assertEquals("required", body["tool_choice"]?.jsonPrimitive?.content)
    }

    private fun buildRequest(
        model: Model,
        params: (Model) -> TextGenerationParams,
    ): JsonObject {
        val method = ChatCompletionsAPI::class.java.getDeclaredMethod(
            "buildChatCompletionRequest",
            List::class.java,
            TextGenerationParams::class.java,
            ProviderSetting.OpenAI::class.java,
            Boolean::class.javaPrimitiveType,
        ).apply { isAccessible = true }
        return method.invoke(
            api,
            listOf(UIMessage.user("hello")),
            params(model),
            ProviderSetting.OpenAI(baseUrl = "https://example.test/v1"),
            true,
        ) as JsonObject
    }
}
