package me.rerere.rikkahub.ui.components.richtext

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InteractiveCodeBlockTest {
    @Test
    fun `recognizes supported code fence languages`() {
        assertEquals(InteractiveCodeRenderer.ECHARTS, InteractiveCodeRenderer.fromLanguage("chart"))
        assertEquals(InteractiveCodeRenderer.ABC, InteractiveCodeRenderer.fromLanguage("abcjs"))
        assertEquals(InteractiveCodeRenderer.JIANPU, InteractiveCodeRenderer.fromLanguage(" numbered-notation "))
        assertEquals(InteractiveCodeRenderer.JIANPU, InteractiveCodeRenderer.fromLanguage("简谱"))
        assertEquals(InteractiveCodeRenderer.LEAFLET, InteractiveCodeRenderer.fromLanguage("geojson"))
        assertEquals(InteractiveCodeRenderer.RAILROAD, InteractiveCodeRenderer.fromLanguage("grammar"))
    }

    @Test
    fun `does not create an interactive renderer for incomplete or oversized source`() {
        assertFalse(canRenderInteractiveCodeBlock("kotlin", "fun main() = Unit"))
        assertFalse(canRenderInteractiveCodeBlock("echarts", "x".repeat(128 * 1024 + 1)))
        assertTrue(canRenderInteractiveCodeBlock("echarts", "{}"))
    }

    @Test
    fun `converts EBNF grouping alternatives and repetition into railroad nodes`() {
        val source = normalizeRailroadSource(
            "expression = term, { ('+' | '-'), term }, [ ';' ];",
        )

        assertTrue(source.contains("nonterminal"))
        assertTrue(source.contains("zeroOrMore"))
        assertTrue(source.contains("optional"))
        assertTrue(source.contains("terminal"))
    }

    @Test
    fun `map renderer includes GeoJSON bounds and delayed viewport refresh`() {
        val html = buildInteractiveRendererHtml(
            renderer = InteractiveCodeRenderer.LEAFLET,
            code = "{\"type\":\"Point\",\"coordinates\":[120,30]}",
            colorScheme = androidx.compose.material3.lightColorScheme(),
        )

        assertTrue(html.contains("item.getBounds()"))
        assertTrue(html.contains("const geoJson = config.geoJson"))
        assertTrue(html.contains("setTimeout(refreshMapViewport, 120)"))
    }

    @Test
    fun `score preview keeps a transparent responsive canvas and supports scrolling`() {
        val html = buildInteractiveRendererHtml(
            renderer = InteractiveCodeRenderer.ABC,
            code = "X:1\nK:C\nC D E F|",
            colorScheme = androidx.compose.material3.lightColorScheme(),
        )

        assertTrue(html.contains("overflow: auto"))
        assertTrue(html.contains("body { background: transparent"))
        assertTrue(html.contains("ABCJS.renderAbc"))
        assertTrue(html.contains("root.clientWidth - 16"))
        assertTrue(html.contains("#renderer svg { display: block; max-width: 100%"))
        assertTrue(html.contains("preserveAspectRatio"))
    }

    @Test
    fun `charts add compact mobile layout safeguards`() {
        val html = buildInteractiveRendererHtml(
            renderer = InteractiveCodeRenderer.ECHARTS,
            code = "{\"radar\":{},\"series\":[]}",
            colorScheme = androidx.compose.material3.lightColorScheme(),
        )

        assertTrue(html.contains("compactViewport"))
        assertTrue(html.contains("containLabel: true"))
        assertTrue(html.contains("overflow: 'break'"))
        assertTrue(html.contains("confine: true"))
        assertTrue(html.contains("radius: '58%'"))
        assertTrue(html.contains("devicePixelRatio: Math.min"))
    }

    @Test
    fun `rich preview height follows phone width within stable limits`() {
        assertEquals(249, calculateRichPreviewHeightDp(320, 240, 380, 0.78f))
        assertEquals(280, calculateRichPreviewHeightDp(360, 220, 380, 0.78f))
        assertEquals(380, calculateRichPreviewHeightDp(800, 240, 380, 0.78f))
    }

    @Test
    fun `svg preview uses source dimensions and a transparent responsive viewport`() {
        assertEquals(4f, extractSvgAspectRatio("<svg viewBox='0 0 800 200'></svg>"))
        assertEquals(2f, extractSvgAspectRatio("<svg width=\"640px\" height=\"320\"></svg>"))
        assertEquals(120, calculateSvgPreviewHeightDp(360, 4f))
        assertEquals(295, calculateSvgPreviewHeightDp(360, 1f))
        assertEquals(420, calculateSvgPreviewHeightDp(360, 0.5f))

        val html = buildCodePreviewHtml(
            code = "<svg viewBox=\"0 0 800 200\"><text x=\"790\">title</text></svg>",
            language = "svg",
        )
        assertTrue(html.contains("background: transparent"))
        assertTrue(html.contains("svg.getBBox()"))
        assertTrue(html.contains("preserveAspectRatio"))
        assertTrue(html.contains("svg.removeAttribute('width')"))
        assertTrue(html.contains("svg.style.width = '100%'"))
    }

    @Test
    fun `html preview adds transparent mobile media constraints`() {
        val html = buildCodePreviewHtml(
            code = "<html><body><canvas width=\"1200\" height=\"600\"></canvas></body></html>",
            language = "html",
        )

        assertTrue(html.contains("rikkahub-responsive-preview"))
        assertTrue(html.contains("width=device-width"))
        assertTrue(html.contains("background: transparent"))
        assertTrue(html.contains("canvas, iframe"))
    }

    @Test
    fun `recognizes diff fence aliases`() {
        assertTrue(isDiffCodeFenceLanguage("diff"))
        assertTrue(isDiffCodeFenceLanguage("PATCH"))
        assertTrue(isDiffCodeFenceLanguage(" udiff "))
        assertTrue(isDiffCodeFenceLanguage("git-diff filename.patch"))
        assertTrue(isDiffCodeFenceLanguage("{unified-diff}"))
        assertTrue(isDiffCodeFenceLanguage("{.diff}"))
        assertFalse(isDiffCodeFenceLanguage("git"))
    }

    @Test
    fun `detects unified diff content without requiring a language tag`() {
        val diff = """
            --- a/file.txt
            +++ b/file.txt
            @@ -1 +1 @@
            -old
            +new
        """.trimIndent()

        assertTrue(looksLikeUnifiedDiff(diff))
        assertTrue(looksLikeStandaloneUnifiedDiff(diff))
        assertTrue(shouldRenderDiffCodeBlock("plaintext", diff))
        assertTrue(shouldRenderDiffCodeBlock("diff", "-partial\n+streaming"))
        assertFalse(looksLikeUnifiedDiff("Use C++ and --flags in ordinary text."))
        assertFalse(looksLikeStandaloneUnifiedDiff("Explanation first.\n\n$diff"))
    }

    @Test
    fun `railroad diagrams retain readable width with touch scrolling`() {
        val html = buildInteractiveRendererHtml(
            renderer = InteractiveCodeRenderer.RAILROAD,
            code = "{\"type\":\"sequence\",\"items\":[\"a\",\"b\"]}",
            colorScheme = androidx.compose.material3.lightColorScheme(),
        )

        assertTrue(html.contains("-webkit-overflow-scrolling: touch"))
        assertTrue(html.contains("max-width: none"))
    }

    @Test
    fun `numbered notation renderer supports common jianpu metadata and tokens`() {
        val html = buildInteractiveRendererHtml(
            renderer = InteractiveCodeRenderer.JIANPU,
            code = "title: Little Star\n/key(C)\nbpm: 96\n1 1 5 5 | 6 6 5= |\nL: 一 闪 一 闪",
            colorScheme = androidx.compose.material3.lightColorScheme(),
        )

        assertTrue(html.contains("Jianpu numbered musical notation"))
        assertTrue(html.contains("tokenPattern"))
        assertTrue(html.contains("replace(/\\r/g, '').split('\\n')"))
        assertTrue(html.contains("/^\\/key\\(/i"))
        assertFalse(html.contains("/^\\\\/key\\\\(/i"))
        assertTrue(html.contains("replaceChildren") || html.contains("appendChild(svg)"))
        assertTrue(html.contains("preserveAspectRatio"))
    }

    @Test
    fun `plain text diff blocks are split without classifying ordinary plus minus text`() {
        val content = "Patch explanation.\n\n--- a/file.txt\n+++ b/file.txt\n@@ -1 +1 @@\n-old\n+new\n\nThat is all."
        val parts = splitMarkdownAroundDiff(content)

        assertEquals(3, parts.size)
        assertFalse(parts[0].isDiff)
        assertTrue(parts[1].isDiff)
        assertFalse(parts[2].isDiff)
        assertTrue(splitMarkdownAroundDiff("Use C++ and --flags in ordinary text.").isEmpty())
        assertTrue(
            splitMarkdownAroundDiff(
                "```diff\n--- a/file.txt\n+++ b/file.txt\n@@ -1 +1 @@\n-old\n+new\n```",
            ).isEmpty(),
        )
    }
}
