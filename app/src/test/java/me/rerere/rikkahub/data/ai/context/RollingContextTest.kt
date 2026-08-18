package me.rerere.rikkahub.data.ai.context

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RollingContextTest {
    @Test
    fun `automatic plan summarizes an old prefix and leaves a recent window`() {
        val messages = messages(count = 8, contentLength = 120)

        val plan = createRollingContextPlan(
            messages = messages,
            storedSummary = null,
            thresholdTokens = 200,
        )

        assertNotNull(plan)
        assertTrue(plan!!.messagesToSummarize.isNotEmpty())
        assertTrue(plan.messagesToSummarize.size < messages.size)
        assertEquals(
            messages.take(plan.messagesToSummarize.size).map(UIMessage::id),
            plan.sourceMessageIds,
        )
        assertEquals(512, plan.targetTokens)
    }

    @Test
    fun `summary is invalidated when the active branch changes`() {
        val messages = messages(count = 6, contentLength = 40)
        val summary = RollingContextSummary(
            content = "summary",
            sourceMessageIds = messages.take(3).map(UIMessage::id),
            updatedAtMillis = 1L,
        )
        val branched = messages.toMutableList().also {
            it[1] = UIMessage(role = MessageRole.ASSISTANT, parts = it[1].parts)
        }

        assertEquals(3, summary.coveredMessageCount(messages))
        assertEquals(0, summary.coveredMessageCount(branched))
    }

    @Test
    fun `manual plan can compact before the automatic threshold`() {
        val messages = messages(count = 6, contentLength = 8)

        assertNull(
            createRollingContextPlan(
                messages = messages,
                storedSummary = null,
                thresholdTokens = 32_000,
            )
        )
        assertNotNull(
            createRollingContextPlan(
                messages = messages,
                storedSummary = null,
                thresholdTokens = 32_000,
                force = true,
                targetTokensOverride = 1_000,
            )
        )
    }

    @Test
    fun `legacy disabled threshold migrates to the rolling context default`() {
        assertNotNull(
            createRollingContextPlan(
                messages = messages(count = 8, contentLength = 100),
                storedSummary = null,
                thresholdTokens = 0,
                force = true,
            )
        )
    }

    @Test
    fun `a valid existing summary only compacts newly accumulated history`() {
        val messages = messages(count = 10, contentLength = 100)
        val summary = RollingContextSummary(
            content = "previous summary",
            sourceMessageIds = messages.take(4).map(UIMessage::id),
            updatedAtMillis = 1L,
        )

        val plan = createRollingContextPlan(
            messages = messages,
            storedSummary = summary,
            thresholdTokens = 100,
        )

        assertNotNull(plan)
        assertEquals(summary, plan!!.previousSummary)
        assertFalse(plan.messagesToSummarize.any { it.id in summary.sourceMessageIds })
        assertTrue(plan.sourceMessageIds.size > summary.sourceMessageIds.size)
    }

    @Test
    fun `fallback window excludes old history and starts at a user turn`() {
        val messages = messages(count = 10, contentLength = 100)

        val startIndex = rollingContextWindowStartIndex(
            messages = messages,
            thresholdTokens = 200,
        )

        assertTrue(startIndex > 0)
        assertTrue(startIndex < messages.lastIndex)
        assertEquals(MessageRole.USER, messages[startIndex].role)
        assertTrue(messages.drop(startIndex).size < messages.size)
    }

    private fun messages(count: Int, contentLength: Int): List<UIMessage> = List(count) { index ->
        val content = "m$index ${"x".repeat(contentLength)}"
        if (index % 2 == 0) UIMessage.user(content) else UIMessage.assistant(content)
    }
}
