package me.rerere.rikkahub.data.ai.context

import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
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

    @Test
    fun `large prior turn can be compacted before four messages accumulate`() {
        val messages = listOf(
            UIMessage.user("document ${"x".repeat(20_000)}"),
            UIMessage.assistant("I read the document."),
            UIMessage.user("Continue."),
        )

        val plan = createRollingContextPlan(
            messages = messages,
            storedSummary = null,
            thresholdTokens = 1_000,
        )

        assertNotNull(plan)
        assertEquals(messages.take(2).map(UIMessage::id), plan!!.sourceMessageIds)
        assertEquals(listOf(messages.last().id), messages.drop(plan.sourceMessageIds.size).map(UIMessage::id))
    }

    @Test
    fun `reasoning and tool payloads are included in message estimate`() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text("answer"),
                UIMessagePart.Reasoning("r".repeat(400)),
                UIMessagePart.Tool(
                    toolCallId = "call-123",
                    toolName = "inspect_file",
                    input = "i".repeat(400),
                    output = listOf(UIMessagePart.Text("o".repeat(400))),
                ),
            ),
        )

        assertTrue(estimateMessageTokens(message) > estimateMessageTokens(UIMessage.assistant("answer")) + 250)
    }

    @Test
    fun `provider completion usage covers hidden model output`() {
        val message = UIMessage.assistant("short answer").copy(
            usage = TokenUsage(completionTokens = 2_000),
        )

        assertEquals(2_004, estimateMessageTokens(message))
    }

    @Test
    fun `provider prompt usage can trigger automatic compression`() {
        val messages = listOf(
            UIMessage.assistant("Prior reply").copy(
                usage = TokenUsage(promptTokens = 5_000, completionTokens = 100),
            ),
            UIMessage.user("Continue."),
        )

        assertNotNull(
            createRollingContextPlan(
                messages = messages,
                storedSummary = null,
                thresholdTokens = 4_000,
            )
        )
    }

    private fun messages(count: Int, contentLength: Int): List<UIMessage> = List(count) { index ->
        val content = "m$index ${"x".repeat(contentLength)}"
        if (index % 2 == 0) UIMessage.user(content) else UIMessage.assistant(content)
    }
}
