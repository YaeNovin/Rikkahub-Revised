package me.rerere.rikkahub.ui.components.ai

import me.rerere.rikkahub.data.model.*
import org.junit.Assert.*
import org.junit.Test

class PromptDiagnosticsVisibilityTest {
    @Test fun `conversation exclusion overrides default without modifying assistant`() {
        val book = Lorebook()
        val assistant = Assistant(allowConversationPromptInjection = true, lorebookIds = setOf(book.id))
        val conversation = Conversation(assistantId = assistant.id, messageNodes = emptyList(), disabledLorebookIds = setOf(book.id))
        assertFalse(hasEnabledPromptDiagnostics(assistant, conversation, emptyList(), listOf(book)))
        assertTrue(hasEnabledPromptDiagnostics(assistant, conversation.copy(disabledLorebookIds = emptySet()), emptyList(), listOf(book)))
        assertEquals(setOf(book.id), assistant.lorebookIds)
    }
    @Test fun `unchecking last lorebook hides diagnostics immediately`() {
        val book = Lorebook(name = "World")
        val assistant = Assistant(allowConversationPromptInjection = true)
        val conversation = Conversation(assistantId = assistant.id, messageNodes = emptyList(), lorebookIds = setOf(book.id))
        assertTrue(hasEnabledPromptDiagnostics(assistant, conversation, emptyList(), listOf(book)))
        assertFalse(hasEnabledPromptDiagnostics(assistant, conversation.copy(lorebookIds = emptySet()), emptyList(), listOf(book)))
    }
    @Test fun `disabled deleted or forbidden conversation selections do not show diagnostics`() {
        val book = Lorebook()
        val assistant = Assistant(allowConversationPromptInjection = false)
        val conversation = Conversation(assistantId = assistant.id, messageNodes = emptyList(), lorebookIds = setOf(book.id))
        assertFalse(hasEnabledPromptDiagnostics(assistant, conversation, emptyList(), listOf(book)))
        val defaults = assistant.copy(lorebookIds = setOf(book.id))
        assertTrue(hasEnabledPromptDiagnostics(defaults, conversation, emptyList(), listOf(book)))
        assertFalse(hasEnabledPromptDiagnostics(defaults, conversation, emptyList(), listOf(book.copy(enabled = false))))
        assertFalse(hasEnabledPromptDiagnostics(defaults, conversation, emptyList(), emptyList()))
    }
}
