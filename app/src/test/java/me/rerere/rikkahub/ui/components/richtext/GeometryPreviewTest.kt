package me.rerere.rikkahub.ui.components.richtext

import java.io.File
import java.security.MessageDigest
import java.util.Base64
import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.render.RendererCatalog
import me.rerere.rikkahub.data.render.compileDeclarativeRenderSpec
import me.rerere.rikkahub.ui.pages.webview.prepareFullscreenPreviewHtml
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class GeometryPreviewTest {
    @Test fun `raw JSXGraph scripts stay highlighted source while JSON aliases still render`() {
        val source = "// 初始化画板\nvar board = JXG.JSXGraph.initBoard('board', {});"
        assertFalse(canRenderGeometryPreview("jsxgraph", source, true))
        assertEquals("javascript", rendererSourceHighlightLanguage("jsxgraph", source))
        assertTrue(canRenderGeometryPreview("jsxgraph", RendererCatalog.find("math_plot")!!.example, true))
        assertEquals("json", rendererSourceHighlightLanguage("jsxgraph", "{}"))
    }
    @Test fun `aliases are gated by completeness size and explicit render types`() {
        listOf("math_plot", "math_plan", "jsxgraph", "math-plot", "sigil", "magic_circle", "magic-circle", "LANGUAGE-MATH_PLOT").forEach {
            assertTrue(canRenderGeometryPreview(it, "{}", true))
            assertFalse(canRenderGeometryPreview(it, "{}", false))
            assertEquals(240, markdownLoadingPlaceholderHeightDp("```" + it.lowercase().removePrefix("language-") + "\n{}"))
        }
        assertFalse(canRenderGeometryPreview("html", "{}", true))
        assertFalse(canRenderGeometryPreview("json", "{}", true))
        assertFalse(canRenderGeometryPreview("math_plot", "x".repeat(32769), true))
    }
    @Test fun `source labels are encoded data and CSP never grants source execution`() {
        val source = """{"title":"</script><img src=x onerror=alert(1)>","curves":[{"expression":"sin(x)","label":"<script>alert(1)</script>"}]}"""
        val html = buildGeometryPreviewHtml("math_plot", source)
        val doc = Jsoup.parse(html)
        assertFalse(html.contains("onerror="))
        assertTrue(doc.select("script:not([src]),iframe,img").isEmpty())
        val data = String(Base64.getDecoder().decode(doc.body().attr("data-source")), Charsets.UTF_8)
        assertTrue(data.contains("onerror="))
        val csp = doc.selectFirst("meta[http-equiv]")!!.attr("content").substringAfter("script-src").substringBefore(';')
        assertFalse(csp.contains("unsafe-inline")); assertFalse(csp.contains("unsafe-eval"))
        assertTrue(doc.select("script[src]").all { it.attr("src").startsWith("https://rikkahub.local/assets/html/renderers/") })
        val fullscreen = prepareFullscreenPreviewHtml(html)
        assertEquals("true", Jsoup.parse(fullscreen).body().attr("data-fullscreen"))
        assertEquals(fullscreen, prepareFullscreenPreviewHtml(fullscreen, true))
        assertFalse(fullscreen.contains("fullscreen-pan.js"))
        assertFalse(buildGeometryPreviewHtml("sigil", RendererCatalog.find("sigil")!!.example).contains("<script defer src=\"https://rikkahub.local/assets/html/renderers/jsxgraph"))
    }
    @Test fun `invalid fenced data gives a readable failure instead of executing it`() {
        val doc = Jsoup.parse(buildGeometryPreviewHtml("math_plot", "alert(1)"))
        val data = Json.parseToJsonElement(String(Base64.getDecoder().decode(doc.body().attr("data-source")))).jsonObject
        assertTrue("error" in data)
        assertTrue(isGeometryPreview(doc.outerHtml()))
    }
    @Test fun `pinned JSXGraph bundle and MIT notice are present`() {
        mapOf("jsxgraph-1.13.3.js" to "4e196b10dc77e22ec0eda897e0271636edf8dadd91d76cc2cf2e136c61c35537",
            "jsxgraph-1.13.3.css" to "86e8f917ba2b4bc1b979375808cc4d1aeda8b0fddb4b23a96cfee9ccc870b8b8").forEach { (name, expected) ->
            assertEquals(expected, MessageDigest.getInstance("SHA-256").digest(File("src/main/assets/html/renderers/$name").readBytes()).joinToString("") { "%02x".format(it) })
        }
        assertTrue(File("src/main/assets/html/licenses/jsxgraph-1.13.3-MIT.txt").readText().contains("Permission is hereby granted"))
    }
    @Test fun `generate production math sigil error and evaluator fixtures`() {
        val directory = File("build/reports/geometry-preview").apply { mkdirs() }
        val math = RendererCatalog.find("math_plot")!!.example
        val sigil = """{"title":"六芒星 · AETHER","color":"#8b5cf6","glow":0.6,"animate":true,"layers":[{"type":"ring","radius":94},{"type":"text_ring","radius":87,"text":"A E T H E R · L U X · A E T H E R · L U X ·","fontSize":5,"speed":-3},{"type":"ring","radius":80},{"type":"ticks","radius":77,"count":48,"length":3},{"type":"star","points":6,"step":2,"radius":67,"speed":8},{"type":"ring","radius":33,"speed":0},{"type":"star","points":6,"step":2,"radius":29,"speed":-12}]}"""
        directory.resolve("math.html").writeText(buildGeometryPreviewHtml("math_plot", math))
        val compatibility = """{"title":"滑块与向量标注","parameters":[{"name":"a","label":"振幅","min":0,"max":3,"value":1,"step":0.03}],"curves":[{"expression":"a*sin(x)"}],"primitives":[{"type":"vector","from":[0,0],"to":[2,2],"label":"<img src=x onerror=evil>"},{"type":"circle","center":[0,0],"radius":1,"label":"单位圆"}]}"""
        directory.resolve("compatibility.html").writeText(buildGeometryPreviewHtml("math_plot", compatibility))
        directory.resolve("export-probe.js").writeText(me.rerere.rikkahub.ui.components.webview.WEBVIEW_EXPORT_PROBE)
        directory.resolve("export-tall-svg.html").writeText(buildCodePreviewHtml("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 200 2000"><rect width="200" height="2000" fill="white"/><rect id="start" width="200" height="80" fill="red"/><rect id="end" y="1920" width="200" height="80" fill="blue"/></svg>""", "svg"))
        directory.resolve("math-dark.html").writeText(buildGeometryPreviewHtml("math_plot", math, true))
        directory.resolve("math-fullscreen.html").writeText(prepareFullscreenPreviewHtml(buildGeometryPreviewHtml("math_plot", math)))
        directory.resolve("sigil.html").writeText(buildGeometryPreviewHtml("sigil", sigil, true))
        val advancedSigil = """{"name":"七重守护阵","colors":["#ff4fd8","#29d3ff","#ffd166"],"glow":{"amount":1.4,"color":"#29d3ff","spread":2.2,"pulse":true},"animate":true,"animation":{"mode":"sequence","duration":2,"stagger":0.12},"layers":[{"type":"ring","radius":94,"symmetry":2},{"type":"path","points":[[0,-40],[12,-8],[40,0],[12,8],[0,40],[-12,8],[-40,0],[-12,-8]],"closed":true,"fill":"#19142f","label":"封印纹","symmetry":3,"animation":{"mode":"draw","loop":false}},{"type":"text_ring","radius":82,"text":"A E T H E R · L U X","fontSize":5}]}"""
        directory.resolve("sigil-advanced.html").writeText(buildGeometryPreviewHtml("sigil", advancedSigil, true))
        directory.resolve("sigil-light.html").writeText(buildGeometryPreviewHtml("sigil", sigil))
        directory.resolve("invalid.html").writeText(buildGeometryPreviewHtml("math_plot", """{"curves":[{"expression":"window.alert(1)"}]}"""))
        val special = """{"title":"<img src=https://evil.test onerror=alert(1)>","curves":[{"expression":"1/x"},{"expression":"tan(x)"},{"expression":"sqrt(x)"},{"type":"parametric","x":"2*cos(t)","y":"2*sin(t)"},{"type":"polar","r":"3*cos(3*theta)"}],"primitives":[{"type":"point","at":[1,1],"label":"<svg onload=alert(1)>"},{"type":"vector","from":[0,0],"to":[2,3]},{"type":"circle","center":[-3,0],"radius":1}]}"""
        directory.resolve("special.html").writeText(buildGeometryPreviewHtml("math_plot", special))
        directory.resolve("special.json").writeText(compileDeclarativeRenderSpec("math_plot", special).toString())
    }
}
