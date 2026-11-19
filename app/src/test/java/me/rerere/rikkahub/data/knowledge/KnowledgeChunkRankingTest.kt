package me.rerere.rikkahub.data.knowledge

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.db.dao.KnowledgeChunkSearchRow
import org.junit.Assert.*
import org.junit.Test

class KnowledgeChunkRankingTest {
    private fun row(index: Int, content: String = "unrelated") = KnowledgeChunkSearchRow(
        id = index.toString().padStart(6, '0'), documentId = "document", knowledgeBaseId = "base",
        ordinal = index, content = content, pageStart = null, pageEnd = null, sectionPath = "",
        charStart = 0, charEnd = content.length, embedding = null, embeddingModelId = null,
        embeddingDimension = null, documentTitle = "document", sourceUri = "source",
    )

    @Test fun `lexical match beyond both old candidate limits is found`() = runBlocking {
        val rows = (0 until 2505).map { row(it, if (it == 2504) "稀有知识检索目标" else "unrelated") }
        val pageSizes = mutableListOf<Int>()
        val matches = rankKnowledgePages(6, { after, size ->
            pageSizes += size
            rows.filter { it.id > after }.take(size)
        }, knowledgeLexicalScorer("稀有知识检索目标"))
        assertEquals(listOf(rows.last()), matches.map { it.first })
        assertTrue(pageSizes.size > 10)
        assertTrue(pageSizes.all { it <= 128 })
    }

    @Test fun `top k is global and deterministic across page boundaries`() = runBlocking {
        val rows = (0 until 1500).map { row(it) }
        val matches = rankKnowledgePages(4, { after, size -> rows.filter { it.id > after }.take(size) }) {
            (it.ordinal % 20).toFloat()
        }
        assertEquals(listOf(19, 39, 59, 79), matches.map { it.first.ordinal })
        assertTrue(matches.all { it.second == 19f })
    }

    @Test fun `invalid scores and empty query do not produce false matches`() = runBlocking {
        val rows = (0..4).map { row(it) }
        val values = listOf(Float.NaN, Float.POSITIVE_INFINITY, -1f, 0f, 0.9f)
        val matches = rankKnowledgePages(6, { after, size -> rows.filter { it.id > after }.take(size) }) { values[it.ordinal] }
        assertEquals(listOf(rows.last()), matches.map { it.first })
        assertEquals(0f, knowledgeLexicalScorer("  ")(rows.first()))
    }

    @Test fun `cancelled page load propagates cancellation`() = runBlocking {
        val result = runCatching {
            rankKnowledgePages(6, { _, _ -> throw CancellationException("cancelled") }) { 1f }
        }
        assertTrue(result.exceptionOrNull() is CancellationException)
    }
}
