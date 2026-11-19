package me.rerere.rikkahub.ui.components.richtext

import java.io.File
import me.rerere.rikkahub.data.render.RendererCatalog
import me.rerere.rikkahub.ui.pages.webview.prepareFullscreenPreviewHtml
import me.rerere.rikkahub.ui.pages.webview.previewSupportsGlobalBackground
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class GeometryAppearanceTest {
    @Test fun `only decorative geometry opts into inherited background`() {
        for (id in listOf("math_plot", "sigil", "pattern")) {
            val html = buildGeometryPreviewHtml(id, RendererCatalog.find(id)!!.example)
            assertEquals(id != "math_plot", isDecorativeGeometryPreview(html))
            assertEquals(id != "math_plot", previewSupportsGlobalBackground(html))
            val fullscreen = prepareFullscreenPreviewHtml(html, true)
            val doc = Jsoup.parse(fullscreen)
            assertEquals("true", doc.body().attr("data-fullscreen"))
            assertEquals((id != "math_plot").toString(), doc.body().attr("data-preview-background"))
            assertEquals(id != "math_plot", doc.body().hasClass("rikka-preview-global-background"))
            assertEquals(fullscreen, prepareFullscreenPreviewHtml(fullscreen, true))
            assertEquals(Jsoup.parse(html).body().attr("data-source"), doc.body().attr("data-source"))
            assertFalse(fullscreen.contains("fullscreen-pan.js"))
            assertFalse(Jsoup.parse(prepareFullscreenPreviewHtml(fullscreen, false)).body().hasClass("rikka-preview-global-background"))
        }
        val terminal = buildTerminalPreviewHtml("ANSI", false)
        assertFalse(previewSupportsGlobalBackground(terminal))
        val fullscreenTerminal = prepareFullscreenPreviewHtml(terminal, true)
        assertEquals("false", Jsoup.parse(fullscreenTerminal).body().attr("data-embedded"))
        assertEquals(prepareFullscreenPreviewHtml(terminal, false), fullscreenTerminal)
        assertFalse(previewSupportsGlobalBackground(fullscreenTerminal))
    }
    @Test fun `zoom controls and decorative frames are removed without removing useful actions`() {
        val html = buildGeometryPreviewHtml("pattern", RendererCatalog.find("pattern")!!.example)
        val doc = Jsoup.parse(html)
        assertTrue(doc.select("#zoom-in,#zoom-out,header button").isEmpty())
        assertEquals(3, doc.select("#controls button").size)
        assertTrue(doc.select("script[src]").any { it.attr("src").endsWith("geometry-gestures.js") })
        assertTrue(doc.select("style").html().contains("border:0;border-radius:0"))
        assertEquals("false", doc.body().attr("data-preview-background"))
        val page = File("src/main/java/me/rerere/rikkahub/ui/pages/webview/WebViewPage.kt").readText()
        assertTrue(page.contains("WindowInsets.statusBarsIgnoringVisibility"))
        assertTrue(page.contains("WindowInsets.displayCutout"))
    }
    @Test fun `generate fullscreen and inline appearance fixtures`() {
        val dir = File("build/reports/geometry-appearance").apply { mkdirs() }
        for (id in listOf("math_plot", "sigil", "pattern")) {
            val example = RendererCatalog.find(id)!!.example
            val html = buildGeometryPreviewHtml(id, example, true)
            dir.resolve("$id-inline.html").writeText(html)
            dir.resolve("$id.html").writeText(prepareFullscreenPreviewHtml(html, true))
        }
        val animated = """{"animate":true,"effects":{"stardust":{},"trail":{}},"layers":[{"type":"mandala","speed":12}]}"""
        dir.resolve("animated.html").writeText(prepareFullscreenPreviewHtml(buildGeometryPreviewHtml("pattern", animated, true), true))
    }
}
