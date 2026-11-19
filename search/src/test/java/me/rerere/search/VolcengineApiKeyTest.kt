package me.rerere.search

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class VolcengineApiKeyTest {
    @Test fun `edition keys remain independent across switches edits and serialization`() {
        val custom = SearchServiceOptions.VolcengineOptions().withActiveApiKey("custom-fixture")
        val global = custom.copy(global = true).withActiveApiKey("global-fixture")
        assertEquals("global-fixture", global.activeApiKey())
        assertEquals("custom-fixture", global.copy(global = false).activeApiKey())
        assertEquals(global, Json.decodeFromString<SearchServiceOptions.VolcengineOptions>(Json.encodeToString(global)))
    }

    @Test fun `legacy key remains available until an edition is explicitly edited or cleared`() {
        val legacy = Json.decodeFromString<SearchServiceOptions.VolcengineOptions>("""{"apiKey":"legacy-fixture"}""")
        assertEquals("legacy-fixture", legacy.activeApiKey())
        assertEquals("legacy-fixture", legacy.copy(global = true).activeApiKey())
        val changed = legacy.withActiveApiKey("")
        assertEquals("", changed.activeApiKey())
        assertEquals("legacy-fixture", changed.copy(global = true).activeApiKey())
    }

    @Test fun `subscription key error tells the user to use the appropriate edition key`() {
        val error = assertThrows(IllegalStateException::class.java) {
            parseVolcengineSearchResponse("""{"ResponseMetadata":{"Error":{"Code":"10409","Message":"unsupported subscription"}}}""", true)
        }
        assertTrue(error.message.orEmpty().contains("Global 不支持订阅套餐 Key"))
    }
}
