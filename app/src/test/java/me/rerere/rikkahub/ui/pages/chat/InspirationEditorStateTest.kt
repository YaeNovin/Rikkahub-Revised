package me.rerere.rikkahub.ui.pages.chat

import me.rerere.rikkahub.data.model.InspirationCard
import me.rerere.rikkahub.data.model.InspirationSettings
import org.junit.Assert.*
import org.junit.Test

class InspirationEditorStateTest {
    @Test fun `rotation retains edits but a new opening reloads saved configuration`() {
        val state = InspirationEditorState()
        val initial = InspirationSettings()
        state.begin("opening-1", initial, true)
        val card = InspirationCard(title = "Draft", prompt = "Unfinished text")
        state.draft = initial.copy(customCards = listOf(card))
        state.inherited = false
        state.editing = card
        state.begin("opening-1", initial, true)
        assertEquals(listOf(card), state.draft.customCards)
        assertFalse(state.inherited)
        assertEquals(card, state.editing)
        state.begin("opening-2", initial, true)
        assertEquals(initial, state.draft)
        assertTrue(state.inherited)
        assertNull(state.editing)
    }
}
