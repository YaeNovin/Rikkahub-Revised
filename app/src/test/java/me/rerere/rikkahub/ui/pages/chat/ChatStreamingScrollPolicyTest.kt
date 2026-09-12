package me.rerere.rikkahub.ui.pages.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatStreamingScrollPolicyTest {
    @Test fun `a drag completed inside the follow delay still cancels the old scroll`() {
        val gate = StreamingScrollGate()
        val version = gate.interactionVersion
        gate.onUserScroll()
        gate.onScrollSettled(atBottom = true)
        assertFalse(gate.allowsPending(version, 8, 200, 8, 200))
        assertTrue(gate.allowsPending(gate.interactionVersion, 8, 200, 8, 200))
    }

    @Test fun `manual browsing stays detached until the user returns to the bottom`() {
        val gate = StreamingScrollGate()
        gate.onUserScroll()
        gate.onScrollSettled(atBottom = false)
        assertTrue(gate.browsing)
        assertFalse(gate.allowsPending(gate.interactionVersion, 8, 200, 8, 200))
        gate.onScrollSettled(atBottom = true)
        assertTrue(gate.allowsPending(gate.interactionVersion, 8, 200, 8, 200))
    }

    @Test fun `changed scroll offsets and changed visible items invalidate delayed corrections`() {
        val gate = StreamingScrollGate()
        assertFalse(gate.allowsPending(0, 8, 200, 8, 199))
        assertFalse(gate.allowsPending(0, 8, 200, 7, 200))
    }
    @Test
    fun `streaming output follows only when the real last item is near the bottom`() {
        assertTrue(
            shouldFollowStreamingOutput(
                loading = true,
                isScrollInProgress = false,
                lastVisibleItemIndex = 9,
                totalItemCount = 10,
                lastVisibleItemEndPx = 940,
                viewportEndPx = 900,
                bottomInsetPx = 80,
                tolerancePx = 128,
            ),
        )
        assertFalse(
            shouldFollowStreamingOutput(
                loading = true,
                isScrollInProgress = false,
                lastVisibleItemIndex = 7,
                totalItemCount = 10,
                lastVisibleItemEndPx = 800,
                viewportEndPx = 900,
                bottomInsetPx = 80,
                tolerancePx = 128,
            ),
        )
    }

    @Test
    fun `streaming output does not fight active user scrolling`() {
        assertFalse(
            shouldFollowStreamingOutput(
                loading = true,
                isScrollInProgress = true,
                lastVisibleItemIndex = 9,
                totalItemCount = 10,
                lastVisibleItemEndPx = 820,
                viewportEndPx = 900,
                bottomInsetPx = 80,
                tolerancePx = 128,
            ),
        )
    }

    @Test
    fun `bottom correction scrolls only the newly overflowing distance`() {
        assertEquals(0, streamingBottomOverflowPx(800, 900, 80))
        assertEquals(36, streamingBottomOverflowPx(856, 900, 80))
    }
}
