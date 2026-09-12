package me.rerere.rikkahub.ui.components.webview

/** A fresh native view always loads, even when its Compose state outlives the previous view. */
internal class WebViewLoadTracker {
    var content: WebContent? = null
        private set
    fun needsLoad(next: WebContent, force: Boolean): Boolean = force || content != next
    fun loaded(next: WebContent) { content = next }
}
