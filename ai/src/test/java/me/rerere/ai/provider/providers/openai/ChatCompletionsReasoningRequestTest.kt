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

        val openAi56 = buildRequest("https://api.openai.com/v1", "gpt-5.6-sol")
        assertEquals("max", openAi56["reasoning_effort"]?.jsonPrimitive?.content)

        val deepSeek = buildRequest("https://api.deepseek.com/v1", "deepseek-v4")
        assertEquals("max", deepSeek["reasoning_effort"]?.jsonPrimitive?.content)

        val dashScope = buildRequest("https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen3")
        assertTrue(dashScope["enable_thinking"]?.jsonPrimitive?.content?.toBoolean() == true)
        assertEquals(32_000, dashScope["thinking_budget"]?.jsonPrimitive?.int)
    }

    @Test
    fun `Alibaba workspace endpoints use model-specific thinking controls`() {
        val qwen = buildRequest(
            "https://workspace.cn-beijing.maas.aliyuncs.com/compatible-mode/v1",
            "Qwen/Qwen3.7-Plus",
            ReasoningLevel.HIGH,
        )
        assertEquals("true", qwen["enable_thinking"]?.jsonPrimitive?.content)
        assertEquals(8_000, qwen["thinking_budget"]?.jsonPrimitive?.int)

        val qwenOff = buildRequest(
            "https://coding.dashscope.aliyuncs.com/v1",
            "qwen3.7-plus",
            ReasoningLevel.OFF,
        )
        assertEquals("false", qwenOff["enable_thinking"]?.jsonPrimitive?.content)
        assertFalse(qwenOff.containsKey("thinking_budget"))

        listOf("qwen3-30b-a3b-thinking-2507", "qwen3.7-max-preview").forEach { modelId ->
            val thinkingOnly = buildRequest(
                "https://dashscope.aliyuncs.com/compatible-mode/v1",
                modelId,
                ReasoningLevel.OFF,
            )
            assertFalse("model=$modelId", thinkingOnly.containsKey("enable_thinking"))
            assertFalse("model=$modelId", thinkingOnly.containsKey("thinking_budget"))
        }

        val deepSeek = buildRequest(
            "https://workspace.ap-southeast-1.maas.aliyuncs.com/compatible-mode/v1",
            "deepseek-v4-pro",
            ReasoningLevel.XHIGH,
        )
        assertEquals("true", deepSeek["enable_thinking"]?.jsonPrimitive?.content)
        assertEquals("high", deepSeek["reasoning_effort"]?.jsonPrimitive?.content)
        assertFalse(deepSeek.containsKey("thinking_budget"))
    }

    @Test
    fun `DeepSeek official endpoint normalizes unsupported effort aliases`() {
        val medium = buildRequest(
            "https://api.deepseek.com/v1",
            "deepseek-v4-flash",
            ReasoningLevel.MEDIUM,
        )
        val xhigh = buildRequest(
            "https://api.deepseek.com/v1",
            "deepseek-v4-pro",
            ReasoningLevel.XHIGH,
        )
        val max = buildRequest(
            "https://api.deepseek.com/v1",
            "deepseek-v4-pro",
            ReasoningLevel.MAX,
        )
        val off = buildRequest(
            "https://api.deepseek.com/v1",
            "deepseek-v4-pro",
            ReasoningLevel.OFF,
        )

        assertEquals("high", medium["reasoning_effort"]?.jsonPrimitive?.content)
        assertEquals("high", xhigh["reasoning_effort"]?.jsonPrimitive?.content)
        assertEquals("max", max["reasoning_effort"]?.jsonPrimitive?.content)
        assertEquals("disabled", off["thinking"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertFalse(off.containsKey("reasoning_effort"))
    }

    @Test
    fun `Volcengine regional endpoints only send effort to documented Doubao models`() {
        val supported = buildRequest(
            "https://ark.ap-southeast-1.volces.com/api/v3",
            "doubao-seed-1-6-lite-251015",
            ReasoningLevel.MAX,
        )
        assertEquals("enabled", supported["thinking"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertEquals("high", supported["reasoning_effort"]?.jsonPrimitive?.content)

        val unsupported = buildRequest(
            "https://ark.cn-beijing.volces.com/api/v3",
            "doubao-seed-1-8-251228",
            ReasoningLevel.MAX,
        )
        assertEquals("enabled", unsupported["thinking"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertFalse(unsupported.containsKey("reasoning_effort"))
    }

    @Test
    fun `compatible providers map reasoning controls by model family`() {
        val qwen = buildRequest(
            "https://models.example.com/v1",
            "Qwen/Qwen3-32B",
            ReasoningLevel.HIGH,
        )
        assertEquals("true", qwen["enable_thinking"]?.jsonPrimitive?.content)
        assertEquals(8_000, qwen["thinking_budget"]?.jsonPrimitive?.int)

        val qwen38 = buildRequest(
            "https://models.example.com/v1",
            "Qwen3.8-Max",
            ReasoningLevel.XHIGH,
        )
        assertEquals("xhigh", qwen38["reasoning_effort"]?.jsonPrimitive?.content)

        val deepSeek = buildRequest(
            "https://models.example.com/v1",
            "deepseek-ai/DeepSeek-V4-Pro",
            ReasoningLevel.MEDIUM,
        )
        assertEquals("enabled", deepSeek["thinking"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertEquals("high", deepSeek["reasoning_effort"]?.jsonPrimitive?.content)

        val doubao = buildRequest("https://models.example.com/v1", "doubao-seed-1-6-lite")
        assertFalse(doubao.containsKey("reasoning_effort"))
        assertFalse(doubao.containsKey("thinking"))
    }

    @Test
    fun `compatible OpenAI aliases preserve model specific maximum`() {
        val gpt54 = buildRequest(
            "https://models.example.com/v1",
            "openai/GPT5.4",
            ReasoningLevel.MAX,
        )
        val gpt56 = buildRequest(
            "https://models.example.com/v1",
            "deployment-56",
            ReasoningLevel.MAX,
            displayName = "GPT-5.6 Sol",
        )

        assertEquals("xhigh", gpt54["reasoning_effort"]?.jsonPrimitive?.content)
        assertEquals("max", gpt56["reasoning_effort"]?.jsonPrimitive?.content)
    }

    @Test
    fun `persisted compatible model without abilities recovers reasoning support`() {
        val body = buildRequest(
            baseUrl = "https://models.example.com/v1",
            modelId = "deployment-54",
            reasoningLevel = ReasoningLevel.XHIGH,
            displayName = "GPT-5.4",
            abilities = emptyList(),
        )

        assertEquals("xhigh", body["reasoning_effort"]?.jsonPrimitive?.content)
        assertEquals("deployment-54", body["model"]?.jsonPrimitive?.content)
    }

    @Test
    fun `compatible Gemini and Claude aliases use model specific levels`() {
        val geminiPro = buildRequest(
            "https://models.example.com/v1",
            "deployment-gemini",
            ReasoningLevel.MEDIUM,
            displayName = "Gemini 3 Pro",
        )
        val claude46 = buildRequest(
            "https://models.example.com/v1",
            "deployment-claude",
            ReasoningLevel.XHIGH,
            displayName = "Claude Sonnet 4.6",
        )

        assertEquals("high", geminiPro["reasoning_effort"]?.jsonPrimitive?.content)
        assertEquals("high", claude46["reasoning_effort"]?.jsonPrimitive?.content)
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

    private fun buildRequest(
        baseUrl: String,
        modelId: String,
        reasoningLevel: ReasoningLevel = ReasoningLevel.MAX,
        displayName: String = "",
        abilities: List<ModelAbility> = listOf(ModelAbility.REASONING),
    ): JsonObject {
        val method = ChatCompletionsAPI::class.java.getDeclaredMethod(
            "buildChatCompletionRequest",
            List::class.java,
            TextGenerationParams::class.java,
            ProviderSetting.OpenAI::class.java,
            Boolean::class.javaPrimitiveType,
        )
        method.isAccessible = true
        val params = TextGenerationParams(
            model = Model(
                modelId = modelId,
                displayName = displayName,
                abilities = abilities,
            ),
            reasoningLevel = reasoningLevel,
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
