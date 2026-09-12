package me.rerere.rikkahub.data.model

import me.rerere.ai.provider.*
import me.rerere.ai.ui.*
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.datastore.Settings
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class ChatSuggestionPolicyTest {
    private val assistant = Assistant(enableMemory = true, localTools = listOf(LocalToolOption.AskUser))
    private val response = UIMessage.assistant("Original response")
    private fun conversation() = Conversation(assistantId = assistant.id, messageNodes = listOf(MessageNode.of(response)))
    private fun settings(model: Model) = Settings(assistants = listOf(assistant), chatModelId = model.id,
        providers = listOf(ProviderSetting.OpenAI(models = listOf(model))))

    @Test fun `editing a response with the same id expires suggestions but title edits do not`() {
        val original = conversation()
        val saved = original.copy(chatSuggestionItems = listOf(ChatSuggestionItem(text = "Next", sourceMessageId = response.id,
            sourceContextKey = original.suggestionContextKey())))
        assertEquals(1, saved.copy(title = "Renamed").currentChatSuggestions().size)
        val edited = response.copy(parts = listOf(UIMessagePart.Text("Edited response")))
        assertTrue(saved.copy(messageNodes = listOf(MessageNode.of(edited))).currentChatSuggestions().isEmpty())
    }

    @Test fun `user messages interrupted replies and pending tools cannot generate or revive suggestions`() {
        val saved = conversation().copy(chatSuggestions = listOf("Legacy next"))
        assertTrue(saved.copy(messageNodes = saved.messageNodes + MessageNode.of(UIMessage.user("New turn"))).currentChatSuggestions().isEmpty())
        assertNull(saved.copy(messageNodes = listOf(MessageNode.of(response.copy(interrupted = true)))).suggestionSourceMessage())
        val pending = response.copy(parts = response.parts + UIMessagePart.Tool("ask", "ask_user", "{}", approvalState = ToolApprovalState.Pending))
        assertNull(saved.copy(messageNodes = listOf(MessageNode.of(pending))).suggestionSourceMessage())
    }

    @Test fun `memory suggestions follow per conversation disabled and rag only modes`() {
        val settings = settings(Model())
        assertFalse(ChatSuggestionAction.SEARCH_MEMORY in conversation().availableSuggestionActions(settings))
        assertTrue(ChatSuggestionAction.SEARCH_MEMORY in conversation().copy(memoryMode = ConversationMemoryMode.RAG_ONLY).availableSuggestionActions(settings))
        assertTrue(ChatSuggestionAction.SEARCH_MEMORY in conversation().copy(memoryMode = ConversationMemoryMode.EXTRACTION_ONLY).availableSuggestionActions(settings))
    }

    @Test fun `tool suggestions require the main model capability while native search is independent`() {
        assertFalse(ChatSuggestionAction.ASK_USER in conversation().availableSuggestionActions(settings(Model())))
        assertTrue(ChatSuggestionAction.ASK_USER in conversation().availableSuggestionActions(settings(Model(abilities = listOf(ModelAbility.TOOL)))))
        assertTrue(ChatSuggestionAction.SEARCH_WEB in conversation().availableSuggestionActions(settings(Model(tools = setOf(BuiltInTools.Search)))))
        assertTrue(ChatSuggestionAction.COPY_TEXT in conversation().availableSuggestionActions(settings(Model())))
    }

    @Test fun `changing a selected candidate changes the context identity`() {
        val original = conversation()
        val alternative = original.copy(messageNodes = listOf(MessageNode.of(UIMessage.assistant("Alternative"))))
        assertNotEquals(original.suggestionContextKey(), alternative.suggestionContextKey())
        assertNotEquals(original.suggestionContextKey(), original.copy(assistantId = Uuid.random()).suggestionContextKey())
    }
}
