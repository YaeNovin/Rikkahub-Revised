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
    fun `automatic compression can be disabled without losing its threshold`() {
        assertNull(
            automaticRollingContextThreshold(
                enabled = false,
                configuredThresholdTokens = 48_000,
                modelContextWindowTokens = 128_000,
            )
        )
        assertEquals(
            48_000,
            automaticRollingContextThreshold(
                enabled = true,
                configuredThresholdTokens = 48_000,
                modelContextWindowTokens = 128_000,
            )
        )
    }

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
    fun `completed plan is committed only while its source prefix is unchanged`() {
        val messages = messages(count = 8, contentLength = 120)
        val plan = createRollingContextPlan(
            messages = messages,
            storedSummary = null,
            thresholdTokens = 200,
        )!!
        val appended = messages + UIMessage.user("new turn")
        val editedPrefix = messages.toMutableList().also {
            it[0] = UIMessage.user("edited")
        }

        assertTrue(plan.isStillApplicableTo(messages))
        assertTrue(plan.isStillApplicableTo(appended))
        assertFalse(plan.isStillApplicableTo(editedPrefix))
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
    fun `model context safety threshold overrides an unsafe configured threshold`() {
        assertEquals(
            80_000,
            effectiveRollingContextThreshold(
                configuredThresholdTokens = 1_000_000,
                modelContextWindowTokens = 100_000,
                maxOutputTokens = 10_000,
            ),
        )
        assertEquals(
            32_000,
            effectiveRollingContextThreshold(
                configuredThresholdTokens = 32_000,
                modelContextWindowTokens = 100_000,
                maxOutputTokens = 10_000,
            ),
        )
    }

    @Test
    fun `small model still reserves half of its context when output budget is oversized`() {
        assertEquals(
            4_000,
            effectiveRollingContextThreshold(
                configuredThresholdTokens = 32_000,
                modelContextWindowTokens = 8_000,
                maxOutputTokens = 8_000,
            ),
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

    @Test
    fun `compression text splitting preserves all content within each token budget`() {
        val content = buildString {
            appendLine("Conversation heading")
            append("\u4e2d\u6587\u5185\u5bb9".repeat(200))
            appendLine()
            append("english content ".repeat(300))
            append("\ud83d\ude00".repeat(50))
        }

        val chunks = splitTextForTokenBudget(content, maxTokens = 300)

        assertTrue(chunks.size > 1)
        assertEquals(content, chunks.joinToString(separator = ""))
        assertTrue(chunks.all { estimateTextTokens(it) <= 300 })
        assertTrue(chunks.none { it.lastOrNull()?.isHighSurrogate() == true })
    }

    private fun messages(count: Int, contentLength: Int): List<UIMessage> = List(count) { index ->
        val content = "m$index ${"x".repeat(contentLength)}"
        if (index % 2 == 0) UIMessage.user(content) else UIMessage.assistant(content)
    }
}
