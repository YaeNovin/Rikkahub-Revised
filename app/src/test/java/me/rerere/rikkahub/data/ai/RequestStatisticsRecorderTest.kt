package me.rerere.rikkahub.data.ai

import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.StreamChunk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestStatisticsRecorderTest {
    @Test
    fun `reasoning depth recognizes provider parameter variants`() {
        assertEquals("high", mapOf("reasoning_effort" to "high").reasoningDepth())
        assertEquals("medium", mapOf("reasoning.effort" to "medium").reasoningDepth())
        assertEquals("low", mapOf("output_config.effort" to "low").reasoningDepth())
        assertEquals(
            "HIGH",
            mapOf("thinkingConfig.thinkingLevel" to "HIGH").reasoningDepth(),
        )
        assertEquals("2048", mapOf("thinking.budget_tokens" to "2048").reasoningDepth())
    }

    @Test
    fun `reasoning depth prefers explicit request effort`() {
        val parameters = mapOf(
            "reasoning_effort" to "high",
            "thinking.budget_tokens" to "4096",
        )

        assertEquals("high", parameters.reasoningDepth())
    }

    @Test
    fun `first token timing only accepts model text content`() {
        assertFalse(StreamChunk.TextStart("text").isFirstModelContentToken())
        assertFalse(StreamChunk.TextDelta("text", "  ").isFirstModelContentToken())
        assertTrue(StreamChunk.TextDelta("text", "hello").isFirstModelContentToken())
        assertTrue(StreamChunk.ReasoningDelta("reasoning", "thinking").isFirstModelContentToken())
        assertFalse(StreamChunk.ToolCallStart("tool", "search").isFirstModelContentToken())
        assertFalse(StreamChunk.Usage(TokenUsage(promptTokens = 1)).isFirstModelContentToken())
        assertFalse(StreamChunk.Finish().isFirstModelContentToken())
    }
}
