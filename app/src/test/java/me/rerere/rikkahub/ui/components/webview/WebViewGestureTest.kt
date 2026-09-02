package me.rerere.rikkahub.ui.components.webview

import org.junit.Assert.assertEquals
import org.junit.Test

class WebViewGestureTest {
    @Test
    fun `single finger vertical drag belongs to conversation list`() {
        assertEquals(
            WebViewGestureOwner.PARENT_VERTICAL,
            resolveWebViewGestureOwner(8f, 48f, pointerCount = 1, touchSlop = 12f),
        )
    }

    @Test
    fun `single finger horizontal drag remains inside graphical preview`() {
        assertEquals(
            WebViewGestureOwner.WEB_VIEW,
            resolveWebViewGestureOwner(48f, 8f, pointerCount = 1, touchSlop = 12f),
        )
    }

    @Test
    fun `zoomed preview keeps vertical drag inside web view`() {
        assertEquals(
            WebViewGestureOwner.WEB_VIEW,
            resolveWebViewGestureOwner(8f, 48f, pointerCount = 1, touchSlop = 12f, isZoomed = true),
        )
    }

    @Test
    fun `pinch remains inside graphical preview`() {
        assertEquals(
            WebViewGestureOwner.WEB_VIEW,
            resolveWebViewGestureOwner(0f, 40f, pointerCount = 2, touchSlop = 12f),
        )
    }

    @Test
    fun `small movement stays undecided`() {
        assertEquals(
            WebViewGestureOwner.UNDECIDED,
            resolveWebViewGestureOwner(4f, 5f, pointerCount = 1, touchSlop = 12f),
        )
    }
}
