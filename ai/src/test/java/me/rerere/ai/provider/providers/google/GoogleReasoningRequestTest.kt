package me.rerere.ai.provider.providers.google

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class GoogleReasoningRequestTest {
    private lateinit var provider: GoogleProvider

    @Before
    fun setUp() {
        provider = GoogleProvider(OkHttpClient())
    }

    @Test
    fun `max maps to the highest Gemini 3 thinking level`() {
        val thinking = buildRequest("gemini-3-pro-preview")["generationConfig"]
            ?.jsonObject?.get("thinkingConfig")?.jsonObject

        assertEquals("high", thinking?.get("thinkingLevel")?.jsonPrimitive?.content)
    }

    @Test
    fun `minimal maps to Gemini Flash minimal thinking level`() {
        val thinking = buildRequest(
            modelId = "gemini-3-flash-preview",
            reasoningLevel = ReasoningLevel.MINIMAL,
        )["generationConfig"]?.jsonObject?.get("thinkingConfig")?.jsonObject

        assertEquals("minimal", thinking?.get("thinkingLevel")?.jsonPrimitive?.content)
    }

    @Test
    fun `max budget respects Gemini model limits`() {
        val pro = buildRequest("gemini-2.5-pro")["generationConfig"]
            ?.jsonObject?.get("thinkingConfig")?.jsonObject
        val flash = buildRequest("gemini-2.5-flash")["generationConfig"]
            ?.jsonObject?.get("thinkingConfig")?.jsonObject

        assertEquals(32_000, pro?.get("thinkingBudget")?.jsonPrimitive?.int)
        assertEquals(24_576, flash?.get("thinkingBudget")?.jsonPrimitive?.int)
    }

    @Test
    fun `unsupported persisted Gemini 3 levels are coerced before request`() {
        val thinking = buildRequest(
            modelId = "gemini-3-pro",
            reasoningLevel = ReasoningLevel.MEDIUM,
        )["generationConfig"]?.jsonObject?.get("thinkingConfig")?.jsonObject

        assertEquals("high", thinking?.get("thinkingLevel")?.jsonPrimitive?.content)
    }

    @Test
    fun `persisted Gemini model without abilities still sends thinking level`() {
        val thinking = buildRequest(
            modelId = "Gemini35-Flash",
            reasoningLevel = ReasoningLevel.MINIMAL,
            abilities = emptyList(),
        )["generationConfig"]?.jsonObject?.get("thinkingConfig")?.jsonObject

        assertEquals("minimal", thinking?.get("thinkingLevel")?.jsonPrimitive?.content)
    }

    private fun buildRequest(
        modelId: String,
        reasoningLevel: ReasoningLevel = ReasoningLevel.MAX,
        abilities: List<ModelAbility> = listOf(ModelAbility.REASONING),
    ): JsonObject {
        val method = GoogleProvider::class.java.getDeclaredMethod(
            "buildCompletionRequestBody",
            List::class.java,
            TextGenerationParams::class.java,
        )
        method.isAccessible = true
        return method.invoke(
            provider,
            listOf(UIMessage.user("hello")),
            TextGenerationParams(
                model = Model(modelId = modelId, abilities = abilities),
                reasoningLevel = reasoningLevel,
            ),
        ) as JsonObject
    }
}
