package me.rerere.rikkahub.ui.pages.webview

import me.rerere.rikkahub.data.datastore.isFullscreenPreviewBackgroundActive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.jsoup.Jsoup

class WebViewPageTest {
    @Test fun `preview background follows saved global settings and resets HTML state`() {
        val appearance = me.rerere.rikkahub.data.datastore.AdvancedAppearanceSetting(
            enableGlobalBackground = true, globalBackground = "content://background", applyGlobalBackgroundToChat = false)
        fun active(setting: me.rerere.rikkahub.data.datastore.AdvancedAppearanceSetting) =
            me.rerere.rikkahub.data.datastore.Settings(advancedAppearanceSetting = setting).isFullscreenPreviewBackgroundActive()
        assertTrue(active(appearance))
        org.junit.Assert.assertFalse(active(appearance.copy(applyGlobalBackgroundToFullscreenPreview = false)))
        org.junit.Assert.assertFalse(active(appearance.copy(enableGlobalBackground = false)))
        org.junit.Assert.assertFalse(active(appearance.copy(globalBackground = null)))
        org.junit.Assert.assertFalse(active(appearance.copy(pageSurfaceStyle = me.rerere.rikkahub.data.datastore.BackgroundSurfaceStyle.OPAQUE)))
        val enabled = prepareFullscreenPreviewHtml("<html><body><svg/></body></html>", true)
        assertTrue(Jsoup.parse(enabled).body().hasClass("rikka-preview-global-background"))
        org.junit.Assert.assertFalse(Jsoup.parse(prepareFullscreenPreviewHtml(enabled, false)).body().hasClass("rikka-preview-global-background"))
        java.io.File("build/reports/fullscreen-pan").apply { mkdirs() }.resolve("background.html").writeText(enabled)
    }
    @Test
    fun `fullscreen preview centers visual content and keeps vertical scrolling enabled`() {
        val result = prepareFullscreenPreviewHtml(
            "<html><head></head><body><svg viewBox='0 0 800 1200'></svg></body></html>",
        )
        val document = Jsoup.parse(result)
        val style = document.getElementById("rikkahub-fullscreen-preview-style")

        assertTrue(document.body().hasClass("rikkahub-fullscreen-preview"))
        assertEquals(1, document.select("#rikkahub-fullscreen-preview-style").size)
        assertTrue(style?.html().orEmpty().contains("overflow: auto !important"))
        assertTrue(style?.html().orEmpty().contains("margin: auto"))
        assertTrue(style?.html().orEmpty().contains("pan-x pan-y pinch-zoom"))
        assertEquals(1, document.select("#rikkahub-fullscreen-background-style").size)
        assertTrue(result.contains("rikkaSetPreviewBackground"))
        assertTrue(result.contains("rikka-preview-global-background"))
    }

    @Test
    fun `fullscreen preview style injection is idempotent`() {
        val once = prepareFullscreenPreviewHtml("<html><body><canvas></canvas></body></html>")
        val twice = prepareFullscreenPreviewHtml(once)

        assertEquals(
            1,
            Jsoup.parse(twice).select("#rikkahub-fullscreen-preview-style").size,
        )
        assertEquals(1, Jsoup.parse(twice).select("#rikkahub-fullscreen-pan-script").size)
        java.io.File("build/reports/fullscreen-pan").apply { mkdirs() }.resolve("preview.html").writeText(
            prepareFullscreenPreviewHtml("<html><body><svg width='240' height='160' viewBox='0 0 240 160'><rect width='240' height='160' fill='teal'/><text x='40' y='80'>Drag diagram</text></svg></body></html>"))
    }
}
