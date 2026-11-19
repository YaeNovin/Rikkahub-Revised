package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.ui.graphics.Color
import org.junit.Assert.*
import org.junit.Test
import org.jsoup.Jsoup
import me.rerere.rikkahub.data.render.RendererCatalog
import me.rerere.rikkahub.ui.pages.webview.prepareFullscreenPreviewHtml

class PreviewBackgroundChromeTest {
    @Test fun `host palette hook passes only canonical colors and boolean state`() {
        val light = previewBackgroundScript(true, Color.White)
        assertTrue(light.contains("(true,{\"foreground\":\"#ffffff\",\"shadow\":\"#000000\"})"))
        val dark = previewBackgroundScript(false, Color.Black)
        assertTrue(dark.contains("(false,{\"foreground\":\"#000000\",\"shadow\":\"#ffffff\"})"))
        assertEquals(light, previewBackgroundScript(true, Color.Unspecified))
        assertFalse(light.contains("content://"))
    }
    @Test fun `decorative chrome uses transparency and never expands script policy`() {
        for (id in listOf("sigil", "pattern")) {
            val doc = Jsoup.parse(prepareFullscreenPreviewHtml(buildGeometryPreviewHtml(id, RendererCatalog.find(id)!!.example), true))
            val css = doc.select("style").html()
            assertTrue(css.contains("#controls")); assertTrue(css.contains("#status"))
            assertTrue(css.contains("background:transparent;color:var(--rikka-chrome-foreground"))
            assertTrue(css.contains("text-shadow:inherit"))
            assertFalse(css.contains("#status{background:#"))
            val scriptPolicy = doc.selectFirst("meta[http-equiv]")!!.attr("content").substringAfter("script-src").substringBefore(';')
            assertFalse(scriptPolicy.contains("unsafe-inline")); assertFalse(scriptPolicy.contains("unsafe-eval"))
        }
    }
}
