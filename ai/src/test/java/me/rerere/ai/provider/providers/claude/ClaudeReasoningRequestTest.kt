package me.rerere.ai.provider.providers.claude

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
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

class ClaudeReasoningRequestTest {
    private lateinit var provider: ClaudeProvider

    @Before
    fun setUp() {
        provider = ClaudeProvider(OkHttpClient())
    }

    @Test
    fun `max effort is sent only to supported Anthropic models`() {
        val opus = buildRequest("https://api.anthropic.com/v1", "claude-opus-4-6")
        val sonnet = buildRequest("https://api.anthropic.com/v1", "claude-sonnet-4-6")

        assertEquals("max", opus["output_config"]?.jsonObject?.get("effort")?.jsonPrimitive?.content)
        assertEquals("high", sonnet["output_config"]?.jsonObject?.get("effort")?.jsonPrimitive?.content)
    }

    @Test
    fun `xhigh is downgraded because Anthropic does not accept that literal`() {
        val body = buildRequest(
            baseUrl = "https://api.anthropic.com/v1",
            modelId = "claude-opus-4-6",
            reasoningLevel = ReasoningLevel.XHIGH,
        )

        assertEquals("high", body["output_config"]?.jsonObject?.get("effort")?.jsonPrimitive?.content)
    }

    @Test
    fun `MiniMax Anthropic endpoint uses budget thinking`() {
        val body = buildRequest("https://api.minimaxi.com/anthropic/v1", "MiniMax-M2.5")
        val thinking = body["thinking"]?.jsonObject

        assertEquals("enabled", thinking?.get("type")?.jsonPrimitive?.content)
        assertEquals(32_000, thinking?.get("budget_tokens")?.jsonPrimitive?.int)
        assertFalse(body.containsKey("output_config"))
    }

    private fun buildRequest(
        baseUrl: String,
        modelId: String,
        reasoningLevel: ReasoningLevel = ReasoningLevel.MAX,
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
            ProviderSetting.Claude(baseUrl = baseUrl),
            listOf(UIMessage.user("hello")),
            TextGenerationParams(
                model = Model(modelId = modelId, abilities = listOf(ModelAbility.REASONING)),
                reasoningLevel = reasoningLevel,
            ),
            false,
        ) as JsonObject
    }
}
