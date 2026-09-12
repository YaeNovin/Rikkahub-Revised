package me.rerere.rikkahub.ui.components.ui

import androidx.compose.runtime.*

internal class ExportRenderTracker {
    private val pending = mutableSetOf<Any>()
    private var changedAt = System.nanoTime()
    var failure: String? = null
        private set
    fun begin(token: Any) { if (pending.add(token)) changedAt = System.nanoTime() }
    fun complete(token: Any) { if (pending.remove(token)) changedAt = System.nanoTime() }
    fun fail(token: Any, reason: String) { failure = reason; complete(token) }
    fun settled(now: Long = System.nanoTime()): Boolean = pending.isEmpty() && now - changedAt >= 200_000_000L
}

internal val LocalExportRenderTracker = compositionLocalOf<ExportRenderTracker?> { null }

@Composable
internal fun AwaitExportRender(pending: Boolean) {
    val tracker = LocalExportRenderTracker.current ?: return
    val token = remember { Any() }
    DisposableEffect(tracker, pending) {
        if (pending) tracker.begin(token) else tracker.complete(token)
        onDispose { tracker.complete(token) }
    }
}
