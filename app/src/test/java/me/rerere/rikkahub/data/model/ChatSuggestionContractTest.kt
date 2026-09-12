package me.rerere.rikkahub.data.model

import kotlinx.serialization.json.*
import me.rerere.ai.ui.AskUserProtocol
import me.rerere.rikkahub.data.datastore.*
import me.rerere.rikkahub.service.normalizeGeneratedSuggestionItems
import me.rerere.rikkahub.service.suggestionContextBudget
import me.rerere.rikkahub.service.suggestionGenerationInstruction
import me.rerere.rikkahub.data.ai.context.estimateTextTokens
import org.junit.Assert.*
import org.junit.Test

class ChatSuggestionContractTest {
    private val actions = setOf(ChatSuggestionAction.INSERT_TEXT, ChatSuggestionAction.ASK_USER)
    private fun JsonObject.itemFields() = getValue("properties").jsonObject.getValue("suggestions").jsonObject
        .getValue("items").jsonObject.getValue("properties").jsonObject

    @Test fun `response fields match the actual parser lengths and allowed actions`() {
        val config = ChatSuggestionConfig(count = 7, maxLength = 38)
        val schema = ChatSuggestionContract.responseSchema(config, actions, 2)
        val fields = schema.itemFields()
        assertEquals(38, fields.getValue("text").jsonObject.getValue("maxLength").jsonPrimitive.int)
        assertEquals(120, fields.getValue("description").jsonObject.getValue("maxLength").jsonPrimitive.int)
        assertEquals(500, fields.getValue("payload").jsonObject.getValue("maxLength").jsonPrimitive.int)
        assertEquals(setOf("insert_text", "ask_user"), fields.getValue("action").jsonObject.getValue("enum").jsonArray.map { it.jsonPrimitive.content }.toSet())
        assertEquals(2, schema.getValue("properties").jsonObject.getValue("suggestions").jsonObject.getValue("maxItems").jsonPrimitive.int)
        val parsed = normalizeGeneratedSuggestionItems("""{"suggestions":[{"text":"${"a".repeat(80)}","description":"${"b".repeat(200)}","payload":"${"c".repeat(600)}"}]}""", maxLength = 38).single()
        assertEquals(38, parsed.text.length); assertEquals(120, parsed.description.length); assertEquals(500, parsed.payload.length)
    }

    @Test fun `draft form schema excludes confirmation and includes the real rich and conditional fields`() {
        val schema = ChatSuggestionContract.responseSchema(ChatSuggestionConfig(), actions, 3)
        val questions = schema.itemFields().getValue("parameters").jsonObject.getValue("properties").jsonObject.getValue("questions").jsonObject
        assertEquals(5, questions.getValue("maxItems").jsonPrimitive.int)
        val fields = questions.getValue("items").jsonObject.getValue("properties").jsonObject
        assertFalse("danger" in fields); assertFalse("timeout_seconds" in fields)
        assertFalse(fields.getValue("selection_type").jsonObject.getValue("enum").jsonArray.any { it.jsonPrimitive.content == "confirm" })
        assertTrue("visible_if" in fields); assertTrue("option_details" in fields); assertTrue("default_values" in fields)
    }

    @Test fun `advertised example is accepted by the suggestion parser and produces a typed answer`() {
        val caps = ChatSuggestionContract.describe(ChatSuggestionConfig(), actions, details = true)
        val item = normalizeGeneratedSuggestionItems(caps.getValue("example").toString()).single()
        val request = AskUserProtocol.parseRequest(item.parameterForm!!).getOrThrow()
        val answer = AskUserProtocol.validateAnswer(request, """{"answers":{"focus":"performance"}}""").getOrThrow()
        assertTrue(answer.contains("performance"))
        assertEquals("Compare the options for performance.", QuickMessage(title = item.text, content = item.payload).render(mapOf("focus" to "performance")))
    }

    @Test fun `disabled forms do not leak schema or examples and roleplay categories are explicit`() {
        val config = ChatSuggestionConfig(style = ChatSuggestionStyle.ROLEPLAY, options = SuggestionOptions(parameterForms = false))
        val caps = ChatSuggestionContract.describe(config, actions, details = true)
        assertFalse("parameters" in caps.getValue("response_schema").jsonObject.itemFields())
        assertFalse("example" in caps); assertFalse("field_types" in caps)
        assertTrue(caps.getValue("parameter_form_actions").jsonArray.isEmpty())
        assertTrue(caps.getValue("categories").jsonArray.any { it.jsonPrimitive.content == "dialogue" })
        assertFalse(ChatSuggestionContract.categories(ChatSuggestionStyle.BALANCED).contains("dialogue"))
    }

    @Test fun `configuration discovery respects conversation overrides and hides private prompt data`() {
        val assistant = Assistant(systemPrompt = "PRIVATE_SYSTEM", suggestionSettings = ChatSuggestionConfig(count = 4))
        val config = ChatSuggestionConfig(count = 2, displayMode = ChatSuggestionDisplayMode.TWO_ROW, prompt = "PRIVATE_PROMPT",
            options = SuggestionOptions(goal = "PRIVATE_GOAL", includeMemory = true, trigger = SuggestionTrigger.MANUAL))
        val conversation = Conversation(assistantId = assistant.id, messageNodes = emptyList(),
            suggestionSession = SuggestionSession(settings = config, paused = true))
        val caps = conversation.suggestionCapabilities(Settings(assistants = listOf(assistant)), details = false, knowledgeAvailable = false)
        assertFalse(caps.getValue("enabled").jsonPrimitive.boolean)
        assertEquals(2, caps.getValue("settings").jsonObject.getValue("count").jsonPrimitive.int)
        assertEquals("two_row", caps.getValue("settings").jsonObject.getValue("display_mode").jsonPrimitive.content)
        assertFalse(caps.toString().contains("PRIVATE_")); assertFalse("response_schema" in caps)
        assertFalse("search_memory" in caps.getValue("actions").jsonObject)
    }

    @Test fun `form actions cannot skip the preview and leave unresolved placeholders`() {
        val form = """{"questions":[{"id":"value","question":"Value"}]}"""
        for (action in listOf("copy_text", "save_quick_message")) {
            assertTrue(normalizeGeneratedSuggestionItems("""[{"text":"Test","action":"$action","payload":"{{value}}","parameters":$form}]""").isEmpty())
        }
        assertNotNull(normalizeGeneratedSuggestionItems("""[{"text":"Test","action":"insert_text","payload":"{{value}}","parameters":$form}]""").single().parameterForm)
    }

    @Test fun `generator receives rendering and action semantics even with a custom prompt`() {
        val config = ChatSuggestionConfig(displayMode = ChatSuggestionDisplayMode.COMPACT, prompt = "custom")
        val instruction = suggestionGenerationInstruction(config, actions, 2, emptyList(), emptyList())
        assertTrue(instruction.contains("One visible text line"))
        assertTrue(instruction.contains("does not send a message or execute a tool"))
        assertTrue(instruction.contains("response_schema"))
        assertFalse(instruction.contains("\"image_draft\""))
    }

    @Test fun `new contract cost is reserved before allocating conversation context`() {
        val instruction = suggestionGenerationInstruction(ChatSuggestionConfig(), actions, 3, emptyList(), emptyList())
        val overhead = estimateTextTokens(instruction)
        assertEquals(500, suggestionContextBudget(3000, overhead + 1024 + 500, instruction))
        assertEquals(3000, suggestionContextBudget(3000, null, instruction))
        assertThrows(IllegalArgumentException::class.java) { suggestionContextBudget(3000, overhead + 1024 + 50, instruction) }
    }
}
