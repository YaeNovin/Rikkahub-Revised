package me.rerere.rikkahub.ui.components.richtext

import me.rerere.rikkahub.ui.pages.webview.prepareFullscreenPreviewHtml
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class HtmlPreviewDependenciesTest {
    private fun prepare(html: String) = Jsoup.parse(buildCodePreviewHtml(html, "html"))
    private fun libraries(html: String) = prepare(html).select("script[data-rikkahub-renderer-library]")
        .map { it.attr("data-rikkahub-renderer-library") }

    @Test fun `missing Vega Embed includes both transitive dependencies before head initialization`() {
        val doc = prepare("<head><script id='init'>vegaEmbed('#chart', {});</script></head><body><div id='chart'></div></body>")
        assertEquals(listOf("vega", "vegaLite", "vegaEmbed"), doc.select("script[src]").map { it.attr("data-rikkahub-renderer-library") })
        assertTrue(doc.select("script").indexOf(doc.getElementById("init")) > doc.select("script").indexOf(doc.select("script[src]").last()))
        assertTrue(doc.select("script[src]").all { !it.hasAttr("async") && !it.hasAttr("defer") })
    }

    @Test fun `single renderers load only their own bundle`() {
        assertEquals(listOf("SmilesDrawer"), libraries("<script>new SmilesDrawer.Drawer({});</script>"))
        assertEquals(listOf("opensheetmusicdisplay"), libraries("<script>new opensheetmusicdisplay.OpenSheetMusicDisplay('score');</script>"))
        assertEquals(listOf("vega", "vegaLite"), libraries("<script>vegaLite.compile({});</script>"))
        assertEquals(listOf("hpccWasm"), libraries("<script>hpccWasm.graphviz.layout('digraph G { a -> b }');</script>"))
    }

    @Test fun `charset base and content security policy keep their position before injected code`() {
        val doc = prepare("""<head><meta charset="utf-8"><meta http-equiv="Content-Security-Policy" content="script-src 'self'"><base href="https://example.com/"><script>vegaEmbed('#chart', {});</script></head>""")
        assertEquals(listOf("meta", "meta", "base"), doc.head().children().take(3).map { it.normalName() })
        assertEquals("script-src 'self'", doc.selectFirst("meta[http-equiv]")!!.attr("content"))
    }

    @Test fun `classic CDN aliases and package roots are rewritten without stale SRI or asynchronous load`() {
        mapOf(
            "https://cdn.jsdelivr.net/npm/vega-embed@6" to "vegaEmbed",
            "//unpkg.com/vega-embed@6.29.0/build/vega-embed.min.js?cache=1" to "vegaEmbed",
            "https://cdn.jsdelivr.net/npm/opensheetmusicdisplay@1.9.2/build/opensheetmusicdisplay.min.js" to "opensheetmusicdisplay",
            "https://unpkg.com/smiles-drawer@2.0.1/dist/smiles-drawer.min.js" to "SmilesDrawer",
            "https://cdnjs.cloudflare.com/ajax/libs/smiles-drawer/2.0.1/smiles-drawer.min.js" to "SmilesDrawer",
            "https://cdnjs.cloudflare.com/ajax/libs/SmilesDrawer/1.0.0/smiles-drawer.min.js" to "SmilesDrawer",
            "https://rikkahub.local/assets/html/vega-embed.min.js" to "vegaEmbed",
            "smiles-drawer.min.js" to "SmilesDrawer",
        ).forEach { (url, global) ->
            val doc = prepare("<script src='$url' async defer integrity='sha256-obsolete' crossorigin='anonymous'></script>")
            assertEquals(url, global, doc.select("script[src]").last()!!.attr("data-rikkahub-renderer-library"))
            assertTrue(doc.select("script[src]").all { it.attr("src").startsWith("https://rikkahub.local/assets/html/renderers/") })
            assertTrue(doc.select("script[integrity],script[async],script[defer],script[crossorigin]").isEmpty())
        }
    }

    @Test fun `unknown origins plugins and module scripts remain intact`() {
        val urls = listOf("https://custom.example/vega-embed.min.js", "https://cdn.jsdelivr.net.evil.test/npm/vega-embed@6",
            "https://cdn.jsdelivr.net/npm/unrelated/vega-embed.js", "https://cdn.jsdelivr.net/npm/vega-embed@6/build/vega-embed.module.js",
            "https://cdn.jsdelivr.net/npm/vega-embed@6/build/plugin/vega-embed.js", "https://unpkg.com/vega-embed@6/locale.js")
        urls.forEach { url ->
            val doc = prepare("<script src='$url'></script>")
            assertEquals(url, doc.selectFirst("script[src]")!!.attr("src"))
            assertTrue(doc.select("script[data-rikkahub-renderer-library]").isEmpty())
        }
        val module = "<script type='module' src='https://cdn.jsdelivr.net/npm/vega-embed@6'></script>"
        assertEquals("module", prepare(module).selectFirst("script")!!.attr("type"))
    }

    @Test fun `prose escaped source inert data and templates do not load heavy renderers`() {
        val html = """
            <p>vegaEmbed SmilesDrawer opensheetmusicdisplay</p>
            <pre>&lt;script&gt;vegaEmbed('#chart', {})&lt;/script&gt;</pre>
            <script type="application/json">{"name":"SmilesDrawer"}</script>
            <template><script>vegaEmbed('#chart', {});</script></template>
            <script>var myvegaEmbed = 1; var opensheetmusicdisplayExtension = 2;</script>
        """.trimIndent()
        assertEquals(emptyList<String>(), libraries(html))
    }

    @Test fun `duplicates out of order and cached fullscreen preparation are idempotent`() {
        val html = """
            <script src="https://cdn.jsdelivr.net/npm/vega-embed@6" async></script>
            <script id="init">window.count = (window.count || 0) + 1; vegaEmbed('#chart', {});</script>
            <script src="https://unpkg.com/vega@5" defer></script>
            <script src="https://unpkg.com/vega-embed@6" defer></script>
            <div id="chart"></div>
        """.trimIndent()
        val inline = buildCodePreviewHtml(html, "html")
        val fullscreen = prepareFullscreenPreviewHtml(prepareFullscreenPreviewHtml(inline))
        assertEquals(listOf("vega", "vegaLite", "vegaEmbed"), libraries(fullscreen))
        val doc = Jsoup.parse(fullscreen)
        assertEquals(1, doc.select("#init").size)
        assertEquals(1, doc.select("#rikkahub-renderer-dependency-guard").size)
        val cachedLegacy = "<style id='rikkahub-responsive-preview'></style><script>SmilesDrawer.parse('CCO');</script>"
        assertEquals(1, Jsoup.parse(prepareFullscreenPreviewHtml(cachedLegacy)).select("script[data-rikkahub-renderer-library]").size)
    }

    @Test fun `generate executable HTML reproductions and fixed previews for real browser tests`() {
        val output = File("build/reports/html-dependencies").apply { mkdirs() }
        val samples = listOf("vega", "smiles", "musicxml")
        samples.forEach { name ->
            val source = File("src/test/resources/html-dependencies/$name.html").readText()
            output.resolve("$name-original.html").writeText(source)
            output.resolve("$name.html").writeText(buildCodePreviewHtml(source, "html"))
            output.resolve("$name-fullscreen.html").writeText(prepareFullscreenPreviewHtml(buildCodePreviewHtml(source, "html")))
        }
        val vega = File("src/test/resources/html-dependencies/vega.html").readText()
        val cdn = vega.replace("<head>", "<head><script async integrity='sha256-invalid' src='https://cdn.jsdelivr.net/npm/vega-embed@6'></script>")
            .replace("</body>", "<script defer src='https://unpkg.com/vega@5'></script><script src='https://unpkg.com/vega-lite@5'></script></body>")
        output.resolve("cdn-order.html").writeText(buildCodePreviewHtml(cdn, "html"))
        val mixed = samples.joinToString("\n") { Jsoup.parse(File("src/test/resources/html-dependencies/$it.html").readText()).body().html() }
        output.resolve("mixed.html").writeText(buildCodePreviewHtml("<html><body>$mixed</body></html>", "html"))
        output.resolve("plain.html").writeText(buildCodePreviewHtml("<p>Simple HTML; no renderer needed</p>", "html"))
    }
}
