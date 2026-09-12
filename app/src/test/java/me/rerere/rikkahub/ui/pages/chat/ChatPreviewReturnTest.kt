package me.rerere.rikkahub.ui.pages.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class ChatPreviewReturnTest {
    @Test fun `departure state remains pending while the preview is open and cancels explicitly`() {
        val state = ChatPreviewReturnState()
        state.anchor = ChatPreviewAnchor("b", 1, 250)
        assertFalse(state.departed)
        state.leave()
        assertTrue(state.departed)
        assertEquals(250, state.anchor!!.offset)
        state.cancel()
        assertNull(state.anchor)
        assertFalse(state.departed)
        state.leave()
        assertFalse(state.departed)
    }

    @Test fun `layout readiness waits for every nested parser and removes disposed placeholders`() {
        val state = ChatPreviewReturnState()
        val first = Any()
        val second = Any()
        state.layoutReadiness.update(first, true)
        state.layoutReadiness.update(second, true)
        state.layoutReadiness.update(first, false)
        assertFalse(state.layoutReadiness.ready)
        state.layoutReadiness.remove(second)
        assertTrue(state.layoutReadiness.ready)
    }
    @Test fun `new messages before anchor do not change the message being restored`() {
        val anchor = ChatPreviewAnchor("b", 1, 240)
        assertEquals(2, resolvePreviewReturnIndex(anchor, listOf("new", "a", "b", "c")))
        assertEquals(240, anchor.offset)
    }

    @Test fun `deleted anchors and empty lists have a bounded fallback`() {
        val anchor = ChatPreviewAnchor("deleted", 8, 200)
        assertEquals(1, resolvePreviewReturnIndex(anchor, listOf("a", "b")))
        assertEquals(0, resolvePreviewReturnIndex(anchor, emptyList()))
        assertEquals(0, resolvePreviewReturnIndex(anchor.copy(index = -1), listOf("a")))
    }
}
