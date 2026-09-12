package me.rerere.rikkahub.ui.components.webview

import org.junit.Assert.*
import org.junit.Test

class WebViewLoadTrackerTest {
    private val content = WebContent.Data("<svg></svg>", baseUrl = WEB_VIEW_BASE_URL)
    @Test fun `recreated native view reloads HTML held by the same state`() {
        val old = WebViewLoadTracker()
        assertTrue(old.needsLoad(content, false))
        old.loaded(content)
        assertFalse(old.needsLoad(content, false))
        assertTrue(WebViewLoadTracker().needsLoad(content, false))
    }
    @Test fun `explicit refresh loads once without changing content`() {
        val state = WebViewState(content)
        val tracker = WebViewLoadTracker().apply { loaded(this@WebViewLoadTrackerTest.content) }
        state.reload()
        assertTrue(tracker.needsLoad(content, state.forceReload))
        tracker.loaded(content); state.forceReload = false
        assertFalse(tracker.needsLoad(content, state.forceReload))
    }
    @Test fun `changing HTML forces load without rebuilding state`() {
        val tracker = WebViewLoadTracker().apply { loaded(this@WebViewLoadTrackerTest.content) }
        assertTrue(tracker.needsLoad(content.copy(data = "<svg><text>updated</text></svg>"), false))
    }
    @Test fun `visible and user requested views precede prefetch within resource limit`() {
        val priorities = mapOf(1L to 1L, 2L to 0L, 3L to -1L, 4L to 1L)
        assertEquals(setOf(3L, 2L), admittedWebViewIds(priorities, 2))
        assertEquals(emptySet<Long>(), admittedWebViewIds(priorities, 0))
    }
}
