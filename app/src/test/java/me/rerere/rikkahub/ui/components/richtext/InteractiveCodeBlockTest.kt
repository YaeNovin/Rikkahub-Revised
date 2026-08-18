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
}
