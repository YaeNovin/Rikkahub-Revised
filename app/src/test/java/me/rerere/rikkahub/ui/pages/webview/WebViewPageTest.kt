package me.rerere.rikkahub.ui.pages.webview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.jsoup.Jsoup

class WebViewPageTest {
    @Test
    fun `fullscreen preview centers visual content and keeps vertical scrolling enabled`() {
        val result = prepareFullscreenPreviewHtml(
            "<html><head></head><body><svg viewBox='0 0 800 1200'></svg></body></html>",
        )
        val document = Jsoup.parse(result)
        val style = document.getElementById("rikkahub-fullscreen-preview-style")

        assertTrue(document.body().hasClass("rikkahub-fullscreen-preview"))
        assertEquals(1, document.select("#rikkahub-fullscreen-preview-style").size)
        assertTrue(style?.data().orEmpty().contains("overflow: auto !important"))
        assertTrue(style?.data().orEmpty().contains("margin: auto"))
        assertTrue(style?.data().orEmpty().contains("pan-x pan-y pinch-zoom"))
    }

    @Test
    fun `fullscreen preview style injection is idempotent`() {
        val once = prepareFullscreenPreviewHtml("<html><body><canvas></canvas></body></html>")
        val twice = prepareFullscreenPreviewHtml(once)

        assertEquals(
            1,
            Jsoup.parse(twice).select("#rikkahub-fullscreen-preview-style").size,
        )
    }
}
