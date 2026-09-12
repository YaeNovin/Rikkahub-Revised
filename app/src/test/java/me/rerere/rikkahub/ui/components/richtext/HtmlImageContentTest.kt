package me.rerere.rikkahub.ui.components.richtext

import org.junit.Assert.*
import org.junit.Test
import org.jsoup.Jsoup

class HtmlImageContentTest {
    @Test fun `svg css dimensions support pixel and physical units but not unbound percentages`() {
        assertEquals(20f, svgCssLength("20px")!!, .001f)
        assertEquals(88.6f, svgCssLength("88.6")!!, .001f)
        assertEquals(96f, svgCssLength("1in")!!, .001f)
        assertEquals(20f, svgCssLength("15pt")!!, .001f)
        assertNull(svgCssLength("100%"))
        assertNull(svgCssLength("auto"))
        assertNull(svgCssLength("1e99"))
    }

    @Test fun `nested linked badge retains target and formatted surrounding text without mutating document`() {
        val doc = buildMarkdownDocument("**before [![Build](https://img.shields.io/badge/build-ok-green)](https://example.com) after**")
        val original = doc.outerHtml()
        val runs = htmlImageRuns(doc.selectFirst("p")!!.childNodes())
        assertEquals(3, runs.size)
        assertTrue((runs[0] as HtmlImageRun.Text).nodes[0].outerHtml().contains("<strong>before "))
        assertEquals("https://example.com", (runs[1] as HtmlImageRun.Image).link)
        assertEquals("Build", (runs[1] as HtmlImageRun.Image).element.attr("alt"))
        assertTrue((runs[2] as HtmlImageRun.Text).nodes[0].outerHtml().contains("after</strong>"))
        assertEquals(original, doc.outerHtml())
    }

    @Test fun `images in tight lists tables headings and reference links use image runs`() {
        val document = buildMarkdownDocument("""
            # ![heading][badge]

            - ![list][badge]

            | Status |
            | --- |
            | [![table][badge]](https://example.com) |

            [badge]: https://img.shields.io/badge/test-pass-green
        """.trimIndent())
        listOf("h1", "li", "td").forEach { tag ->
            val node = document.selectFirst(tag)!!
            assertTrue(tag, node.containsInlineImage())
            assertEquals(1, htmlImageRuns(node.childNodes()).filterIsInstance<HtmlImageRun.Image>().size)
        }
    }

    @Test fun `callout retains first paragraph image text and later paragraphs`() {
        val document = buildMarkdownDocument("> [!NOTE]\n> [![Build](https://img.shields.io/badge/test-pass-green)](https://example.com) Text\n>\n> More")
        val original = document.outerHtml()
        val callout = htmlCallout(document.selectFirst("blockquote")!!)!!
        assertEquals("NOTE", callout.title)
        val body = callout.body.joinToString("") { it.outerHtml() }
        assertFalse(body.contains("[!NOTE]"))
        assertTrue(body.contains("<img"))
        assertTrue(body.contains("Text"))
        assertTrue(body.contains("More"))
        assertEquals(original, document.outerHtml())
    }

    @Test fun `normal quotes and inline code stay text`() {
        assertNull(htmlCallout(Jsoup.parse("<blockquote><p>[!OTHER] ordinary text</p></blockquote>").selectFirst("blockquote")!!))
        assertFalse(Jsoup.parse("<code>&lt;img src='demo'/&gt;</code>").selectFirst("code")!!.containsInlineImage())
    }

    @Test fun `failure details never expose signed image url or exception message`() {
        val details = inlineImageFailureDetails("https://user:secret@example.com/image?token=private", java.io.IOException("token=private"))
        assertTrue(details.contains("example.com"))
        assertFalse(details.contains("private"))
        assertFalse(details.contains("secret"))
        assertEquals("图片请求超时", inlineImageFailureReason(java.net.SocketTimeoutException()))
    }
}
