package me.rerere.rikkahub.ui.pages.chat

import me.rerere.rikkahub.data.model.InspirationCard
import me.rerere.rikkahub.data.model.InspirationSettings
import org.junit.Assert.*
import org.junit.Test

class InspirationEditorStateTest {
    @Test fun `readiness belongs to one opening and new saved settings cannot overwrite its draft`() {
        val state = InspirationEditorState()
        assertFalse(state.isReady("first"))
        state.begin("first", InspirationSettings(cardCount = 6), false)
        assertTrue(state.isReady("first"))
        assertFalse(state.isReady("second"))
        state.draft = state.draft.copy(cardCount = 2)
        state.begin("first", InspirationSettings(cardCount = 8), true)
        assertEquals(2, state.draft.cardCount)
        assertFalse(state.inherited)
        state.begin("second", InspirationSettings(cardCount = 4), true)
        assertFalse(state.isReady("first"))
        assertTrue(state.isReady("second"))
        assertEquals(4, state.draft.cardCount)
    }

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
