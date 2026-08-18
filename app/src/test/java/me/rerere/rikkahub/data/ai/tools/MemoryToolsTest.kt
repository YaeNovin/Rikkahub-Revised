package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.model.MemoryType
import org.junit.Assert.assertEquals
import org.junit.Test

class MemoryToolsTest {
    private val json = Json
    private val memory = AssistantMemory(
        id = 7,
        content = "User prefers concise Chinese replies.",
        type = MemoryType.FACT,
        createdAt = 1L,
    )

    @Test
    fun `memory tool reports not found for missing edit and delete`() = runBlocking {
        val tool = createTool(onDelete = { false }, onUpdate = { _, _ -> null })

        val edit = execute(tool, "edit") {
            put("id", 404)
            put("content", "updated")
        }
        val delete = execute(tool, "delete") { put("id", 404) }

        assertEquals("not_found", edit["status"]!!.jsonPrimitive.content)
        assertEquals("edit", edit["action"]!!.jsonPrimitive.content)
        assertEquals("not_found", delete["status"]!!.jsonPrimitive.content)
        assertEquals("delete", delete["action"]!!.jsonPrimitive.content)
    }

    @Test
    fun `memory tool lists current memory domain with paging metadata`() = runBlocking {
        val tool = createTool(onDelete = { true }, onUpdate = { _, _ -> memory })
        val result = execute(tool, "list") {
            put("offset", 0)
            put("limit", 1)
        }

        assertEquals("ok", result["status"]!!.jsonPrimitive.content)
        assertEquals(1, result["total"]!!.jsonPrimitive.content.toInt())
        assertEquals(7, result["memories"]!!.jsonArray.single().jsonObject["id"]!!.jsonPrimitive.content.toInt())
    }

    private fun createTool(
        onDelete: suspend (Int) -> Boolean,
        onUpdate: suspend (Int, String) -> AssistantMemory?,
    ) = buildMemoryTools(
        json = json,
        onCreation = { content, type -> memory.copy(content = content, type = type) },
        onUpdate = onUpdate,
        onDelete = onDelete,
        onList = { listOf(memory) },
    ).single()

    private suspend fun execute(
        tool: me.rerere.ai.core.Tool,
        action: String,
        block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {},
    ) = json.parseToJsonElement(
        (tool.execute(buildJsonObject {
            put("action", action)
            block()
        }).single() as UIMessagePart.Text).text
    ).jsonObject
}
