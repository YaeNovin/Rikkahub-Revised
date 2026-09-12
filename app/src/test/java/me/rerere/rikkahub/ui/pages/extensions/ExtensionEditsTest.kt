package me.rerere.rikkahub.ui.pages.extensions

import org.junit.Assert.assertEquals
import org.junit.Test

class ExtensionEditsTest {
    private data class Entry(val id: Int, val text: String)

    @Test fun `editing one item preserves concurrent additions and other changes`() {
        val before = listOf(Entry(1, "old"), Entry(2, "old"))
        val edited = listOf(Entry(1, "edit"), Entry(2, "old"))
        val current = listOf(Entry(1, "old"), Entry(2, "new"), Entry(3, "added"))
        assertEquals(listOf(Entry(1, "edit"), Entry(2, "new"), Entry(3, "added")), mergeExtensionEdits(before, edited, current) { it.id })
    }

    @Test fun `stale edit does not resurrect a deleted item`() {
        assertEquals(emptyList<Entry>(), mergeExtensionEdits(listOf(Entry(1, "old")), listOf(Entry(1, "edit")), emptyList()) { it.id })
    }

    @Test fun `drag reorder retains newer content and concurrent additions`() {
        val before = listOf(Entry(1, "old"), Entry(2, "old"))
        assertEquals(listOf(Entry(2, "new"), Entry(1, "old"), Entry(3, "added")),
            mergeExtensionEdits(before, before.reversed(), listOf(Entry(1, "old"), Entry(2, "new"), Entry(3, "added"))) { it.id })
    }
}
