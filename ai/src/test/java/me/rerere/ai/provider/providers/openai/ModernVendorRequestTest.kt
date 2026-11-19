package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.*
import me.rerere.ai.core.*
import me.rerere.ai.provider.*
import me.rerere.ai.provider.providers.claude.ClaudeProvider
import me.rerere.ai.registry.ModelRegistry
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.util.KeyRoulette
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class ModernVendorRequestTest {
    @Test fun `model matching keeps vendor boundaries and accepts namespaced Kimi aliases`() {
        for (id in listOf("grok3", "shark3", "customglm-5.3"))
            assertNull(id, Model(modelId = id).modernVendorModel())
        for (id in listOf("k3", "kimi-k3", "moonshot/kimi-k3.1-pro-highspeed"))
            assertEquals(id, ModernVendorModel.KIMI_K3, Model(modelId = id).modernVendorModel())
    }

    private fun request(model: Model, level: ReasoningLevel): JsonObject {
        val method = ChatCompletionsAPI::class.java.getDeclaredMethod("buildChatCompletionRequest",
            List::class.java, TextGenerationParams::class.java, ProviderSetting.OpenAI::class.java, Boolean::class.javaPrimitiveType)
        method.isAccessible = true
        val tool = Tool(name = "search_web", description = "Search", parameters = { InputSchema.Obj(JsonObject(emptyMap())) }, execute = { emptyList() })
        return method.invoke(ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default()), listOf(UIMessage.user("Search")),
            TextGenerationParams(model = model, reasoningLevel = level, temperature = .2f, tools = listOf(tool)),
            ProviderSetting.OpenAI(baseUrl = "https://relay.example/v1"), true) as JsonObject
    }

    @Test fun `new model requests constrain effort and preserve relay identifiers`() {
        for ((name, effort) in listOf("MiniMax M3.1 Flash" to "medium", "GLM 5.3 FlashX" to "high", "Kimi K3.1 Pro Highspeed" to "high")) {
            val body = request(Model(modelId = "opaque-42", displayName = name), ReasoningLevel.MEDIUM)
            assertEquals("opaque-42", body["model"]!!.jsonPrimitive.content)
            assertEquals(effort, body["reasoning_effort"]!!.jsonPrimitive.content)
            assertTrue(body["tools"]!!.jsonArray.any { it.jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content == "search_web" })
            if (name.startsWith("Kimi")) {
                assertFalse("temperature" in body)
                assertFalse("thinking" in body)
            }
        }
    }

    @Test fun `legacy disabled thinking is converted to valid minimum effort`() {
        for (id in listOf("MiniMax-M3.1-Flash", "glm-5.3", "glm-5.3-flash", "kimi-k3.1-pro")) {
            val body = request(Model(modelId = id), ReasoningLevel.OFF)
            assertEquals("low", body["reasoning_effort"]!!.jsonPrimitive.content)
            assertEquals(1_000_000, inferContextWindowTokens(id))
        }
    }

    @Test fun `GLM flagship stays text only while Flash variants and MiniMax accept visual input`() {
        assertFalse(Modality.IMAGE in ModelRegistry.MODEL_INPUT_MODALITIES.getData("glm-5.3"))
        for (id in listOf("glm-5.3-flash", "glm-5.3-flashx", "glm-5.3-flashx-legal", "MiniMax-M3.1-Flash", "kimi-k3.1-pro-highspeed", "kimi-k2.7-code", "kimi-k2.7-code-highspeed"))
            assertTrue(id, Modality.IMAGE in ModelRegistry.MODEL_INPUT_MODALITIES.getData(id))
    }

    @Test fun `Kimi Code removes incompatible sampling thinking and forced tool overrides`() {
        val body = buildJsonObject {
            put("temperature", .2); put("top_p", .8); put("n", 2)
            put("thinking", buildJsonObject { put("type", "disabled") })
            put("reasoning_effort", "none")
            put("reasoning", buildJsonObject { put("effort", "none") })
            put("tool_choice", "required")
        }.withModernVendorOptions(TextGenerationParams(model = Model(modelId = "kimi-k2.7-code-highspeed")), "chat")
        assertEquals("auto", body["tool_choice"]!!.jsonPrimitive.content)
        assertFalse(body.keys.any { it in setOf("temperature", "top_p", "n", "thinking", "reasoning", "reasoning_effort") })
        assertEquals(262_144, inferContextWindowTokens("kimi-k2.7-code-highspeed"))
    }

    @Test fun `MiniMax Anthropic request uses adaptive effort without Claude native search`() {
        val method = ClaudeProvider::class.java.getDeclaredMethod("buildMessageRequest",
            ProviderSetting.Claude::class.java, List::class.java, TextGenerationParams::class.java, Boolean::class.javaPrimitiveType)
        method.isAccessible = true
        val model = Model(modelId = "deployment-55", displayName = "MiniMax M3.1 Flash", tools = setOf(BuiltInTools.Search))
        val body = method.invoke(ClaudeProvider(OkHttpClient()), ProviderSetting.Claude(), listOf(UIMessage.user("Hello")),
            TextGenerationParams(model = model, reasoningLevel = ReasoningLevel.XHIGH), false) as JsonObject
        assertEquals("deployment-55", body["model"]!!.jsonPrimitive.content)
        assertEquals("adaptive", body["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("xhigh", body["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertFalse((body["tools"] as? JsonArray).orEmpty().any { it.jsonObject["type"]?.jsonPrimitive?.content?.startsWith("web_search") == true })
        assertFalse("reasoning_effort" in body)
    }
}
