package me.rerere.rikkahub.ui.components.message.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class MemoryToolResultTest {
    @Test
    fun `parses current nested create and edit result`() {
        val result = parseMemoryToolResult(
            buildJsonObject {
                put("status", "created")
                put("memory", buildJsonObject {
                    put("id", 12)
                    put("content", "Visited Hangzhou")
                    put("type", "episodic")
                })
            }
        )

        assertEquals(12, result.id)
        assertEquals("Visited Hangzhou", result.content)
        assertEquals("episodic", result.type)
    }

    @Test
    fun `keeps legacy top level result compatible`() {
        val result = parseMemoryToolResult(
            buildJsonObject {
                put("id", 7)
                put("content", "Prefers concise replies")
                put("type", "fact")
            }
        )

        assertEquals(7, result.id)
        assertEquals("Prefers concise replies", result.content)
        assertEquals("fact", result.type)
    }

    @Test
    fun `parses list result count`() {
        val result = parseMemoryToolResult(
            buildJsonObject {
                put("status", "ok")
                put("total", 23)
            }
        )

        assertEquals(23, result.total)
    }
}
