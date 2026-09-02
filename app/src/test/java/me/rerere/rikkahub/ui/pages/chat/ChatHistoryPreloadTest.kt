package me.rerere.rikkahub.ui.pages.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatHistoryPreloadTest {
    @Test
    fun `preload prioritizes nearest earlier messages`() {
        assertEquals(
            listOf(6, 5, 4, 10),
            historyPreloadIndices(
                firstVisibleIndex = 7,
                lastVisibleIndex = 9,
                messageCount = 20,
            ),
        )
    }

    @Test
    fun `preload window stays inside conversation bounds`() {
        assertEquals(
            listOf(3),
            historyPreloadIndices(
                firstVisibleIndex = 0,
                lastVisibleIndex = 2,
                messageCount = 4,
            ),
        )
        assertEquals(
            listOf(7, 6, 5),
            historyPreloadIndices(
                firstVisibleIndex = 8,
                lastVisibleIndex = 9,
                messageCount = 10,
            ),
        )
    }

    @Test
    fun `invalid visible range does not schedule work`() {
        assertEquals(emptyList<Int>(), historyPreloadIndices(-1, -1, 10))
        assertEquals(emptyList<Int>(), historyPreloadIndices(0, 0, 0))
    }
}
