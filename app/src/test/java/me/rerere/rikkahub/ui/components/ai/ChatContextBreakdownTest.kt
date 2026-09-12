package me.rerere.rikkahub.ui.components.ai

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.data.ai.context.RollingContextSummary
import org.junit.Assert.*
import org.junit.Test

class ChatContextBreakdownTest {
    @Test fun `breakdown sums to usage and hides absent categories`() {
        val usage = calculateChatContextUsage(listOf(UIMessage(role = MessageRole.USER,
            parts = listOf(UIMessagePart.Text("hello"), UIMessagePart.Image("file:///image.png")))), capacityTokens = 32000)
        assertEquals(usage.usedTokens, usage.breakdown.values.sum())
        assertTrue(usage.breakdown.containsKey(ContextTokenCategory.IMAGE))
        assertFalse(usage.breakdown.containsKey(ContextTokenCategory.AUDIO))
    }
    @Test fun `summarized media is not counted twice`() {
        val message = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Video("file:///video.mp4")))
        val usage = calculateChatContextUsage(listOf(message), RollingContextSummary("summary", listOf(message.id), 0), 32000)
        assertFalse(usage.breakdown.containsKey(ContextTokenCategory.VIDEO))
        assertTrue(usage.breakdown.containsKey(ContextTokenCategory.SUMMARY))
        assertEquals(usage.usedTokens, usage.breakdown.values.sum())
    }
}
