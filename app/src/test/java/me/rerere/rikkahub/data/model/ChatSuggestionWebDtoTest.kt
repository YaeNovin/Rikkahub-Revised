package me.rerere.rikkahub.data.model

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.web.dto.toDto
import me.rerere.rikkahub.web.routes.singleNodeDiffOrNull
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class ChatSuggestionWebDtoTest {
    @Test fun `web response removes stale suggestions and sends a full update when a message is edited`() {
        val message = UIMessage.assistant("Before")
        val source = Conversation(assistantId = Uuid.random(), messageNodes = listOf(MessageNode.of(message)))
        val conversation = source.copy(chatSuggestionItems = listOf(ChatSuggestionItem(text = "Next", sourceMessageId = message.id,
            sourceContextKey = source.suggestionContextKey())))
        val before = conversation.toDto()
        assertEquals(listOf("Next"), before.chatSuggestions)
        val after = conversation.copy(messageNodes = listOf(MessageNode.of(message.copy(parts = listOf(UIMessagePart.Text("After")))))).toDto()
        assertTrue(after.chatSuggestions.isEmpty())
        assertNull(before.singleNodeDiffOrNull(after))
        assertTrue(conversation.toDto(isGenerating = true).chatSuggestions.isEmpty())
    }
}
