package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.material3.lightColorScheme
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class WebRendererRegressionTest {
    @Test fun `mixed HTML leaves streaming diagram unrendered until its closing fence`() {
        val source = "<div>Header</div>\n\n```mermaid\nflowchart LR\n A --> B\n"
        val open = buildMarkdownDocument(source)
        assertEquals("false", open.selectFirst("pre")!!.attr("data-rikka-complete"))
        val complete = buildMarkdownDocument(source + "```\n")
        assertFalse(complete.selectFirst("pre")!!.hasAttr("data-rikka-complete"))
    }
    @Test fun `plain HTML preformatted blocks are preserved`() {
        assertFalse(buildMarkdownDocument("<pre><code>hello</code></pre>").selectFirst("pre")!!.hasAttr("data-rikka-complete"))
    }
    @Test fun `unclosed quote fence does not mark a later HTML block as incomplete`() {
        val document = buildMarkdownDocument(
            "> ```mermaid\n> flowchart LR\n> A --> B\n\n<pre><code>Later HTML</code></pre>"
        )
        val blocks = document.select("pre")
        assertEquals(2, blocks.size)
        assertEquals("false", blocks[0].attr("data-rikka-complete"))
        assertFalse(blocks[1].hasAttr("data-rikka-complete"))
    }
    @Test fun `only the open fence is marked across mixed and nested blocks`() {
        val document = buildMarkdownDocument(
            "```mermaid\nflowchart LR; A --> B\n```\n\n" +
                "> > ```\n> > unfinished\n\n<pre><code class='language-svg'>literal</code></pre>"
        )
        val blocks = document.select("pre")
        assertEquals(3, blocks.size)
        assertFalse(blocks[0].hasAttr("data-rikka-complete"))
        assertEquals("false", blocks[1].attr("data-rikka-complete"))
        assertFalse(blocks[2].hasAttr("data-rikka-complete"))
    }
    @Test fun `SVG dimensions come from root and must be finite`() {
        assertNull(extractSvgAspectRatio("<svg><rect width='200' height='50'/></svg>"))
        assertNull(extractSvgAspectRatio("<svg viewBox='0 0 Infinity 200'></svg>"))
        assertEquals(2f, extractSvgAspectRatio("<svg width='2e3' height='1000'></svg>"))
        assertEquals(3f, extractSvgAspectRatio("<svg viewBox='0 0 300 100'><rect width='1' height='1'/></svg>"))
    }
    @Test fun `generate renderer pages for standalone JavaScript verification`() {
        val dir = File("build/reports/webview-renderers").apply { mkdirs() }
        InteractiveCodeRenderer.entries.forEach { renderer ->
            val sample = when(renderer) {
                InteractiveCodeRenderer.ECHARTS -> "{\"series\":[]}"
                InteractiveCodeRenderer.LEAFLET -> "{\"markers\":[{\"lat\":30,\"lng\":120}]}"
                InteractiveCodeRenderer.ABC -> "X:1\nK:C\nC D E F|"
                InteractiveCodeRenderer.JIANPU -> "1 2 3 4 | 5 6 7 1'"
                InteractiveCodeRenderer.RAILROAD -> "{\"type\":\"terminal\",\"text\":\"word\"}"
                InteractiveCodeRenderer.WAVEFORM -> """{"signal":[{"name":"clk","wave":"p....."},{"name":"data","wave":"x.345x","data":["A","B","C"]}]}"""
                InteractiveCodeRenderer.GRAPHVIZ -> "digraph G { A -> B; A -> C; }"
                InteractiveCodeRenderer.VEGA -> """{"mark":"bar","data":{"values":[{"a":"A","b":2},{"a":"B","b":4}]},"encoding":{"x":{"field":"a","type":"nominal"},"y":{"field":"b","type":"quantitative"}}}"""
                InteractiveCodeRenderer.MOLECULE -> "CC(=O)Oc1ccccc1C(=O)O"
                InteractiveCodeRenderer.MUSICXML -> """<?xml version="1.0"?><score-partwise version="3.1"><part-list><score-part id="P1"><part-name>Piano</part-name></score-part></part-list><part id="P1"><measure number="1"><attributes><divisions>1</divisions><key><fifths>0</fifths></key><time><beats>4</beats><beat-type>4</beat-type></time><clef><sign>G</sign><line>2</line></clef></attributes><note><pitch><step>C</step><octave>4</octave></pitch><duration>4</duration><type>whole</type></note></measure></part></score-partwise>"""
            }
            File(dir, "${renderer.name}.html").writeText(buildInteractiveRendererHtml(renderer, sample, lightColorScheme()))
        }
        File(dir, "MERMAID.html").writeText(buildMermaidHtml("flowchart LR; A --> B", lightColorScheme()))
        File(dir, "SVG.html").writeText(buildCodePreviewHtml("<svg viewBox='0 0 300 100'><text x='10' y='20'>Hello</text></svg>", "svg"))
        val maps = File(dir, "maps").apply { mkdirs() }
        File(maps, "fullscreen.html").writeText(
            me.rerere.rikkahub.ui.pages.webview.prepareFullscreenPreviewHtml(File(dir, "LEAFLET.html").readText())
        )
        val rawMap = """
            <html><head><meta charset="UTF-8">
            <link rel="stylesheet" href="https://rikkahub.local/assets/html/renderers/leaflet.css">
            <script src="https://rikkahub.local/assets/html/renderers/leaflet.js"></script></head>
            <body><div id="map" style="width:100%;height:500px"></div><script>
            var map = L.map('map').setView([30,120], 5);
            L.tileLayer('https://tiles.test/{z}/{x}/{y}.png').addTo(map);
            L.circleMarker([30,120]).addTo(map);
            </script></body></html>
        """.trimIndent()
        File(maps, "raw-html.html").writeText(buildCodePreviewHtml(rawMap, "html"))
        File(maps, "raw-fullscreen.html").writeText(
            me.rerere.rikkahub.ui.pages.webview.prepareFullscreenPreviewHtml(buildCodePreviewHtml(rawMap, "html")),
        )
        assertEquals(InteractiveCodeRenderer.entries.size + 2, dir.listFiles()!!.count { it.extension == "html" })
    }
}
