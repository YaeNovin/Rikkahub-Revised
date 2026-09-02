package me.rerere.ai.provider

import me.rerere.ai.core.ReasoningLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReasoningLevelSupportTest {
    @Test
    fun `OpenAI model range follows the model maximum`() {
        val support = resolveReasoningLevelSupport(
            model = reasoningModel("gpt-5.6-sol"),
            provider = ProviderSetting.OpenAI(baseUrl = "https://api.openai.com/v1"),
        )

        assertEquals(ReasoningLevel.MAX, support.levels.last())
        assertTrue(ReasoningLevel.OFF in support.levels)
        assertFalse(support.compatibleEndpoint)
    }

    @Test
    fun `OpenAI ranges are selected by model on official and compatible endpoints`() {
        val official = ProviderSetting.OpenAI(baseUrl = "https://api.openai.com/v1")
        val compatible = ProviderSetting.OpenAI(baseUrl = "https://gateway.example/v1")

        assertEquals(
            listOf(
                ReasoningLevel.AUTO,
                ReasoningLevel.MINIMAL,
                ReasoningLevel.LOW,
                ReasoningLevel.MEDIUM,
                ReasoningLevel.HIGH,
            ),
            resolveReasoningLevelSupport(reasoningModel("gpt-5"), official).levels,
        )
        assertEquals(
            listOf(
                ReasoningLevel.OFF,
                ReasoningLevel.AUTO,
                ReasoningLevel.LOW,
                ReasoningLevel.MEDIUM,
                ReasoningLevel.HIGH,
            ),
            resolveReasoningLevelSupport(reasoningModel("gpt-5.1"), compatible).levels,
        )
        assertEquals(
            ReasoningLevel.XHIGH,
            resolveReasoningLevelSupport(reasoningModel("GPT5.4"), compatible).levels.last(),
        )
        assertEquals(
            ReasoningLevel.XHIGH,
            resolveReasoningLevelSupport(reasoningModel("GPT54"), compatible).levels.last(),
        )
    }

    @Test
    fun `Gemini 3_7 Flash exposes only official thinking levels`() {
        val support = resolveReasoningLevelSupport(
            model = reasoningModel("gemini-3.7-flash"),
            provider = ProviderSetting.Google(),
        )

        assertEquals(
            listOf(
                ReasoningLevel.AUTO,
                ReasoningLevel.LOW,
                ReasoningLevel.MEDIUM,
                ReasoningLevel.HIGH,
            ),
            support.levels,
        )
        assertEquals(ReasoningLevel.HIGH, support.coerce(ReasoningLevel.MAX))
    }

    @Test
    fun `Gemini Pro and Flash expose their own documented levels through compatible providers`() {
        val provider = ProviderSetting.OpenAI(baseUrl = "https://gateway.example/v1")
        val pro = resolveReasoningLevelSupport(reasoningModel("google/gemini-3-pro-preview"), provider)
        val flash = resolveReasoningLevelSupport(reasoningModel("gemini-3-flash-preview"), provider)

        assertEquals(
            listOf(ReasoningLevel.AUTO, ReasoningLevel.LOW, ReasoningLevel.HIGH),
            pro.levels,
        )
        assertTrue(ReasoningLevel.MINIMAL in flash.levels)
        assertTrue(pro.compatibleEndpoint)
    }

    @Test
    fun `OpenRouter range is the intersection of model and channel support`() {
        val provider = ProviderSetting.OpenAI(baseUrl = "https://openrouter.ai/api/v1")
        val geminiPro = resolveReasoningLevelSupport(reasoningModel("gemini-3-pro-preview"), provider)
        val gpt56 = resolveReasoningLevelSupport(reasoningModel("gpt-5.6-sol"), provider)

        assertEquals(
            listOf(ReasoningLevel.AUTO, ReasoningLevel.LOW, ReasoningLevel.HIGH),
            geminiPro.levels,
        )
        assertFalse(ReasoningLevel.MAX in gpt56.levels)
        assertEquals(ReasoningLevel.XHIGH, gpt56.levels.last())
    }

    @Test
    fun `Anthropic effort range distinguishes xhigh support`() {
        val sonnet46 = resolveReasoningLevelSupport(
            model = reasoningModel("claude-sonnet-4-6"),
            provider = ProviderSetting.Claude(),
        )
        val sonnet5 = resolveReasoningLevelSupport(
            model = reasoningModel("claude-sonnet-5"),
            provider = ProviderSetting.Claude(),
        )

        assertFalse(ReasoningLevel.XHIGH in sonnet46.levels)
        assertTrue(ReasoningLevel.MAX in sonnet46.levels)
        assertTrue(ReasoningLevel.XHIGH in sonnet5.levels)
        assertTrue(ReasoningLevel.MAX in sonnet5.levels)
    }

    @Test
    fun `DeepSeek V4 hides aliases that the API maps to another effort`() {
        val support = resolveReasoningLevelSupport(
            model = reasoningModel("deepseek-v4-pro"),
            provider = ProviderSetting.OpenAI(baseUrl = "https://api.deepseek.com/v1"),
        )

        assertFalse(ReasoningLevel.MEDIUM in support.levels)
        assertFalse(ReasoningLevel.XHIGH in support.levels)
        assertEquals(ReasoningLevel.HIGH, support.coerce(ReasoningLevel.MEDIUM))
        assertEquals(ReasoningLevel.HIGH, support.coerce(ReasoningLevel.XHIGH))
    }

    @Test
    fun `Qwen compatible endpoint keeps model supported depth controls`() {
        val qwen35 = resolveReasoningLevelSupport(
            model = reasoningModel("qwen3.5-72b"),
            provider = ProviderSetting.OpenAI(baseUrl = "https://compatible.example/v1"),
        )
        val qwen38 = resolveReasoningLevelSupport(
            model = reasoningModel("qwen3.8-max"),
            provider = ProviderSetting.OpenAI(baseUrl = "https://compatible.example/v1"),
        )

        assertTrue(ReasoningLevel.OFF in qwen35.levels)
        assertTrue(ReasoningLevel.MAX in qwen35.levels)
        assertEquals(
            listOf(
                ReasoningLevel.AUTO,
                ReasoningLevel.LOW,
                ReasoningLevel.MEDIUM,
                ReasoningLevel.XHIGH,
            ),
            qwen38.levels,
        )
        assertTrue(qwen35.compatibleEndpoint)
    }

    @Test
    fun `Claude and DeepSeek aliases retain model controls on third party providers`() {
        val provider = ProviderSetting.OpenAI(baseUrl = "https://compatible.example/v1")
        val claude = resolveReasoningLevelSupport(
            reasoningModel("opaque", displayName = "Claude Sonnet 3.7"),
            provider,
        )
        val deepSeek = resolveReasoningLevelSupport(reasoningModel("DeepSeekV4-Pro"), provider)

        assertTrue(ReasoningLevel.MAX in claude.levels)
        assertFalse(ReasoningLevel.MEDIUM in deepSeek.levels)
        assertEquals(ReasoningLevel.MAX, deepSeek.levels.last())
    }

    private fun reasoningModel(modelId: String, displayName: String = ""): Model = Model(
        modelId = modelId,
        displayName = displayName,
        abilities = listOf(ModelAbility.REASONING),
    )
}
