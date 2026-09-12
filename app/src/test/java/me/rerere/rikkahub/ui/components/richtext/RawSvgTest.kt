package me.rerere.rikkahub.ui.components.richtext

import org.junit.Assert.*
import org.junit.Test
import org.jsoup.Jsoup
import java.io.File

class RawSvgTest {
    private val svg = """
        <svg viewBox="-20 -10 440 260" xmlns="http://www.w3.org/2000/svg">
          <defs>
            <linearGradient id="paint"><stop stop-color="#267cff"/><stop offset="1" stop-color="#ee57cc"/></linearGradient>
            <filter id="glow" x="-30%" y="-30%" width="160%" height="180%"><feDropShadow dx="0" dy="10" stdDeviation="7" flood-color="#ff3040"/></filter>
          </defs>

          <!-- A literal </svg> inside a comment is not the root's closing tag. -->
          <rect x="60" y="60" width="280" height="110" rx="20" fill="url(#paint)" filter="url(#glow)"/>
          <path d="M20,140 A80,70 0 0,1 150,20" fill="none" stroke="#34d399" stroke-width="4"/>
          <text x="180" y="100" fill="white">SVG test</text>
          <svg x="350" y="190" width="40" height="40" viewBox="0 0 10 10"><circle cx="5" cy="5" r="4" fill="#34d399"/></svg>
        </svg>
    """.trimIndent()

    @Test fun `raw SVG survives Markdown blank lines definitions and comments byte for byte`() {
        val document = buildMarkdownDocument("## Before\n\n$svg\n\nAfter")
        val nodes = document.select("div[$RAW_SVG_ATTRIBUTE]")
        assertEquals(1, nodes.size)
        assertEquals(RawSvgSource(svg, true), rawSvgSource(nodes.first()!!))
        assertTrue(document.text().contains("After"))
        assertFalse(document.text().contains("SVG test"))
        assertEquals(0, document.select("pre").size)
    }
    @Test fun `fenced inline and indented source are not reinterpreted`() {
        listOf("```xml\n$svg\n```", "~~~svg\n$svg\n~~~", "`<svg></svg>`", "    <svg></svg>")
            .forEach { assertEquals(it, protectRawSvg(it)) }
    }
    @Test fun `incomplete SVG remains a single pending unit`() {
        val source = "<svg><defs>\n\n  <linearGradient id='x'>"
        val protected = Jsoup.parse(protectRawSvg(source))
        assertEquals(RawSvgSource(source, false), rawSvgSource(protected.selectFirst("div")!!))
        assertFalse(protected.text().contains("linearGradient"))
    }
    @Test fun `separate roots retain their own source and nested viewports`() {
        val document = buildMarkdownDocument("$svg\n\n$svg")
        assertEquals(2, document.select("div[$RAW_SVG_ATTRIBUTE]").size)
        assertEquals(svg, rawSvgSource(document.selectFirst("div")!!)!!.source)
    }
    @Test fun `comments and HTML attributes do not trigger false SVG extraction`() {
        listOf("<!-- <svg>not content</svg> -->", "<div title='<svg></svg>'>plain</div>").forEach {
            assertEquals(it, protectRawSvg(it))
        }
    }
    @Test fun `generate SVG browser fixtures`() {
        val directory = File("build/reports/svg-regressions").apply { mkdirs() }
        File(directory, "advanced.html").writeText(buildCodePreviewHtml(svg, "svg"))
        File(directory, "dimensions.html").writeText(buildCodePreviewHtml("<svg width='400' height='200'><rect x='10' y='10' width='30' height='30' fill='red'/></svg>", "svg"))
        File(directory, "bounds.html").writeText(buildCodePreviewHtml("<svg><rect x='10' y='10' width='30' height='30' fill='red'/></svg>", "svg"))
        File(directory, "animated.html").writeText(buildCodePreviewHtml("""<svg viewBox="0 0 400 200"><defs><linearGradient id="flow"><stop stop-color="#06b6d4"/><stop offset="1" stop-color="#a855f7"/></linearGradient></defs><path id="route" d="M30 160 Q200 -80 370 160" stroke="url(#flow)" stroke-width="4" fill="none"/><circle r="8" fill="#34d399"><animateMotion dur="3s" repeatCount="indefinite" path="M30 160 Q200 -80 370 160"/></circle></svg>""", "svg"))
    }
}
