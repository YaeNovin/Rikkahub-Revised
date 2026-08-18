package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
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

class ChatCompletionsReasoningRequestTest {
    private lateinit var api: ChatCompletionsAPI

    @Before
    fun setUp() {
        api = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())
    }

    @Test
    fun `max is capped to each OpenAI-compatible provider`() {
        val openRouter = buildRequest("https://openrouter.ai/api/v1", "test-model")
        assertEquals("xhigh", openRouter["reasoning"]?.jsonObject?.get("effort")?.jsonPrimitive?.content)

        val openAi = buildRequest("https://api.openai.com/v1", "gpt-5")
        assertEquals("high", openAi["reasoning_effort"]?.jsonPrimitive?.content)

        val deepSeek = buildRequest("https://api.deepseek.com/v1", "deepseek-v4")
        assertEquals("max", deepSeek["reasoning_effort"]?.jsonPrimitive?.content)

        val dashScope = buildRequest("https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen3")
        assertTrue(dashScope["enable_thinking"]?.jsonPrimitive?.content?.toBoolean() == true)
        assertEquals(32_000, dashScope["thinking_budget"]?.jsonPrimitive?.int)
    }

    @Test
    fun `siliconflow uses its supported thinking toggle without unsupported effort`() {
        val body = buildRequest(
            baseUrl = "https://api.siliconflow.cn/v1",
            modelId = "Qwen/Qwen3.5-397B-A17B",
        )

        assertTrue(body["enable_thinking"]?.jsonPrimitive?.content?.toBoolean() == true)
        assertFalse(body.containsKey("reasoning_effort"))
        assertFalse(body.containsKey("thinking_budget"))
    }

    private fun buildRequest(baseUrl: String, modelId: String): JsonObject {
        val method = ChatCompletionsAPI::class.java.getDeclaredMethod(
            "buildChatCompletionRequest",
            List::class.java,
            TextGenerationParams::class.java,
            ProviderSetting.OpenAI::class.java,
            Boolean::class.javaPrimitiveType,
        )
        method.isAccessible = true
        val params = TextGenerationParams(
            model = Model(modelId = modelId, abilities = listOf(ModelAbility.REASONING)),
            reasoningLevel = ReasoningLevel.MAX,
        )
        return method.invoke(
            api,
            listOf(UIMessage.user("hello")),
            params,
            ProviderSetting.OpenAI(baseUrl = baseUrl),
            false,
        ) as JsonObject
    }
}
