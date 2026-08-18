package me.rerere.rikkahub.data.repository

import me.rerere.rikkahub.data.db.dao.KnowledgeDocumentPreviewChunk
import org.junit.Assert.assertEquals
import org.junit.Test

class KnowledgeDocumentPreviewTest {
    @Test
    fun `reconstructs indexed text without chunk overlap`() {
        val preview = buildKnowledgeDocumentPreview(
            listOf(
                chunk(ordinal = 0, content = "abcdefghij", start = 0, end = 10),
                chunk(ordinal = 1, content = "ijklmnop", start = 8, end = 16),
                chunk(ordinal = 2, content = "next paragraph", start = 20, end = 34),
            )
        )

        assertEquals("abcdefghijklmnop\n\nnext paragraph", preview)
    }

    @Test
    fun `orders chunks and ignores content already covered`() {
        val preview = buildKnowledgeDocumentPreview(
            listOf(
                chunk(ordinal = 1, content = "def", start = 3, end = 6),
                chunk(ordinal = 0, content = "abcdef", start = 0, end = 6),
            )
        )

        assertEquals("abcdef", preview)
    }

    private fun chunk(
        ordinal: Int,
        content: String,
        start: Int,
        end: Int,
    ) = KnowledgeDocumentPreviewChunk(
        ordinal = ordinal,
        content = content,
        pageStart = null,
        pageEnd = null,
        sectionPath = "",
        charStart = start,
        charEnd = end,
    )
}
