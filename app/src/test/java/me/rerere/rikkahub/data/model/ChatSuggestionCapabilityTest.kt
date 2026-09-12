package me.rerere.rikkahub.data.model

import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.ai.ui.UIMessage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatSuggestionCapabilityTest {
    @Test
    fun `sensitive actions require their assistant capability`() {
        val disabled = Assistant(localTools = emptyList())
        assertFalse(ChatSuggestionAction.SEARCH_WEB.isAvailableFor(disabled))
        assertFalse(ChatSuggestionAction.SEARCH_MEMORY.isAvailableFor(disabled))
        assertFalse(ChatSuggestionAction.ASK_USER.isAvailableFor(disabled))
        assertFalse(ChatSuggestionAction.WORKSPACE.isAvailableFor(disabled))

        val enabled = Assistant(
            enableWebSearch = true,
            enableMemory = true,
            localTools = listOf(LocalToolOption.AskUser),
            workspaceId = kotlin.uuid.Uuid.random(),
        )
        assertTrue(ChatSuggestionAction.SEARCH_WEB.isAvailableFor(enabled))
        assertTrue(ChatSuggestionAction.SEARCH_MEMORY.isAvailableFor(enabled))
        assertTrue(ChatSuggestionAction.ASK_USER.isAvailableFor(enabled))
        assertTrue(ChatSuggestionAction.WORKSPACE.isAvailableFor(enabled))
    }

    @Test
    fun `local suggestion actions are always available`() {
        val assistant = Assistant(localTools = emptyList())
        assertTrue(ChatSuggestionAction.INSERT_TEXT.isAvailableFor(assistant))
        assertTrue(ChatSuggestionAction.COPY_TEXT.isAvailableFor(assistant))
        assertTrue(ChatSuggestionAction.SAVE_QUICK_MESSAGE.isAvailableFor(assistant))
        assertTrue(ChatSuggestionAction.CREATE_BRANCH.isAvailableFor(assistant))
    }

    @Test
    fun `suggestions from an older assistant response expire`() {
        val older = UIMessage.assistant("older")
        val current = UIMessage.assistant("current")
        val conversation = Conversation(
            assistantId = kotlin.uuid.Uuid.random(),
            messageNodes = listOf(MessageNode.of(older), MessageNode.of(current)),
            chatSuggestionItems = listOf(
                ChatSuggestionItem(text = "stale", sourceMessageId = older.id),
                ChatSuggestionItem(text = "current", sourceMessageId = current.id),
                ChatSuggestionItem(text = "legacy"),
            ),
        )

        val visible = conversation.currentChatSuggestions().map(ChatSuggestionItem::text)
        assertFalse("stale" in visible)
        assertTrue("current" in visible)
        assertTrue("legacy" in visible)
    }
}
