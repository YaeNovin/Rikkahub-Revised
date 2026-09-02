package me.rerere.ai.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelParameterFamilyTest {
    @Test
    fun `recognizes vendor aliases from ids and display names`() {
        assertEquals(
            ModelParameterFamily.CLAUDE,
            Model(modelId = "openrouter/anthropic.claude-sonnet", displayName = "Sonnet").inferParameterFamily(),
        )
        assertEquals(
            ModelParameterFamily.GEMINI,
            Model(modelId = "third-party-model", displayName = "Google Gemini Pro").inferParameterFamily(),
        )
        assertEquals(
            ModelParameterFamily.QWEN,
            Model(modelId = "vendor/QWQ-Plus").inferParameterFamily(),
        )
        assertEquals(ModelParameterFamily.QWEN, Model(modelId = "Qwen3.8-Max").inferParameterFamily())
        assertEquals(ModelParameterFamily.GEMINI, Model(modelId = "Gemini3.5-Flash").inferParameterFamily())
        assertEquals(ModelParameterFamily.GEMINI, Model(modelId = "Gemini35-Flash").inferParameterFamily())
        assertEquals(ModelParameterFamily.CLAUDE, Model(modelId = "Claude3.7-Sonnet").inferParameterFamily())
        assertEquals(ModelParameterFamily.CLAUDE, Model(modelId = "Claude37-Sonnet").inferParameterFamily())
        assertEquals(ModelParameterFamily.DEEPSEEK, Model(modelId = "DeepSeekR1").inferParameterFamily())
        assertEquals(ModelParameterFamily.OPENAI, Model(modelId = "GPT5.4").inferParameterFamily())
        assertEquals(ModelParameterFamily.OPENAI, Model(modelId = "GPT54").inferParameterFamily())
        assertEquals(ModelParameterFamily.QWEN, Model(modelId = "Qwen38-Max").inferParameterFamily())
    }

    @Test
    fun `OpenAI compatible protocol falls back to generic parameters`() {
        val provider = ProviderSetting.OpenAI(baseUrl = "https://example.test/v1")

        assertEquals(
            ModelParameterFamily.OPENAI,
            Model(modelId = "vendor-chat-model").resolveParameterFamily(provider),
        )
        assertEquals(
            ModelParameterFamily.CLAUDE,
            Model(modelId = "claude-custom-alias").resolveParameterFamily(provider),
        )
        assertEquals(
            ModelParameterFamily.GEMINI,
            Model(modelId = "gemini-custom-alias").resolveParameterFamily(provider),
        )
    }

    @Test
    fun `native provider protocol is used when identifiers are opaque`() {
        assertEquals(
            ModelParameterFamily.GEMINI,
            Model(modelId = "opaque-model").resolveParameterFamily(ProviderSetting.Google()),
        )
        assertEquals(
            ModelParameterFamily.CLAUDE,
            Model(modelId = "opaque-model").resolveParameterFamily(ProviderSetting.Claude()),
        )
    }

    @Test
    fun `opaque third party ids use display name only for capability detection`() {
        val model = Model(modelId = "deployment-42", displayName = "Claude Sonnet 4.6")

        assertEquals(ModelParameterFamily.CLAUDE, model.inferParameterFamily())
        assertEquals("Claude Sonnet 4.6", model.parameterModelId())
        assertEquals("deployment-42", model.modelId)
    }

    @Test
    fun `persisted models recover reasoning capability from id or display name`() {
        assertTrue(Model(modelId = "GPT54").supportsReasoningCapability())
        assertTrue(
            Model(
                modelId = "deployment-42",
                displayName = "Gemini 3.5 Flash",
            ).supportsReasoningCapability()
        )
        assertTrue(
            Model(
                modelId = "deployment-claude",
                displayName = "Claude 3.7 Sonnet",
            ).supportsReasoningCapability()
        )
        assertFalse(Model(modelId = "gpt-4o").supportsReasoningCapability())
    }
}
