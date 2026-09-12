package me.rerere.rikkahub.ui.components.webview

internal const val WEB_PAGE_LOAD_TIMEOUT_MS = 30_000L

internal fun shouldFinishStalledLoad(sameView: Boolean, sameNavigation: Boolean, loading: Boolean): Boolean =
    sameView && sameNavigation && loading
