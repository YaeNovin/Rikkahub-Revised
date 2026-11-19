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

class PatternPreviewTest {
    @Test fun `pattern aliases route through complete geometry fences only`() {
        RendererCatalog.find("pattern")!!.aliases.forEach {
            assertTrue(canRenderGeometryPreview(it, "{}", true))
            assertFalse(canRenderGeometryPreview(it, "{}", false))
            assertEquals(240, markdownLoadingPlaceholderHeightDp("```$it\n{}"))
        }
        assertFalse(canRenderGeometryPreview("p5", "function setup() {}", true))
        assertFalse(canRenderGeometryPreview("pattern", " ".repeat(32769), true))
    }

    @Test fun `p5 loads only for pattern with strict CSP and no source interpolation`() {
        val html = buildGeometryPreviewHtml("pattern", """{"title":"</script><img src=x onerror=alert(1)>","layers":[{"type":"rose"}]}""")
        val doc = Jsoup.parse(html)
        val scripts = doc.select("script[src]").map { it.attr("src").substringAfterLast('/') }
        assertTrue(scripts.contains("p5-1.11.11.min.js"))
        assertFalse(scripts.any { it.startsWith("jsxgraph") })
        assertTrue(doc.select("script:not([src]),img,iframe").isEmpty())
        assertFalse(html.contains("onerror="))
        val csp = doc.selectFirst("meta[http-equiv]")!!.attr("content").substringAfter("script-src").substringBefore(';')
        assertFalse(csp.contains("unsafe-inline")); assertFalse(csp.contains("unsafe-eval"))
        val fullscreen = prepareFullscreenPreviewHtml(html)
        assertEquals("true", Jsoup.parse(fullscreen).body().attr("data-fullscreen"))
        assertFalse(fullscreen.contains("fullscreen-pan.js"))
        for (id in listOf("math_plot", "sigil")) {
            assertFalse(buildGeometryPreviewHtml(id, RendererCatalog.find(id)!!.example).contains("p5-1.11.11"))
        }
        val error = Jsoup.parse(buildGeometryPreviewHtml("pattern", "function setup() {}"))
        assertTrue(String(Base64.getDecoder().decode(error.body().attr("data-source"))).contains("error"))
    }

    @Test fun `pinned p5 distribution retains its version and LGPL notice`() {
        val file = File("src/main/assets/html/renderers/p5-1.11.11.min.js")
        assertEquals("1343f616bf9914da8253faae00201ea5f72772916998bc6add4c2a07e00a662c", MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) })
        assertTrue(file.readText().take(100).contains("1.11.11"))
        assertTrue(File("src/main/assets/html/licenses/p5-1.11.11-LGPL-2.1.txt").readText().contains("GNU LESSER GENERAL PUBLIC LICENSE"))
    }

    @Test fun `generate production pattern browser fixtures`() {
        val directory = File("build/reports/pattern-preview").apply { mkdirs() }
        val examples = linkedMapOf(
            "mandala" to RendererCatalog.find("pattern")!!.example,
            "rose" to """{"title":"玫瑰纹样","layers":[{"type":"rose","numerator":7,"denominator":3,"radius":88,"color":"#f472b6"},{"type":"ring","radius":94}]}""",
            "koch" to """{"title":"分形雪花","layers":[{"type":"fractal","variant":"koch","depth":4,"color":"#22d3ee"}]}""",
            "sierpinski" to """{"title":"三角分形纹样","layers":[{"type":"fractal","variant":"sierpinski","depth":5,"color":"#fbbf24"}]}""",
            "tree" to """{"title":"分形枝蔓","layers":[{"type":"fractal","variant":"tree","depth":7,"color":"#34d399"}]}""",
            "mixed" to """{"title":"<img src=https://evil.test onerror=alert(1)>","glow":0.4,"layers":[{"type":"ring","radius":94},{"type":"ticks","radius":90},{"type":"rays","radius":84,"count":12},{"type":"polygon","points":12,"radius":78},{"type":"star","points":6,"step":2,"radius":67,"fill":"#211a39"},{"type":"mandala","radius":58,"rings":3},{"type":"rose","numerator":5,"radius":24}]}""",
            "animated" to """{"animate":true,"layers":[{"type":"mandala","speed":20},{"type":"rose","radius":30,"speed":-15}]}""",
            "advanced" to """{"name":"星门","colors":["#ff2bd6","#24d8ff","#ffe08a"],"glow":{"amount":1.2,"spread":1.8,"pulse":true},"animate":true,"animation":{"mode":"orbit","duration":4,"stagger":0.12},"layers":[{"type":"freeform","points":[[-30,0],[-10,-22],[20,-15],[35,0],[20,15],[-10,22]],"closed":true,"fill":"#211435","symmetry":3,"mirror":true,"animation":{"mode":"draw","loop":false}},{"type":"mandala","rings":3,"petals":16,"colors":["#ff2bd6","#24d8ff"]}]}""",
            "complex" to """{"layers":[{"type":"mandala","petals":32,"rings":6},{"type":"mandala","petals":32,"rings":6,"rotation":5},{"type":"fractal","variant":"koch","depth":5}]}""",
        )
        examples.forEach { (name, source) ->
            directory.resolve("$name.html").writeText(buildGeometryPreviewHtml("pattern", source, true))
            directory.resolve("$name.json").writeText(compileDeclarativeRenderSpec("pattern", source).toString())
        }
        directory.resolve("light.html").writeText(buildGeometryPreviewHtml("pattern", """{"background":"#ffffff","palette":["#7c3aed","#0284c7","#be185d"],"layers":[{"type":"mandala"}]}"""))
        directory.resolve("fullscreen.html").writeText(prepareFullscreenPreviewHtml(buildGeometryPreviewHtml("pattern", examples.getValue("mandala"))))
        directory.resolve("invalid.html").writeText(buildGeometryPreviewHtml("pattern", """{"layers":[{"type":"fractal","depth":100}]}"""))
    }
}
