package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.ui.geometry.Size
import org.junit.Assert.*
import org.junit.Test

class AdditionalRendererTest {
    @Test fun `known code fences select all five additional renderers`() {
        mapOf("wavedrom" to InteractiveCodeRenderer.WAVEFORM, "dot" to InteractiveCodeRenderer.GRAPHVIZ,
            "vega-lite" to InteractiveCodeRenderer.VEGA, "smiles" to InteractiveCodeRenderer.MOLECULE,
            "musicxml" to InteractiveCodeRenderer.MUSICXML).forEach { (name, renderer) ->
            assertEquals(renderer, InteractiveCodeRenderer.fromLanguage(name))
            assertTrue(canRenderInteractiveCodeBlock(name, "test"))
        }
        assertNull(InteractiveCodeRenderer.fromLanguage("mxl")) // compressed files need an import flow.
        assertNull(InteractiveCodeRenderer.fromLanguage("mol")) // MOL format is not SMILES.
    }
    @Test fun `generic data recognized only with distinctive structure`() {
        assertEquals("wavedrom", resolveDiagramLanguage("json", """{"signal":[{"name":"clock","wave":"p..."}]}"""))
        assertEquals("vega", resolveDiagramLanguage("json", """{"mark":"bar","data":{"values":[]},"encoding":{}}"""))
        assertEquals("musicxml", resolveDiagramLanguage("xml", "<score-partwise><part-list/></score-partwise>"))
        assertEquals("json", resolveDiagramLanguage("json", """{"signal":"good","values":[1,2]}"""))
        assertEquals("text", resolveDiagramLanguage("text", "CCO"))
    }
    @Test fun `badge hosts and workflow paths are recognized without matching arbitrary GitHub images`() {
        listOf("https://img.shields.io/badge/build-passing-green", "https://badgen.net/npm/v/example",
            "https://github.com/o/r/actions/workflows/build.yml/badge.svg").forEach { assertTrue(it, isBadgeImage(it)) }
        assertFalse(isBadgeImage("https://github.com/o/r/raw/main/photo.png"))
        assertFalse(isBadgeImage("https://img.shields.io.evil.test/a"))
        assertFalse(isBadgeImage(null))
        assertTrue(isCompactBadgeImage("https://camo.githubusercontent.com/hash", Size(88f,20f)))
        assertFalse(isCompactBadgeImage("https://camo.githubusercontent.com/hash", Size(1200f,800f)))
    }
    @Test fun `linked Markdown badges retain image and target URLs in document`() {
        val doc = buildMarkdownDocument("[![Build](https://img.shields.io/badge/build-ok-green)](https://github.com/o/r) ![Version](https://badgen.net/npm/v/test)")
        assertEquals(2, doc.select("img").size)
        assertEquals("https://github.com/o/r", doc.selectFirst("a:has(img)")!!.attr("href"))
        assertEquals("Build", doc.selectFirst("a img")!!.attr("alt"))
    }
    @Test fun `HTML sizes are honored proportionally and invalid dimensions rejected`() {
        assertEquals(20f, htmlImageDimension("20px"))
        assertNull(htmlImageDimension("100%")); assertNull(htmlImageDimension("NaN")); assertNull(htmlImageDimension("-1"))
        assertEquals(Size(176f, 40f), requestedInlineImageSize(Size(88f,20f), null, 40f, 360f,280f))
        assertEquals(Size(88f,20f), requestedInlineImageSize(Size(88f,20f), null, null,360f,280f))
        assertNull(safeImageLink("javascript:alert(1)"))
        assertEquals("https://github.com", safeImageLink("https://github.com"))
    }
    @Test fun `reference style badge links and HTML dimensions survive parsing`() {
        val document = buildMarkdownDocument("""
            [![CI][badge]][project]

            [badge]: https://github.com/o/r/actions/workflows/build.yml/badge.svg
            [project]: https://github.com/o/r/actions

            <p><a href="https://github.com/o/r"><img src="https://img.shields.io/badge/test-ok-green" width="88" height="20" alt="Tests" /></a></p>
        """.trimIndent())
        assertEquals(2, document.select("a:has(img)").size)
        assertEquals("https://github.com/o/r/actions", document.selectFirst("a:has(img)")!!.attr("href"))
        assertEquals("20", document.select("img").last()!!.attr("height"))
    }
}
