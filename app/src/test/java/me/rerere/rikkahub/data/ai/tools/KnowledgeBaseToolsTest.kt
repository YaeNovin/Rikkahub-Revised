package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.dao.KnowledgeChunkSearchRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowledgeBaseToolsTest {
    @Test
    fun `knowledge search tool exposes query schema and structured excerpts`() = runBlocking {
        var receivedQuery = ""
        var receivedLimit = 0
        val tool = createKnowledgeSearchTool { query, limit ->
            receivedQuery = query
            receivedLimit = limit
            listOf(
                KnowledgeChunkSearchRow(
                    id = "chunk-1",
                    documentId = "document-1",
                    knowledgeBaseId = "base-1",
                    ordinal = 0,
                    content = "The launch date is August 16.",
                    pageStart = 3,
                    pageEnd = 3,
                    sectionPath = "Schedule",
                    charStart = 0,
                    charEnd = 29,
                    embedding = null,
                    embeddingModelId = null,
                    embeddingDimension = null,
                    documentTitle = "Project plan",
                    sourceUri = "file:///project-plan.md",
                )
            )
        }

        assertEquals("kb_search", tool.name)
        val schema = tool.parameters() as InputSchema.Obj
        assertEquals(listOf("query"), schema.required)
        assertTrue(schema.properties.containsKey("query"))
        assertTrue(schema.properties.containsKey("limit"))

        val output = tool.execute(
            buildJsonObject {
                put("query", "launch date")
                put("limit", 99)
            }
        ).single() as UIMessagePart.Text
        val payload = Json.parseToJsonElement(output.text).jsonObject
        val result = payload["results"]!!.jsonArray.single().jsonObject

        assertEquals("launch date", receivedQuery)
        assertEquals(6, receivedLimit)
        assertEquals("chunk-1", result["chunkId"]!!.jsonPrimitive.content)
        assertEquals(3, result["pageStart"]!!.jsonPrimitive.content.toInt())
        assertEquals("Schedule", result["sectionPath"]!!.jsonPrimitive.content)
    }
}
