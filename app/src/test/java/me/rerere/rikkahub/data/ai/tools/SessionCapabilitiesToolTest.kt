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
    @Test fun `disabled image capability omits all invocation rules even when autonomy is configured`() = runBlocking {
        val tool = createSessionCapabilitiesTool(Assistant(enableImageGenerationTool = true, autoApproveImageGeneration = true),
            true, { listOf("ask_user", "get_session_capabilities") })
        val output = tool.execute(buildJsonObject { put("include_ask_user_details", true) }).single() as UIMessagePart.Text
        val payload = Json.parseToJsonElement(output.text).jsonObject
        val image = payload.getValue("image_generation").jsonObject
        assertEquals(setOf("enabled", "status"), image.keys)
        assertEquals("false", image.getValue("enabled").jsonPrimitive.content)
        assertEquals("disabled", image.getValue("status").jsonPrimitive.content)
        assertEquals("true", payload.getValue("ask_user").jsonObject.getValue("enabled").jsonPrimitive.content)
        assertFalse(payload.getValue("ask_user").toString().contains(CHAT_IMAGE_TOOL_NAME))
        assertFalse(payload.getValue("ask_user").toString().contains("image_generation"))
    }

    @Test fun `models without tool calling cannot advertise configured image tools`() = runBlocking {
        val tool = createSessionCapabilitiesTool(Assistant(enableImageGenerationTool = true), false,
            { listOf(CHAT_IMAGE_TOOL_NAME, INSPECT_GENERATED_IMAGE_TOOL_NAME, "ask_user") })
        val output = tool.execute(buildJsonObject {}).single() as UIMessagePart.Text
        val payload = Json.parseToJsonElement(output.text).jsonObject
        assertTrue(payload.getValue("availableTools").jsonArray.isEmpty())
        assertEquals("disabled", payload.getValue("image_generation").jsonObject.getValue("status").jsonPrimitive.content)
        assertFalse(output.text.contains(CHAT_IMAGE_TOOL_NAME))
    }

    @Test fun `image capabilities separate invocation parameter authority and inspection`() = runBlocking {
        for (automatic in listOf(true, false)) {
            val tool = createSessionCapabilitiesTool(Assistant(enableImageGenerationTool = true,
                autoApproveImageGeneration = automatic), true,
                { listOf(CHAT_IMAGE_TOOL_NAME, INSPECT_GENERATED_IMAGE_TOOL_NAME, "ask_user") })
            val output = tool.execute(buildJsonObject {}).single() as UIMessagePart.Text
            val image = Json.parseToJsonElement(output.text).jsonObject.getValue("image_generation").jsonObject
            assertEquals(CHAT_IMAGE_TOOL_NAME, image.getValue("tool").jsonPrimitive.content)
            assertEquals((!automatic).toString(), image.getValue("requires_user_confirmation").jsonPrimitive.content)
            assertEquals("true", image.getValue("parameters_selected_by_user").jsonPrimitive.content)
            assertEquals("false", image.getValue("ask_user_changes_parameters").jsonPrimitive.content)
            assertEquals("on_demand", image.getValue("inspection").jsonPrimitive.content)
        }
    }

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
