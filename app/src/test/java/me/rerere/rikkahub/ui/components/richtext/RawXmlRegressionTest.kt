package me.rerere.rikkahub.ui.components.richtext

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class RawXmlRegressionTest {
    private fun sources(text: String) = Jsoup.parse(protectRawHtml(protectRawSvg(text)))
        .select("[$RAW_SVG_ATTRIBUTE]").mapNotNull(::rawSvgSource)

    @Test fun `XML declaration and SVG remain one SVG source`() {
        val source = "<?xml version=\"1.0\"?>\n<!-- icon -->\n<svg viewBox=\"0 0 10 10\">\n\n<path d=\"M0 0L1 1\"/>\n</svg>"
        assertEquals(listOf(RawSvgSource(source, true)), sources(source))
    }
    @Test fun `arbitrary XML retains blank lines and case as one source-only block`() {
        val source = "<?xml version=\"1.0\"?>\n<Config>\n\n<Item Name=\"Test\"/>\n\n</Config>"
        assertEquals(listOf(RawSvgSource(source, true, "xml")), sources(source))
    }
    @Test fun `prose and adjacent SVG documents do not become HTML`() {
        assertEquals(2, sources("before\n<svg/>\n<svg><g/></svg>\nafter").size)
    }
    @Test fun `fenced xml remains unchanged and incomplete document stays together`() {
        val fenced = "```xml\n<config>\n\n<item/>\n</config>\n```"
        assertEquals(fenced, protectRawSvg(fenced))
        assertEquals(listOf(RawSvgSource("<config>\n\n<item/>", false, "xml")), sources("<config>\n\n<item/>"))
    }
    @Test fun `DTD is opaque and never expands entities`() {
        val source = "<?xml version=\"1.0\"?><!DOCTYPE svg [<!ENTITY x SYSTEM 'file:///secret'>]><svg>&x;</svg>"
        assertEquals(source, sources(source).single().source)
    }
}
