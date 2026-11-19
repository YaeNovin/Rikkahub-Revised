package me.rerere.rikkahub.ui.pages.search

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.db.fts.MessageSearchSort
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.ui.hooks.readStringPreference
import me.rerere.rikkahub.ui.hooks.writeStringPreference

private const val SORT_ORDER_PREF_KEY = "search_page_sort_order"

class SearchVM(
    private val context: Application,
    private val conversationRepo: ConversationRepository,
) : ViewModel() {
    private val pager = MessageSearchPager(
        scope = viewModelScope,
        initialSort = runCatching {
            MessageSearchSort.valueOf(
                context.readStringPreference(SORT_ORDER_PREF_KEY, MessageSearchSort.RELEVANCE.name)!!
            )
        }.getOrDefault(MessageSearchSort.RELEVANCE),
        fetch = { query, sort, limit, offset -> conversationRepo.searchMessages(query, sort, limit, offset) },
    )
    val state = pager.state
    var isRebuilding by mutableStateOf(false)
        private set
    var rebuildProgress by mutableStateOf(0 to 0)
        private set
    var rebuildError by mutableStateOf<String?>(null)
        private set

    fun onQueryChange(query: String) {
        pager.changeQuery(query)
        if (isRebuilding) pager.cancel()
    }

    fun onSortChange(sort: MessageSearchSort) {
        if (state.value.sort == sort) return
        context.writeStringPreference(SORT_ORDER_PREF_KEY, sort.name)
        pager.changeSort(sort)
        if (isRebuilding) pager.cancel()
    }

    fun search() {
        if (!isRebuilding) pager.refresh()
    }

    fun loadMore() { if (!isRebuilding) pager.loadMore() }

    fun rebuildIndex() {
        if (isRebuilding) return
        isRebuilding = true
        rebuildError = null
        pager.cancel()
        viewModelScope.launch {
            rebuildProgress = 0 to 0
            try {
                conversationRepo.rebuildAllIndexes { current, total ->
                    rebuildProgress = current to total
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                rebuildError = error.message ?: "索引重建失败，请重试"
            } finally {
                isRebuilding = false
            }
            pager.refresh()
        }
    }
}
