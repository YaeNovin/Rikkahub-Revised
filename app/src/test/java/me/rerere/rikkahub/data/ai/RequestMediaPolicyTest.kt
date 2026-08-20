package me.rerere.rikkahub.data.ai

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestMediaPolicyTest {
    @Test
    fun `old audio video and large images are omitted only from the request copy`() {
        val historical = UIMessage(
            role = MessageRole.USER,
            parts = listOf(
                UIMessagePart.Text("analyze these"),
                UIMessagePart.Video("file:///old.mp4"),
                UIMessagePart.Audio("file:///old.mp3"),
                UIMessagePart.Image("file:///large.jpg"),
                UIMessagePart.Image("file:///small.jpg"),
            ),
        )
        val current = UIMessage(
            role = MessageRole.USER,
            parts = listOf(
                UIMessagePart.Text("follow up"),
                UIMessagePart.Video("file:///current.mp4"),
                UIMessagePart.Image("file:///current-large.jpg"),
            ),
        )
        val source = listOf(historical, UIMessage.assistant("analysis"), current)

        val compacted = source.compactHistoricalMediaForRequest { url ->
            when {
                "large" in url -> HISTORICAL_LARGE_IMAGE_BYTES
                url.endsWith("small.jpg") -> 1_024L
                else -> null
            }
        }

        val oldParts = compacted.first().parts
        assertEquals(4, oldParts.count { it is UIMessagePart.Text })
        assertFalse(oldParts.any { it is UIMessagePart.Video || it is UIMessagePart.Audio })
        assertEquals(1, oldParts.count { it is UIMessagePart.Image })
        assertSame(current, compacted.last())
        assertTrue(historical.parts.any { it is UIMessagePart.Video })
        assertTrue(historical.parts.any { it is UIMessagePart.Audio })
        assertEquals(2, historical.parts.count { it is UIMessagePart.Image })
    }

    @Test
    fun `large historical data images are estimated without decoding base64`() {
        val thresholdBytes = 12L
        val encodedBytes = thresholdBytes + 1L
        val base64Chars = ((encodedBytes * 4L + 2L) / 3L).toInt()
        val historical = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Image("data:image/png;base64,${"A".repeat(base64Chars)}")),
        )

        val compacted = listOf(historical, UIMessage.user("next")).compactHistoricalMediaForRequest(
            largeImageBytes = thresholdBytes,
        )

        assertTrue(compacted.first().parts.single() is UIMessagePart.Text)
        assertTrue(historical.parts.single() is UIMessagePart.Image)
    }
}
