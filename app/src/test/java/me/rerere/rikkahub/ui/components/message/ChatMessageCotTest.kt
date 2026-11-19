package me.rerere.rikkahub.ui.components.message

import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMessageCotTest {
    @Test
    fun `accepted automatic renderer is answer content`() {
        val blocks = listOf(
            UIMessagePart.Reasoning("先查可用渲染器"),
            renderTool(status = "accepted", display = "chat_preview"),
            UIMessagePart.Text("这是正式说明"),
        ).groupMessageParts()

        assertEquals(3, blocks.size)
        assertTrue(blocks[0] is MessagePartBlock.ThinkingBlock)
        assertTrue(blocks[1] is MessagePartBlock.RenderedContentBlock)
        assertTrue(blocks[2] is MessagePartBlock.ContentBlock)
    }

    @Test
    fun `manual html and failed renderer remain in tool chain`() {
        val blocks = listOf(
            renderTool(status = "accepted", display = "manual_static_preview"),
            renderTool(status = "error", display = "chat_preview"),
        ).groupMessageParts()

        assertEquals(1, blocks.size)
        val thinking = blocks.single() as MessagePartBlock.ThinkingBlock
        assertEquals(2, thinking.steps.size)
        assertTrue(thinking.steps.all { it is ThinkingStep.ToolStep })
    }

    private fun renderTool(status: String, display: String): UIMessagePart.Tool =
        UIMessagePart.Tool(
            toolCallId = "render-${status}-${display}",
            toolName = "render_content",
            input = "{\"renderer\":\"svg\",\"source\":\"<svg/>\"}",
            output = listOf(UIMessagePart.Text("{\"status\":\"$status\",\"display\":\"$display\"}")),
        )
}
