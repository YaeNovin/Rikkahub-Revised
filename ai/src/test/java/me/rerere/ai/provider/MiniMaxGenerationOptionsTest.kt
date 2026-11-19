package me.rerere.ai.provider

import kotlinx.serialization.json.*
import me.rerere.ai.core.ReasoningLevel
import org.junit.Assert.*
import org.junit.Test

class MiniMaxGenerationOptionsTest {
    @Test fun `M3 maps typed options to both native contracts and retains opaque deployment id`() {
        val model = Model(modelId = "deployment-1", displayName = "MiniMax M3")
        val params = TextGenerationParams(model, miniMaxOptions = MiniMaxGenerationOptions(
            thinking = MiniMaxThinking.ADAPTIVE, serviceTier = MiniMaxServiceTier.PRIORITY, reasoningSplit = true))
        for (protocol in listOf("chat", "anthropic")) {
            val body = buildJsonObject { put("model", model.modelId); put("temperature", .4) }.withMiniMaxOptions(params, protocol)
            assertEquals("deployment-1", body["model"]!!.jsonPrimitive.content)
            assertEquals("adaptive", body["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
            assertEquals("priority", body["service_tier"]!!.jsonPrimitive.content)
            assertEquals(protocol == "chat", "reasoning_split" in body)
            assertFalse("temperature" in body)
        }
    }

    @Test fun `M31 cannot disable reasoning or send unsupported service tier and split formats`() {
        val params = TextGenerationParams(Model(modelId = "MiniMax-M3.1-Flash"), reasoningLevel = ReasoningLevel.OFF,
            miniMaxOptions = MiniMaxGenerationOptions(MiniMaxThinking.DISABLED, MiniMaxServiceTier.PRIORITY, false))
        val body = buildJsonObject { put("service_tier", "priority"); put("reasoning_split", false) }
            .withModernVendorOptions(params, "chat").withMiniMaxOptions(params, "chat")
        assertEquals("low", body["reasoning_effort"]!!.jsonPrimitive.content)
        assertFalse("thinking" in body); assertFalse("service_tier" in body); assertFalse("reasoning_split" in body)
    }

    @Test fun `typed MiniMax settings survive serialization and do not affect unrelated models`() {
        val value = MiniMaxGenerationOptions(MiniMaxThinking.DISABLED, MiniMaxServiceTier.STANDARD, false)
        assertEquals(value, Json.decodeFromString<MiniMaxGenerationOptions>(Json.encodeToString(value)))
        val original = buildJsonObject { put("temperature", .3) }
        assertSame(original, original.withMiniMaxOptions(TextGenerationParams(Model(modelId = "gpt-6.1-sol"), miniMaxOptions = value), "chat"))
    }
}
