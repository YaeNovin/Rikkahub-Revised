package me.rerere.rikkahub.ui.pages.assistant.detail

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantImporterTest {
    @Test
    fun `embedded Tavern world book maps entries and selective keywords`() {
        val data = Json.parseToJsonElement(
            """
            {
              "character_book": {
                "name": "City Lore",
                "scan_depth": 6,
                "token_budget": 800,
                "entries": [
                  {
                    "name": "Night event",
                    "keys": ["night", "moon"],
                    "secondary_keys": ["city"],
                    "selective": true,
                    "content": "The gates are closed.",
                    "insertion_order": 42,
                    "position": "after_char",
                    "extensions": {"useProbability": true, "probability": 35}
                  }
                ]
              }
            }
            """.trimIndent()
        ).jsonObject

        val lorebook = parseEmbeddedTavernLorebook(data, "Character")
        assertNotNull(lorebook)
        assertEquals("City Lore", lorebook!!.name)
        assertEquals(800, lorebook.tokenBudget)
        val entry = lorebook.entries.single()
        assertEquals(42, entry.priority)
        assertEquals(6, entry.scanDepth)
        assertEquals(35, entry.triggerProbability)
        assertTrue(entry.keywordExpression.contains("AND"))
        assertTrue(entry.keywordExpression.contains("night"))
        assertTrue(entry.keywordExpression.contains("city"))
    }

    @Test
    fun `embedded Tavern world book accepts object entry maps`() {
        val data = Json.parseToJsonElement(
            """
            {
              "character_book": {
                "entries": {
                  "0": {"key": ["magic"], "content": "Magic exists.", "constant": false}
                }
              }
            }
            """.trimIndent()
        ).jsonObject

        val lorebook = parseEmbeddedTavernLorebook(data, "Mage")
        assertEquals("Mage World Book", lorebook?.name)
        assertEquals(listOf("magic"), lorebook?.entries?.single()?.keywords)
    }
}
