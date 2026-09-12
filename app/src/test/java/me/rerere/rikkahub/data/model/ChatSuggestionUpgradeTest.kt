package me.rerere.rikkahub.data.model

import kotlinx.serialization.json.*
import me.rerere.ai.provider.*
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.service.boundedSuggestionContext
import me.rerere.rikkahub.service.isExplicitEmptySuggestionResponse
import me.rerere.rikkahub.service.normalizeGeneratedSuggestionItems
import me.rerere.rikkahub.service.suggestionFeedbackInstruction
import me.rerere.rikkahub.data.ai.context.estimateTextTokens
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class ChatSuggestionUpgradeTest {
    @Test fun `feedback prompts disclose preference kinds without carrying text from another conversation`() {
        val instruction = suggestionFeedbackInstruction(listOf(SuggestionFeedback("SECRET_FROM_OTHER_CONVERSATION", SuggestionFeedbackReason.TOO_LONG, ChatSuggestionCategory.ACTION)))
        assertFalse(instruction.contains("SECRET_FROM_OTHER_CONVERSATION"))
        assertTrue(instruction.contains("TOO_LONG"))
    }
    private fun conversation(assistant: Assistant = Assistant()) = Conversation(assistantId = assistant.id,
        messageNodes = listOf(MessageNode.of(UIMessage.assistant("Reply")) ))

    @Test fun `conversation settings override assistant which overrides global defaults`() {
        val global = Settings(suggestionCount = 3)
        val assistant = Assistant(suggestionSettings = ChatSuggestionConfig(count = 6))
        val conversation = conversation(assistant)
        val settings = global.copy(assistants = listOf(assistant))
        assertEquals(6, conversation.suggestionConfig(settings).count)
        assertEquals(8, conversation.copy(suggestionSession = SuggestionSession(settings = ChatSuggestionConfig(count = 8))).suggestionConfig(settings).count)
        assertEquals(3, conversation.suggestionConfig(settings.copy(assistants = listOf(assistant.copy(suggestionSettings = null)))).count)
    }

    @Test fun `global config round trip retains advanced options and disabled mode`() {
        val config = ChatSuggestionConfig(count = 7, options = SuggestionOptions(trigger = SuggestionTrigger.MANUAL, contextBudget = 1600, intervalSeconds = 90))
        assertEquals(config, Settings().withSuggestionConfig(config).defaultSuggestionConfig())
        val disabled = config.copy(options = config.options.copy(trigger = SuggestionTrigger.DISABLED))
        assertFalse(Settings().withSuggestionConfig(disabled).enableSuggestion)
        assertEquals(disabled, Settings().withSuggestionConfig(disabled).defaultSuggestionConfig())
    }

    @Test fun `automatic interval and pause do not change saved manual settings`() {
        val config = ChatSuggestionConfig(options = SuggestionOptions(intervalSeconds = 60))
        val conversation = conversation().copy(suggestionSession = SuggestionSession(lastAttemptAt = 10_000))
        assertFalse(conversation.canAutomaticallySuggest(config, 69_999))
        assertTrue(conversation.canAutomaticallySuggest(config, 70_000))
        assertFalse(conversation.copy(suggestionSession = conversation.suggestionSession.copy(paused = true)).canAutomaticallySuggest(config, 90_000))
        assertFalse(conversation.canAutomaticallySuggest(config.copy(options = config.options.copy(trigger = SuggestionTrigger.MANUAL)), 90_000))
        assertFalse(conversation.canAutomaticallySuggest(config, 1_000))
    }

    @Test fun `historical target excludes later messages and becomes invalid after selecting another candidate`() {
        val old = UIMessage.assistant("Old answer")
        val future = UIMessage.assistant("Future secret")
        val conversation = conversation().copy(messageNodes = listOf(MessageNode.of(old), MessageNode.of(future)),
            suggestionSession = SuggestionSession(target = SuggestionTarget(old.id, "Old")))
        assertEquals(listOf(old), conversation.suggestionContext().currentMessages)
        assertEquals(old.id, conversation.suggestionSourceMessage()?.id)
        val appended = conversation.copy(messageNodes = conversation.messageNodes + MessageNode.of(UIMessage.user("Later")))
        assertEquals(conversation.suggestionContextKey(), appended.suggestionContextKey())
        assertNull(conversation.copy(messageNodes = listOf(MessageNode.of(future))).suggestionSourceMessage())
        assertNotEquals(conversation.suggestionContextKey(), conversation.copy(memoryMode = ConversationMemoryMode.ENABLED).suggestionContextKey())
    }

    @Test fun `fixed items survive refresh and duplicates compare actual payload without confusing actions`() {
        val fixed = ChatSuggestionItem(id = "fixed", text = "Keep", payload = "Explain the same topic")
        val duplicate = ChatSuggestionItem(text = "Different label", payload = fixed.payload)
        val differentAction = duplicate.copy(action = ChatSuggestionAction.IMAGE_DRAFT)
        val result = selectSuggestionBatch(listOf(duplicate, differentAction), listOf(fixed), 4, true, emptyList())
        assertEquals(listOf(fixed, differentAction), result)
        assertFalse(suggestionsSimilar(ChatSuggestionItem(text = "😀"), ChatSuggestionItem(text = "😢")))
    }

    @Test fun `feedback excludes rejected payloads and category balancing does not pad suggestions`() {
        val a = ChatSuggestionItem(text = "A", payload = "Rejected topic")
        val b = ChatSuggestionItem(text = "B")
        val c = ChatSuggestionItem(text = "C", category = ChatSuggestionCategory.ACTION)
        val result = selectSuggestionBatch(listOf(a, b, b.copy(id = "dup"), c), emptyList(), 8, true,
            listOf(SuggestionFeedback(a.payload, SuggestionFeedbackReason.IRRELEVANT, a.category)))
        assertEquals(listOf(b, c), result)
    }

    @Test fun `explicit empty response is distinct from malformed output`() {
        assertTrue(isExplicitEmptySuggestionResponse("""{"suggestions":[]}"""))
        assertTrue(isExplicitEmptySuggestionResponse("```json\n[]\n```"))
        assertFalse(isExplicitEmptySuggestionResponse("""{"error":"bad"}"""))
    }

    @Test fun `context budgets bound selected text summary and recent messages`() {
        val context = boundedSuggestionContext("[ASSISTANT] " + "对话".repeat(3000), "摘要".repeat(2000), "选句".repeat(1000), 500)
        assertTrue(estimateTextTokens(context) <= 500)
        assertTrue(context.contains("Selected passage"))
    }

    @Test fun `insertion supports selection replacement append and later undo guards`() {
        val cursor = prepareSuggestionInsertion("abcdef", "NEW", SuggestionInsertLocation.CURSOR, 4, 2)
        assertEquals("abNEWef", cursor.after)
        assertEquals(5, cursor.cursor)
        assertEquals("abcdef", cursor.before)
        assertEquals("old\nnew", prepareSuggestionInsertion("old", "new", SuggestionInsertLocation.APPEND).after)
        assertEquals("new", prepareSuggestionInsertion("old", "new", SuggestionInsertLocation.REPLACE).after)
    }

    @Test fun `parameterized suggestions validate the form and placeholder contract`() {
        val valid = """[{"text":"Plan","payload":"Travel to {{place}}","parameters":{"questions":[{"id":"place","question":"Where?"}]}}]"""
        assertNotNull(normalizeGeneratedSuggestionItems(valid).single().parameterForm)
        assertTrue(normalizeGeneratedSuggestionItems(valid.replace("{{place}}", "{{missing}}")).isEmpty())
        val dangerous = """[{"text":"Delete","payload":"{{go}}","parameters":{"questions":[{"id":"go","question":"Go?","selection_type":"confirm"}]}}]"""
        assertTrue(normalizeGeneratedSuggestionItems(dangerous).isEmpty())
    }

    @Test fun `image draft action requires image model plus explicit suggestion option`() {
        val assistant = Assistant()
        val image = Model(modelId = "image", type = ModelType.IMAGE)
        val settings = Settings(assistants = listOf(assistant), providers = listOf(ProviderSetting.OpenAI(models = listOf(image))), imageGenerationModelId = image.id)
        val conversation = conversation(assistant)
        assertFalse(ChatSuggestionAction.IMAGE_DRAFT in conversation.availableSuggestionActions(settings))
        val enabled = settings.copy(suggestionOptions = SuggestionOptions(imageSuggestions = true))
        assertTrue(ChatSuggestionAction.IMAGE_DRAFT in conversation.availableSuggestionActions(enabled))
        assertFalse(ChatSuggestionAction.IMAGE_DRAFT in conversation.availableSuggestionActions(enabled.copy(imageGenerationModelId = Uuid.random())))
    }
}
