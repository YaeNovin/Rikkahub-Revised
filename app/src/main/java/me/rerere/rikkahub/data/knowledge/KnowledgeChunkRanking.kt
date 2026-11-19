package me.rerere.rikkahub.data.knowledge

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield
import me.rerere.rikkahub.data.db.dao.KnowledgeChunkSearchRow
import java.util.PriorityQueue

/** Exact top-k over all pages, retaining only one page and k winners in memory. */
internal suspend fun rankKnowledgePages(
    limit: Int,
    loadPage: suspend (afterId: String, pageSize: Int) -> List<KnowledgeChunkSearchRow>,
    score: (KnowledgeChunkSearchRow) -> Float,
): List<Pair<KnowledgeChunkSearchRow, Float>> {
    require(limit in 1..100)
    val order = compareBy<Pair<KnowledgeChunkSearchRow, Float>> { it.second }
        .thenByDescending { it.first.id }
    val best = PriorityQueue(limit, order)
    var afterId = ""
    while (true) {
        currentCoroutineContext().ensureActive()
        val page = loadPage(afterId, 128)
        if (page.isEmpty()) break
        for (row in page) {
            currentCoroutineContext().ensureActive()
            val value = score(row)
            if (!value.isFinite() || value <= 0f) continue
            val candidate = row to value
            if (best.size < limit) best.add(candidate)
            else if (order.compare(candidate, best.peek()) > 0) {
                best.poll()
                best.add(candidate)
            }
        }
        check(page.last().id > afterId) { "Knowledge pagination did not advance" }
        afterId = page.last().id
        yield()
    }
    return best.sortedWith(order.reversed())
}

internal fun knowledgeLexicalScorer(query: String): (KnowledgeChunkSearchRow) -> Float {
    val normalized = query.trim().lowercase()
    val terms = normalized.split(Regex("[\\s\\p{Punct}]+"))
        .filter { it.length >= 2 }
    val cjk = normalized.filter {
        it in '\u3040'..'\u30ff' || it in '\u3400'..'\u4dbf' || it in '\u4e00'..'\u9fff'
    }.windowed(2)
    val searchTerms = (terms + cjk).distinct()
    return { row ->
        val text = row.content.lowercase()
        when {
            normalized.isBlank() -> 0f
            searchTerms.isEmpty() -> if (text.contains(normalized)) 1f else 0f
            else -> searchTerms.count(text::contains).toFloat() / searchTerms.size
        }
    }
}
