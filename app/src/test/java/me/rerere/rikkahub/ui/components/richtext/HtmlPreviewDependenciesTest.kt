package me.rerere.rikkahub.ui.components.richtext

import me.rerere.rikkahub.ui.pages.webview.prepareFullscreenPreviewHtml
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class HtmlPreviewDependenciesTest {
    private fun prepare(html: String) = Jsoup.parse(buildCodePreviewHtml(html, "html"))

    @Test fun `author references never auto install executable renderer libraries`() {
        listOf("vegaEmbed('#chart', {})", "new SmilesDrawer.Drawer({})",
            "new opensheetmusicdisplay.OpenSheetMusicDisplay('score')", "hpccWasm.graphviz.layout('digraph G{}')")
            .forEach { code ->
                val doc = prepare("<script>" + code + "</script><p>Keep text</p>")
                assertTrue(doc.select("script[data-rikkahub-renderer-library],script:not([src])").isEmpty())
                assertEquals("Keep text", doc.selectFirst("p")!!.text())
                assertEquals(1, doc.select("script[src]").size)
            }
    }

    @Test fun `CDN aliases and local bundles supplied by author are removed`() {
        listOf("https://cdn.jsdelivr.net/npm/vega-embed@6", "//unpkg.com/vega-embed@6/build/vega-embed.min.js",
            "https://rikkahub.local/assets/html/renderers/vega.min.js", "smiles-drawer.min.js").forEach { url ->
            val doc = prepare("<script src='" + url + "' async defer integrity='old'></script>")
            assertTrue(doc.select("script[src]").all { it.attr("src").endsWith("/static-preview.js") })
            assertTrue(doc.select("script[async],script[defer],script[integrity]").isEmpty())
        }
    }

    @Test fun `unknown origins plugins module scripts and templates cannot execute`() {
        val doc = prepare("<script src='https://custom.test/plugin.js'></script><script type='module'>import 'https://evil.test/a'</script><template><script>alert(1)</script></template>")
        assertEquals(1, doc.select("script").size)
        assertTrue(doc.select("template,script[type=module]").isEmpty())
        assertFalse(doc.outerHtml().contains("evil.test"))
    }

    @Test fun `author charset base and CSP cannot relax the app policy`() {
        val doc = prepare("<head><meta charset='gbk'><meta http-equiv='Content-Security-Policy' content=\"script-src 'unsafe-inline'\"><base href='https://evil.test/'></head>")
        assertEquals("utf-8", doc.selectFirst("meta[charset]")!!.attr("charset"))
        assertTrue(doc.select("base").isEmpty())
        assertEquals(1, doc.select("meta[http-equiv]").size)
        val csp = doc.selectFirst("meta[http-equiv]")!!.attr("content")
        assertTrue(csp.contains("connect-src 'none'"))
        assertFalse(csp.substringAfter("script-src ").substringBefore(';').contains("unsafe-inline"))
    }

    @Test fun `escaped source is kept as text without dependency activation`() {
        val doc = prepare("<pre>&lt;script&gt;vegaEmbed('#chart', {})&lt;/script&gt;</pre>")
        assertEquals("<script>vegaEmbed('#chart', {})</script>", doc.selectFirst("pre")!!.text())
        assertEquals(1, doc.select("script").size)
    }

    @Test fun `fullscreen is idempotent and cannot revive old dependency markers`() {
        val inline = buildCodePreviewHtml("<style id='rikkahub-responsive-preview'></style><script>SmilesDrawer.parse('CCO');</script>", "html")
        val fullscreen = prepareFullscreenPreviewHtml(prepareFullscreenPreviewHtml(inline))
        val doc = Jsoup.parse(fullscreen)
        assertTrue(doc.select("script[data-rikkahub-renderer-library],script:not([src])").isEmpty())
        assertEquals(1, doc.select("#rikkahub-fullscreen-pan-script").size)
        assertEquals(1, doc.select("#rikkahub-fullscreen-background-script").size)
    }

    @Test fun `legacy dependency resolver remains a separate non rendering helper`() {
        val doc = Jsoup.parse("<script>hpccWasm.graphviz.layout('digraph G{}');</script>")
        installHtmlPreviewDependencies(doc)
        assertEquals("hpccWasm", doc.selectFirst("script[data-rikkahub-renderer-library]")!!.attr("data-rikkahub-renderer-library"))
        assertTrue(prepare(doc.outerHtml()).select("script[data-rikkahub-renderer-library]").isEmpty())
    }

    @Test fun `generate sanitized former HTML apps for real browser tests`() {
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
