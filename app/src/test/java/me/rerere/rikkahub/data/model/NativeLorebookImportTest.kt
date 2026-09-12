package me.rerere.rikkahub.data.model

import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.ai.transformers.evaluateInjections
import me.rerere.rikkahub.data.export.*
import org.junit.Assert.*
import org.junit.Test

class NativeLorebookImportTest {
    private fun fixture() = javaClass.getResourceAsStream("/lorebook-native-legacy-sample.json")!!.bufferedReader().use { it.readText() }

    @Test fun `legacy native sample retains all native settings and positions`() {
        val text = fixture()
        val original = ExportSerializer.DefaultJson.decodeFromJsonElement<Lorebook>(Json.parseToJsonElement(text).jsonObject.getValue("data"))
        val imported = decodeLorebookDocument(text, "fallback")
        assertEquals(34, imported.entries.size)
        original.entries.zip(imported.entries).forEach { (before, after) ->
            assertNotEquals(before.id, after.id)
            assertEquals(before, after.copy(id = before.id))
        }
        assertEquals(5, imported.entries.count { it.scanDepth == 0 && it.constantActive })
        assertEquals(4, imported.entries.count { !it.enabled })
        assertEquals(InjectionPosition.TOP_OF_CHAT, imported.entries[0].position)
        assertEquals(InjectionPosition.BOTTOM_OF_CHAT, imported.entries[20].position)
        assertEquals(34, imported.entries.map { it.id }.toSet().size)
        listOf(false, true).forEach { entertainment ->
            val result = evaluateInjections(listOf(UIMessage.user("unmatched")), Assistant(lorebookIds = setOf(imported.id)), emptyList(), listOf(imported), entertainmentMode = entertainment)
            assertEquals(5, result.injections.size)
        }
    }
    @Test fun `current export reimport preserves advanced options without history`() {
        val rule = PromptInjection.RegexInjection(name = "r", content = "body", constantActive = true, scanMode = LorebookScanMode.INHERIT,
            scanSource = LorebookScanSource.USER, exclusiveGroup = "group", groupOverride = true, selectionWeight = 20, stickyTurns = 3, cooldownTurns = 2)
        val book = Lorebook(name = "book", defaultScanDepth = 0, tokenBudget = 77, overflowStrategy = LorebookOverflowStrategy.SKIP_BOOK, entries = listOf(rule))
        val imported = decodeLorebookDocument(LorebookSerializer.exportToJson(book), "fallback")
        assertEquals(book, imported.copy(id = book.id, entries = imported.entries.map { it.copy(id = rule.id) }))
    }
    @Test fun `recognized invalid native format never silently becomes Tavern`() {
        val error = runCatching { decodeLorebookDocument("""{"version":1,"type":"lorebook","data":{"entries":[{"name":"r","position":"unknown"}]},"entries":{}}""", "fallback") }.exceptionOrNull()
        assertNotNull(error)
        assertTrue(error!!.message!!.contains("第 1 个原生条目"))
    }
    @Test fun `unsupported version reports meaningful error`() {
        val error = runCatching { decodeLorebookDocument("""{"version":99,"type":"lorebook","data":{"entries":[]}}""", "fallback") }.exceptionOrNull()
        assertTrue(error!!.message!!.contains("版本"))
    }
    @Test fun `duplicate or obsolete ids are replaced without discarding records`() {
        val book = decodeLorebookDocument("""{"version":1,"type":"lorebook","data":{"id":"old","name":"B","entries":[{"id":"old","name":"a","enabled":false},{"id":"old","name":"b","enabled":false}]}}""", "fallback")
        assertEquals(2, book.entries.size)
        assertNotEquals(book.entries[0].id, book.entries[1].id)
    }
}
