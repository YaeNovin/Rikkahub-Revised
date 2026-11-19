package me.rerere.rikkahub.ui.components.ui

import androidx.compose.runtime.*

internal class ExportRenderTracker {
    private val pending = mutableSetOf<Any>()
    private var changedAt = System.nanoTime()
    private var layoutSize: Pair<Int, Int>? = null
    val warnings = linkedSetOf<String>()
    var failure: String? = null
        private set
    fun begin(token: Any) { if (pending.add(token)) changedAt = System.nanoTime() }
    fun complete(token: Any) { if (pending.remove(token)) changedAt = System.nanoTime() }
    fun fail(token: Any, reason: String) { failure = reason; complete(token) }
    fun warn(token: Any, reason: String) { warnings.add(reason); complete(token) }
    fun layoutChanged(width: Int, height: Int) {
        if (layoutSize != (width to height)) { layoutSize = width to height; changedAt = System.nanoTime() }
    }
    fun settled(now: Long = System.nanoTime()): Boolean = pending.isEmpty() && now - changedAt >= 200_000_000L
}

internal val LocalExportRenderTracker = compositionLocalOf<ExportRenderTracker?> { null }

@Composable
internal fun AwaitExportRender(pending: Boolean, warning: String? = null) {
    val tracker = LocalExportRenderTracker.current ?: return
    val token = remember { Any() }
    DisposableEffect(tracker, pending, warning) {
        if (pending) tracker.begin(token) else if (warning != null) tracker.warn(token, warning) else tracker.complete(token)
        onDispose { tracker.complete(token) }
    }
}
