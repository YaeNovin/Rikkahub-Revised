package me.rerere.ai.provider.providers.google

import kotlinx.serialization.json.*
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.*
import me.rerere.ai.provider.providers.openai.ChatCompletionsAPI
import me.rerere.ai.registry.ModelRegistry
import me.rerere.ai.ui.UIMessage
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class GeminiHydrogenCarbonTest {
    private fun native(model: Model, level: ReasoningLevel): JsonObject {
        val method = GoogleProvider::class.java.getDeclaredMethod("buildCompletionRequestBody", List::class.java, TextGenerationParams::class.java).apply { isAccessible = true }
        return method.invoke(GoogleProvider(OkHttpClient()), listOf(UIMessage.user("fixture")), TextGenerationParams(model,
            reasoningLevel = level, maxTokens = 131072, geminiOptions = GeminiGenerationOptions(responseMimeType = GeminiResponseMimeType.JSON),
            customBody = listOf(CustomBody("cachedContent", JsonPrimitive("old-cache"))))) as JsonObject
    }

    @Test fun `Hydrogen default and xhigh differ from Carbon and opaque ids remain unchanged`() {
        for ((name, default) in listOf("Gemini 4 Hydrogen" to "high", "Gemini 4 Carbon" to "medium")) {
            val model = Model(modelId = "opaque-deployment", displayName = name)
            val body = native(model, ReasoningLevel.AUTO)
            val config = body["generationConfig"]!!.jsonObject
            assertEquals(default, config["thinkingConfig"]!!.jsonObject["thinkingLevel"]!!.jsonPrimitive.content)
            assertEquals(131072, config["maxOutputTokens"]!!.jsonPrimitive.int)
            assertEquals("application/json", config["responseMimeType"]!!.jsonPrimitive.content)
            assertFalse("cachedContent" in body)
            assertTrue(ModelAbility.TOOL in ModelRegistry.MODEL_ABILITIES.getData(model))
            assertTrue(Modality.IMAGE in ModelRegistry.MODEL_INPUT_MODALITIES.getData(model))
            assertEquals(1048576, inferContextWindowTokens(model.parameterModelId()))
        }
        val hydrogen = Model(modelId = "gemini-4-hydrogen")
        assertEquals("xhigh", native(hydrogen, ReasoningLevel.XHIGH)["generationConfig"]!!.jsonObject["thinkingConfig"]!!.jsonObject["thinkingLevel"]!!.jsonPrimitive.content)
        assertTrue(ReasoningLevel.XHIGH in resolveReasoningLevelSupport(hydrogen, ProviderSetting.Google()).levels)
        assertFalse(ReasoningLevel.XHIGH in resolveReasoningLevelSupport(Model(modelId = "gemini-4-carbon"), ProviderSetting.Google()).levels)
    }

    @Test fun `compatible Hydrogen sends xhigh and strips deprecated sampling even after custom overrides`() {
        val method = ChatCompletionsAPI::class.java.getDeclaredMethod("buildChatCompletionRequest", List::class.java,
            TextGenerationParams::class.java, ProviderSetting.OpenAI::class.java, Boolean::class.javaPrimitiveType).apply { isAccessible = true }
        val body = method.invoke(ChatCompletionsAPI(OkHttpClient(), me.rerere.ai.util.KeyRoulette.default()), listOf(UIMessage.user("fixture")),
            TextGenerationParams(Model(modelId = "opaque-42", displayName = "Gemini 4 Hydrogen"), reasoningLevel = ReasoningLevel.XHIGH,
                temperature = .5f, customBody = listOf(CustomBody("top_k", JsonPrimitive(12)))), ProviderSetting.OpenAI(), false) as JsonObject
        assertEquals("opaque-42", body["model"]!!.jsonPrimitive.content)
        assertEquals("xhigh", body["reasoning_effort"]!!.jsonPrimitive.content)
        assertFalse("temperature" in body); assertFalse("top_k" in body)
    }
}
