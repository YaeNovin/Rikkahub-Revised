package me.rerere.rikkahub.ui.components.ui

import org.junit.Assert.*
import org.junit.Test

class ExportRenderTrackerTest {
    @Test fun `visible error placeholder completes export with warning without losing other renders`() {
        val tracker = ExportRenderTracker(); val bad = Any(); val good = Any()
        tracker.begin(bad); tracker.begin(good); tracker.warn(bad, "无法绘图")
        assertNull(tracker.failure)
        assertEquals(setOf("无法绘图"), tracker.warnings)
        assertFalse(tracker.settled(System.nanoTime()+60_000_000_000L))
        tracker.complete(good)
        assertTrue(tracker.settled(System.nanoTime()+300_000_000L))
    }
    @Test fun `layout growth restarts quiet period even after assets loaded`() {
        val tracker = ExportRenderTracker()
        tracker.layoutChanged(1080,200)
        assertTrue(tracker.settled(System.nanoTime()+300_000_000L))
        tracker.layoutChanged(1080,14000)
        assertFalse(tracker.settled())
    }
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
