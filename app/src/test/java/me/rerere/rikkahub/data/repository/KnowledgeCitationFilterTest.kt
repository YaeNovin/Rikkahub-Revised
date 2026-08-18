package me.rerere.rikkahub.data.repository

import me.rerere.ai.ui.UIMessageAnnotation
import org.junit.Assert.assertEquals
import org.junit.Test

class KnowledgeCitationFilterTest {
    @Test
    fun `filters citations whose chunks were deleted before persistence`() {
        val existing = citation("existing")
        val deleted = citation("deleted")

        assertEquals(listOf(existing), listOf(existing, deleted).filterExistingChunks(setOf("existing")))
    }

    private fun citation(chunkId: String) = UIMessageAnnotation.KnowledgeCitation(
        chunkId = chunkId,
        knowledgeBaseId = "base",
        documentId = "document",
        title = "Source",
        sourceUri = "file:///source",
        excerpt = "Excerpt",
        score = 1f,
    )
}
