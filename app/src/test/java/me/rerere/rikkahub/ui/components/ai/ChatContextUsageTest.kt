package me.rerere.rikkahub.ui.components.ai

import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.ai.context.RollingContextSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatContextUsageTest {
    @Test
    fun `uses provider prompt usage when it exceeds the lightweight local estimate`() {
        val usage = calculateChatContextUsage(
            messages = listOf(
                UIMessage.user("A much longer request that would produce a different local estimate."),
                UIMessage.assistant("OK").copy(
                    usage = TokenUsage(promptTokens = 12_345, completionTokens = 25),
                ),
            ),
            capacityTokens = 32_768,
        )

        assertEquals(12_370, usage.usedTokens)
        assertEquals(32_768, usage.capacityTokens)
        assertTrue(usage.isEstimated)
    }

    @Test
    fun `estimates active context when provider usage is unavailable`() {
        val usage = calculateChatContextUsage(
            messages = listOf(UIMessage.user("你好")),
            capacityTokens = null,
        )

        assertEquals(6, usage.usedTokens)
        assertEquals(null, usage.capacityTokens)
        assertTrue(usage.isEstimated)
    }

    @Test
    fun `estimates the next request after the user adds a message`() {
        val usage = calculateChatContextUsage(
            messages = listOf(
                UIMessage.assistant("Prior reply").copy(usage = TokenUsage(promptTokens = 12_345)),
                UIMessage.user("Next question"),
            ),
            capacityTokens = 32_768,
        )

        assertTrue(usage.isEstimated)
        assertTrue(usage.usedTokens > 12_345)
    }

    @Test
    fun `reports percentage and remaining tokens for the full session estimate`() {
        val usage = calculateChatContextUsage(
            messages = listOf(UIMessage.user("你好")),
            capacityTokens = 10,
        )

        assertEquals(6, usage.usedTokens)
        assertEquals(60, usage.percentage)
        assertEquals(4, usage.remainingTokens)
    }

    @Test
    fun `reports the effective summary and recent-window request instead of retained history`() {
        val messages = List(10) { index ->
            if (index % 2 == 0) UIMessage.user("message $index ${"x".repeat(400)}")
            else UIMessage.assistant("message $index ${"x".repeat(400)}")
        }
        val summary = RollingContextSummary(
            content = "A compact summary of the first turns.",
            sourceMessageIds = messages.take(4).map(UIMessage::id),
            updatedAtMillis = 1L,
        )

        val usage = calculateChatContextUsage(
            messages = messages,
            rollingContextSummary = summary,
            capacityTokens = 8_000,
        )

        assertTrue(usage.usedTokens < 1_500)
        assertTrue(usage.remainingTokens!! > 6_500)
    }

    @Test
    fun `does not display a future compression result before compression succeeds`() {
        val messages = List(6) { index ->
            if (index % 2 == 0) UIMessage.user("message $index ${"x".repeat(4_000)}")
            else UIMessage.assistant("message $index ${"x".repeat(4_000)}")
        }

        val usage = calculateChatContextUsage(
            messages = messages,
            capacityTokens = 32_768,
        )

        assertTrue(usage.usedTokens > 5_000)
    }
}
