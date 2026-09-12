package me.rerere.rikkahub.data.datastore

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatSuggestionSettingsTest {
    @Test
    fun `display mode defaults to auto for missing or invalid values`() {
        assertEquals(ChatSuggestionDisplayMode.AUTO, decodeChatSuggestionDisplayMode(null))
        assertEquals(ChatSuggestionDisplayMode.AUTO, decodeChatSuggestionDisplayMode("invalid"))
    }

    @Test
    fun `display mode restores persisted values`() {
        ChatSuggestionDisplayMode.entries.forEach { mode ->
            assertEquals(mode, decodeChatSuggestionDisplayMode(mode.name))
        }
    }
}
