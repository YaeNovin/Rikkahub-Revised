package me.rerere.rikkahub.ui.components.richtext

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GfmModernExtensionsTest {
    @Test fun `empty and nested tasks keep the state of their own list item`() {
        val html = buildMarkdownDocument("- [X]\n\n- [ ] Parent\n  - [x] Child")
        assertEquals(listOf("true", "false", "true"), html.select("li.task-list-item").eachAttr("data-rikka-task"))
    }

    @Test fun `GFM tables badges and normalized alerts retain their structure`() {
        val markdown = """
            > [!WARNING]
            > **Check** <kbd>Ctrl</kbd>+<kbd>K</kbd>

            | Left | Center | Right |
            | :--- | :---: | ---: |
            | A | B | C |

            [![Build](https://img.shields.io/badge/build-ok-green)](https://example.com)
        """.trimIndent()
        for (mode in MarkdownRenderMode.entries) {
            val document = buildMarkdownDocument(markdown, mode)
            val alert = htmlCallout(document.selectFirst("blockquote")!!)!!
            assertEquals("WARNING", alert.title)
            val body = alert.body.joinToString("") { it.outerHtml() }
            assertTrue(body.contains(">Check</strong>"))
            assertFalse(body.contains("[!WARNING]"))
            assertEquals(listOf("Ctrl", "K"), document.select("kbd").eachText())
            assertEquals(listOf("", "center", "right"), document.select("th").map { it.attr("align") })
            assertEquals(1, document.select("a img[src*=shields]").size)
        }
    }

    @Test fun `email autolinks preserve existing links code and mail punctuation`() {
        val markdown = """
            Mail name+tag@example.org, <team@example.org> and www.example.org.

            [existing@example.org](https://example.org) and https://example.org/me@example.org

            | mail |
            | --- |
            | table@example.org |

            `code@example.org`
        """.trimIndent()
        val hrefs = listOf("mailto:name+tag@example.org", "mailto:team@example.org", "https://www.example.org",
            "https://example.org", "https://example.org/me@example.org", "mailto:table@example.org")
        for (mode in MarkdownRenderMode.entries) {
            val document = buildMarkdownDocument(markdown, mode)
            assertEquals(hrefs, document.select("a[href]").eachAttr("href"))
            assertEquals("code@example.org", document.selectFirst("code")!!.wholeText())
        }
        assertEquals("https://www.example.org", normalizeMarkdownLinkDestination("www.example.org"))
        assertEquals("https://example.org/a(b)", normalizeMarkdownLinkDestination("https://example.org/a(b)"))
    }

    @Test fun `paired typography works while attributed and unpaired tags stay literal`() {
        val markdown = """x<sup>2</sup> and H<sub>2</sub>O, <sup style="color: red">3</sup>, and <sup>open."""
        val document = buildMarkdownDocument(markdown)
        assertEquals(listOf("2"), document.select("sup").eachText())
        assertEquals(listOf("2"), document.select("sub").eachText())
        assertTrue(document.text().contains("""<sup style="color: red">3</sup>"""))
        assertTrue(document.text().contains("<sup>open."))
        val literal = buildMarkdownDocument("`<sup>9</sup>`")
        assertNull(literal.selectFirst("sup"))
        assertEquals("<sup>9</sup>", literal.selectFirst("code")!!.wholeText())
    }

    @Test fun `unsupported footnote definitions stay readable and cannot create relative links`() {
        val markdown = "Alpha[^note].\n\n[^note]: example\n\nSee [ref].\n\n[ref]: https://example.org"
        for (mode in MarkdownRenderMode.entries) {
            val document = buildMarkdownDocument(markdown, mode)
            assertTrue(document.text().contains("Alpha[^note]."))
            assertTrue(document.text().contains("[^note]: example"))
            assertEquals(listOf("https://example.org"), document.select("a").eachAttr("href"))
        }
        val code = "~~~md\n[^note]: example\n~~~"
        assertEquals(code, normalizeMarkdownProseLinks(code, linkEmails = false))
    }
}
