package me.rerere.rikkahub.ui.pages.search

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.db.fts.MessageSearchResult
import me.rerere.rikkahub.data.db.fts.MessageSearchSort
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class MessageSearchPagerTest {
    private fun row(id: Int) = MessageSearchResult("node-$id", "message-$id", "chat", "title", Instant.EPOCH, "snippet")

    @Test fun `all pages remain reachable beyond fifty hits`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val rows = (0 until 123).map(::row)
            val offsets = mutableListOf<Int>()
            val pager = MessageSearchPager(scope, MessageSearchSort.RELEVANCE) { _, _, limit, offset ->
                offsets += offset
                rows.drop(offset).take(limit)
            }
            pager.changeQuery("query")
            pager.refresh()
            assertEquals(50, pager.state.value.results.size)
            assertTrue(pager.state.value.hasMore)
            pager.loadMore()
            pager.loadMore()
            assertEquals(rows, pager.state.value.results)
            assertEquals(listOf(0, 50, 100), offsets)
            assertFalse(pager.state.value.hasMore)
            pager.loadMore()
            assertEquals(3, offsets.size)
        } finally { scope.cancel() }
    }

    @Test fun `superseded query cannot overwrite newer results even if its fetch ignores cancellation`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val release = CompletableDeferred<Unit>()
        try {
            val pager = MessageSearchPager(scope, MessageSearchSort.RELEVANCE) { query, _, _, _ ->
                if (query == "old") withContext(NonCancellable) { release.await(); listOf(row(1)) }
                else listOf(row(2))
            }
            pager.changeQuery("old")
            pager.refresh()
            pager.changeQuery("new")
            pager.refresh()
            assertEquals(listOf(row(2)), pager.state.value.results)
            release.complete(Unit)
            assertEquals("new", pager.state.value.query)
            assertEquals(listOf(row(2)), pager.state.value.results)
            assertFalse(pager.state.value.loading)
            pager.changeQuery("")
            assertTrue(pager.state.value.results.isEmpty())
            assertFalse(pager.state.value.loading)
        } finally { release.complete(Unit); scope.cancel() }
    }

    @Test fun `failed next page preserves hits and retries the same offset`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            var fail = true
            val pager = MessageSearchPager(scope, MessageSearchSort.RELEVANCE) { _, _, limit, offset ->
                if (offset == 50 && fail) error("temporary failure")
                (0 until 60).map(::row).drop(offset).take(limit)
            }
            pager.changeQuery("query")
            pager.refresh()
            pager.loadMore()
            assertEquals(50, pager.state.value.results.size)
            assertNotNull(pager.state.value.error)
            assertTrue(pager.state.value.hasMore)
            fail = false
            pager.loadMore()
            assertEquals(60, pager.state.value.results.size)
            assertNull(pager.state.value.error)
        } finally { scope.cancel() }
    }

    @Test fun `changing sort discards previous pages and resets the offset`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val calls = mutableListOf<Pair<MessageSearchSort, Int>>()
            val pager = MessageSearchPager(scope, MessageSearchSort.RELEVANCE) { _, sort, limit, offset ->
                calls += sort to offset
                (0 until 100).map(::row).drop(offset).take(limit)
            }
            pager.changeQuery("query")
            pager.refresh()
            pager.loadMore()
            pager.changeSort(MessageSearchSort.NEWEST_FIRST)
            assertEquals(MessageSearchSort.NEWEST_FIRST to 0, calls.last())
            assertEquals(50, pager.state.value.results.size)
        } finally { scope.cancel() }
    }
}
