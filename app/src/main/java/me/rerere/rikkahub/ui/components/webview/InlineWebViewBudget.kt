package me.rerere.rikkahub.ui.components.webview

import androidx.compose.runtime.mutableStateMapOf

internal const val MAX_INLINE_WEBVIEWS = 6

internal fun admittedWebViewIds(priorities: Map<Long, Long>, maximum: Int = MAX_INLINE_WEBVIEWS): Set<Long> =
    priorities.entries.sortedWith(compareBy<Map.Entry<Long, Long>> { it.value }.thenBy { it.key })
        .take(maximum.coerceAtLeast(0)).mapTo(linkedSetOf()) { it.key }

internal object InlineWebViewBudget {
    private var nextId = 0L
    private var nextRequest = 0L
    val priorities = mutableStateMapOf<Long, Long>()
    fun allocate(): Long = ++nextId
    fun promote(): Long = --nextRequest
    fun allows(id: Long): Boolean = id in admittedWebViewIds(priorities)
}
