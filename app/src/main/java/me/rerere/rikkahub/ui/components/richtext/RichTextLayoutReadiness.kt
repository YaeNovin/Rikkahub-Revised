package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.runtime.*

/** Tracks parsing only, not remote images or WebView loading (those have fixed preview heights). */
internal class RichTextLayoutReadiness {
    private val pending = mutableStateMapOf<Any, Unit>()
    val ready: Boolean get() = pending.isEmpty()
    fun update(token: Any, loading: Boolean) { if (loading) pending[token] = Unit else pending.remove(token) }
    fun remove(token: Any) { pending.remove(token) }
}

internal val LocalRichTextLayoutReadiness = staticCompositionLocalOf<RichTextLayoutReadiness?> { null }

@Composable
internal fun ReportRichTextLayoutLoading(loading: Boolean) {
    val readiness = LocalRichTextLayoutReadiness.current ?: return
    val token = remember { Any() }
    SideEffect { readiness.update(token, loading) }
    DisposableEffect(readiness, token) { onDispose { readiness.remove(token) } }
}
