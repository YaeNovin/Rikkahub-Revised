package me.rerere.rikkahub.ui.components.richtext

import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class EmbeddedSvgImagesTest {
    private fun uri(svg: String) = "data:image/svg+xml;base64," + Base64.getEncoder().encodeToString(svg.toByteArray())

    @Test fun `svg2 badge logo stays vector with its position colors and viewBox`() {
        val logo = """<svg xmlns="http://www.w3.org/2000/svg" fill="white" viewBox="0 0 24 24"><path d="M24 24H0V0h24L12 12Z"/></svg>"""
        val source = """<svg width="94.5" height="28"><image x="9" y="7" width="14" height="14" href="${uri(logo)}"/></svg>"""
        val document = Jsoup.parse(expandEmbeddedSvgImages(source), "", Parser.xmlParser())
        assertTrue(document.getElementsByTag("image").isEmpty())
        val embedded = document.getElementsByTag("svg")[1]
        assertEquals("0 0 24 24", embedded.attr("viewBox"))
        assertEquals("14", embedded.attr("width"))
        assertEquals("9", embedded.attr("x"))
        assertEquals("white", embedded.attr("fill"))
        assertEquals(1, embedded.getElementsByTag("path").size)
    }

    @Test fun `multiple logos keep definitions separate`() {
        val logo = """<svg viewBox="0 0 20 20"><defs><linearGradient id="gradient"><stop offset="1" stop-color="red"/></linearGradient></defs><rect fill="url(#gradient)" width="20" height="20"/></svg>"""
        val result = expandEmbeddedSvgImages("""<svg><image href="${uri(logo)}"/><image xlink:href="${uri(logo)}"/></svg>""")
        val document = Jsoup.parse(result, "", Parser.xmlParser())
        assertEquals(2, document.select("[id]").map { it.id() }.distinct().size)
        document.getElementsByTag("rect").forEach { rect ->
            assertNotNull(document.getElementById(rect.attr("fill").removePrefix("url(#").removeSuffix(")")))
        }
    }

    @Test fun `non embedded sources malformed data and oversized logos are left untouched`() {
        for (url in listOf("https://example.com/logo.svg", "data:image/png;base64,AAAA", "data:image/svg+xml;base64,!!!", uri("<svg>${" ".repeat(40_000)}</svg>"))) {
            val source = """<svg><image href="$url"/></svg>"""
            assertEquals(source, expandEmbeddedSvgImages(source))
        }
    }

    @Test fun `percent encoded svg retains literal plus characters`() {
        val source = """<svg><image href="data:image/svg+xml,%3Csvg%20viewBox=%270%200%2020%2020%27%3E%3Ctext%3EA+B%3C/text%3E%3C/svg%3E"/></svg>"""
        assertTrue(expandEmbeddedSvgImages(source).contains("A+B"))
    }
}
