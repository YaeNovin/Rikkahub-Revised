package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import kotlin.uuid.Uuid

class SessionCapabilitiesToolTest {
    @Test fun `suggestion details are opt in and independent from ask user details`() = runBlocking {
        val tool = createSessionCapabilitiesTool(Assistant(), true, { listOf("ask_user") },
            suggestionCapabilities = { details -> buildJsonObject {
                put("enabled", true)
                if (details) put("response_schema", "example-schema")
            } })
        suspend fun query(details: Boolean) = Json.parseToJsonElement((tool.execute(buildJsonObject {
            put("include_suggestion_details", details)
        }).single() as UIMessagePart.Text).text).jsonObject
        assertFalse("response_schema" in query(false).getValue("chat_suggestions").jsonObject)
        val expanded = query(true)
        assertTrue("response_schema" in expanded.getValue("chat_suggestions").jsonObject)
        assertFalse("examples" in expanded.getValue("ask_user").jsonObject)
    }
    @Test fun `ask user discovery exposes real capabilities only when the tool is available`() = runBlocking {
        for (enabled in listOf(true, false)) {
            val tool = createSessionCapabilitiesTool(Assistant(), enabled, { listOf("ask_user") })
            val result = tool.execute(buildJsonObject { put("include_ask_user_details", true) }).single() as UIMessagePart.Text
            val ask = Json.parseToJsonElement(result.text).jsonObject["ask_user"]!!.jsonObject
            assertEquals(enabled.toString(), ask["enabled"]!!.jsonPrimitive.content)
            assertEquals(enabled, "examples" in ask)
            if (enabled) assertEquals(8, ask["selection_types"]!!.jsonArray.size)
        }
    }
    @Test
    fun `session capabilities report configured modes and tool names`() = runBlocking {
        val tool = createSessionCapabilitiesTool(
            assistant = Assistant(
                enableMemory = true,
                enableMemoryRag = true,
                enableEpisodicMemory = true,
                knowledgeBaseIds = setOf(Uuid.random()),
                enableWebSearch = true,
            ),
            toolCallsAvailable = true,
            availableToolNames = { listOf("kb_search", "memory_tool", "kb_search", "get_session_capabilities") },
        )

        val output = tool.execute(buildJsonObject {}).single() as UIMessagePart.Text
        val payload = Json.parseToJsonElement(output.text).jsonObject

        assertEquals("rag_background", payload["memory"]!!.jsonObject["mode"]!!.jsonPrimitive.content)
        assertEquals(
            "background_silent_with_forced_sources",
            payload["knowledgeBase"]!!.jsonObject["retrievalMode"]!!.jsonPrimitive.content,
        )
        assertTrue(payload["availableTools"]!!.jsonArray.map { it.jsonPrimitive.content }.contains("kb_search"))
        assertTrue(payload["availableTools"]!!.jsonArray.map { it.jsonPrimitive.content }.contains("get_session_capabilities"))
    }

    @Test
    fun `session capabilities distinguish disabled and tool-only knowledge bases`() = runBlocking {
        val assistant = Assistant(knowledgeBaseIds = setOf(Uuid.random(), Uuid.random()))

        val disabled = createSessionCapabilitiesTool(
            assistant = assistant,
            toolCallsAvailable = true,
            availableToolNames = { emptyList() },
            knowledgeBaseCapabilities = KnowledgeBaseCapabilities(
                boundCount = 2,
                enabledCount = 0,
                ragEnabledCount = 0,
            ),
        )
        val toolOnly = createSessionCapabilitiesTool(
            assistant = assistant,
            toolCallsAvailable = true,
            availableToolNames = { listOf("kb_search") },
            knowledgeBaseCapabilities = KnowledgeBaseCapabilities(
                boundCount = 2,
                enabledCount = 1,
                ragEnabledCount = 0,
            ),
        )

        suspend fun retrievalMode(tool: me.rerere.ai.core.Tool): String {
            val output = tool.execute(buildJsonObject {}).single() as UIMessagePart.Text
            return Json.parseToJsonElement(output.text)
                .jsonObject["knowledgeBase"]!!
                .jsonObject["retrievalMode"]!!
                .jsonPrimitive
                .content
        }

        assertEquals("disabled_by_user", retrievalMode(disabled))
        assertEquals("tool_only", retrievalMode(toolOnly))
    }
}
