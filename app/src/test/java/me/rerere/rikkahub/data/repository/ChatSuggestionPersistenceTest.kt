package me.rerere.rikkahub.data.repository

import kotlin.uuid.Uuid
import me.rerere.rikkahub.data.model.ChatSuggestionAction
import me.rerere.rikkahub.data.model.ChatSuggestionCategory
import me.rerere.rikkahub.data.model.ChatSuggestionItem
import me.rerere.rikkahub.data.model.Conversation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatSuggestionPersistenceTest {
    @Test fun `empty collapsed conversation keeps independent settings history and diagnostic across persistence`() {
        val item = ChatSuggestionItem(id = "pin", text = "Preserve")
        val session = me.rerere.rikkahub.data.model.SuggestionSession(
            settings = me.rerere.rikkahub.data.model.ChatSuggestionConfig(count = 2), paused = true, collapsed = true,
            pinnedIds = setOf(item.id), previous = me.rerere.rikkahub.data.model.SuggestionBatch(listOf(item)),
            info = me.rerere.rikkahub.data.model.SuggestionRunInfo("model", "id", 100, 15, error = "failed"))
        val conversation = Conversation(assistantId = Uuid.random(), messageNodes = emptyList(), suggestionSession = session)
        assertEquals(session, decodeChatSuggestions(encodeChatSuggestions(conversation)).session)
        assertTrue(decodeChatSuggestions(encodeChatSuggestions(conversation)).items.isEmpty())
    }

    @Test fun `a corrupt item and future action do not discard other stored suggestions`() {
        val decoded = decodeChatSuggestions("""{"version":1,"items":[{"id":"same","text":"First"},
            {"id":"same","text":"Second","action":"future_action"}, {"text":null},42]}""")
        assertEquals(listOf("First", "Second"), decoded.texts)
        assertEquals(2, decoded.items.map { it.id }.distinct().size)
        assertEquals(ChatSuggestionAction.INSERT_TEXT, decoded.items.last().action)
    }

    @Test fun `legacy nulls and booleans do not become visible suggestions`() {
        assertEquals(listOf("Valid"), decodeChatSuggestions("""[null,true,42," ","Valid"]""").texts)
    }

    @Test
    fun `legacy string arrays remain readable`() {
        val decoded = decodeChatSuggestions("""["继续说明","给个例子"]""")

        assertEquals(listOf("继续说明", "给个例子"), decoded.texts)
        assertTrue(decoded.items.isEmpty())
    }

    @Test
    fun `rich suggestions round trip through existing column`() {
        val sourceMessageId = Uuid.random()
        val conversation = Conversation(
            assistantId = Uuid.random(),
            messageNodes = emptyList(),
            chatSuggestionItems = listOf(
                ChatSuggestionItem(
                    id = "stable-id",
                    text = "搜索资料",
                    description = "使用当前助手的搜索能力",
                    category = ChatSuggestionCategory.ACTION,
                    action = ChatSuggestionAction.SEARCH_WEB,
                    payload = "Compose LazyHorizontalGrid",
                    sourceMessageId = sourceMessageId,
                )
            ),
        )

        val decoded = decodeChatSuggestions(encodeChatSuggestions(conversation))

        assertEquals(listOf("搜索资料"), decoded.texts)
        assertEquals(conversation.chatSuggestionItems, decoded.items)
    }

    @Test
    fun `malformed suggestion data degrades to empty`() {
        val decoded = decodeChatSuggestions("not-json")

        assertTrue(decoded.texts.isEmpty())
        assertTrue(decoded.items.isEmpty())
    }
}
