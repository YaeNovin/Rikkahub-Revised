package me.rerere.rikkahub.ui.pages.search

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.db.fts.MessageSearchResult
import me.rerere.rikkahub.data.db.fts.MessageSearchSort

data class MessageSearchState(
    val query: String = "",
    val sort: MessageSearchSort = MessageSearchSort.RELEVANCE,
    val results: List<MessageSearchResult> = emptyList(),
    val loading: Boolean = false,
    val hasMore: Boolean = false,
    val error: String? = null,
)

/** Main-thread owned paging state; a superseded request can never publish results. */
internal class MessageSearchPager(
    private val scope: CoroutineScope,
    initialSort: MessageSearchSort,
    private val fetch: suspend (String, MessageSearchSort, Int, Int) -> List<MessageSearchResult>,
) {
    private val mutableState = MutableStateFlow(MessageSearchState(sort = initialSort))
    val state = mutableState.asStateFlow()
    private var request: Job? = null
    private var generation = 0L
    private var nextOffset = 0

    fun changeQuery(query: String) = reset(query = query, debounce = true)
    fun changeSort(sort: MessageSearchSort) = reset(sort = sort)
    fun refresh() = reset()

    fun cancel() {
        generation++
        request?.cancel()
        mutableState.value = mutableState.value.copy(loading = false)
    }

    private fun reset(
        query: String = state.value.query,
        sort: MessageSearchSort = state.value.sort,
        debounce: Boolean = false,
    ) {
        cancel()
        nextOffset = 0
        mutableState.value = MessageSearchState(query = query, sort = sort)
        if (query.isNotBlank()) load(debounce)
    }

    fun loadMore() {
        if (state.value.loading || !state.value.hasMore) return
        load(debounce = false)
    }

    private fun load(debounce: Boolean) {
        val current = state.value
        val token = generation
        val offset = nextOffset
        mutableState.value = current.copy(loading = true, error = null)
        request = scope.launch {
            try {
                if (debounce) delay(300)
                val rows = fetch(current.query, current.sort, PAGE_SIZE + 1, offset)
                if (generation != token) return@launch
                val page = rows.take(PAGE_SIZE)
                nextOffset = offset + page.size
                mutableState.value = current.copy(
                    results = (current.results + page).distinctBy { it.messageId },
                    hasMore = rows.size > PAGE_SIZE,
                    error = null,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (generation == token) {
                    mutableState.value = current.copy(error = error.message ?: "搜索失败，请重试")
                }
            } finally {
                if (generation == token) {
                    mutableState.value = mutableState.value.copy(loading = false)
                }
            }
        }
    }

    companion object {
        const val PAGE_SIZE = 50
    }
}
