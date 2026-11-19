package me.rerere.rikkahub.data.knowledge

import me.rerere.rikkahub.data.db.entity.KnowledgeChunkEntity
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class KnowledgeImportCheckpointTest {
    private fun chunk(index: Int) = KnowledgeChunkEntity(
        id = "old-$index", documentId = "document", knowledgeBaseId = "base",
        ordinal = index, content = "part $index", pageStart = 1, pageEnd = 1,
        charStart = index * 6, charEnd = index * 6 + 6,
    )
    private fun vector(vararg values: Float) = ByteBuffer.allocate(values.size * 4)
        .order(ByteOrder.LITTLE_ENDIAN).apply { values.forEach { putFloat(it) } }.array()

    @Test fun `unchanged parsed chunks retain their old identifiers and completed vectors`() {
        val old = (0..2).map(::chunk)
        val parsed = old.map { it.copy(id = "new-${it.ordinal}") }
        assertTrue(canReuseKnowledgeChunks(old, parsed))
        assertFalse(canReuseKnowledgeChunks(old, parsed.dropLast(1)))
        assertFalse(canReuseKnowledgeChunks(old, parsed.reversed()))
        assertFalse(canReuseKnowledgeChunks(old, parsed.map { it.copy(pageStart = 2) }))
        assertFalse(canReuseKnowledgeChunks(old, parsed.map { it.copy(content = "changed") }))
    }

    @Test fun `only completed chunks from the same embedding model are skipped on resume`() {
        val done = chunk(0).copy(embedding = vector(0.1f, 0.9f), embeddingModelId = "model", embeddingDimension = 2)
        assertTrue(done.hasUsableEmbedding("model"))
        assertFalse(chunk(1).hasUsableEmbedding("model"))
        assertFalse(done.hasUsableEmbedding("other-model"))
        assertFalse(done.hasUsableEmbedding(null))
        assertFalse(done.copy(embedding = null).hasUsableEmbedding("model"))
        assertFalse(done.copy(embeddingDimension = 3).hasUsableEmbedding("model"))
        assertFalse(done.copy(embedding = vector(Float.NaN, 1f)).hasUsableEmbedding("model"))
        assertFalse(done.copy(embedding = vector(Float.POSITIVE_INFINITY, 1f)).hasUsableEmbedding("model"))
    }
}
