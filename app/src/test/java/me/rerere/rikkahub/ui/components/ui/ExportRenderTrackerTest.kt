package me.rerere.rikkahub.ui.components.ui

import org.junit.Assert.*
import org.junit.Test

class ExportRenderTrackerTest {
    @Test fun `pending renderer prevents premature capture regardless of elapsed delay`() {
        val tracker = ExportRenderTracker()
        val parse = Any(); val webview = Any()
        tracker.begin(parse); tracker.begin(webview)
        tracker.complete(parse)
        assertFalse(tracker.settled(System.nanoTime() + 60_000_000_000L))
        tracker.complete(webview)
        assertTrue(tracker.settled(System.nanoTime() + 300_000_000L))
    }
    @Test fun `newly created async child resets settling window`() {
        val tracker = ExportRenderTracker()
        val token = Any()
        tracker.begin(token); tracker.complete(token)
        assertFalse(tracker.settled())
        assertTrue(tracker.settled(System.nanoTime() + 300_000_000L))
    }
    @Test fun `failed render reports error rather than silently exporting a hole`() {
        val tracker = ExportRenderTracker(); val token = Any()
        tracker.begin(token); tracker.fail(token, "SVG failed")
        assertEquals("SVG failed", tracker.failure)
    }
}
