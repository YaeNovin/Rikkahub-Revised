package me.rerere.rikkahub.ui.components.richtext

import org.jsoup.nodes.TextNode
import org.junit.Assert.assertEquals
import org.junit.Test

class UserMessageLineBreakTest {
    @Test
    fun `user rich text keeps soft line breaks while standard markdown keeps collapsing them`() {
        val node = TextNode("first line\nsecond line\n\nthird line")

        assertEquals(
            "first line\nsecond line\n\nthird line",
            textNodeContent(node, preserveSoftLineBreaks = true),
        )
        assertEquals(
            "first line second line third line",
            textNodeContent(node, preserveSoftLineBreaks = false),
        )
    }

    @Test
    fun `html markdown path retains line breaks around multiple code fragments`() {
        val source = """
            first note
            second note

            ```kotlin
            val first = 1
            val second = 2
            ```

            <div>HTML-like fragment</div>

            final note
            after final note
        """.trimIndent()
        val document = buildMarkdownDocument(source)
        val paragraphs = document.select("p")

        assertEquals(
            "first note\nsecond note",
            textNodeContent(paragraphs.first()!!.textNodes().single(), preserveSoftLineBreaks = true),
        )
        assertEquals(
            "final note\nafter final note",
            textNodeContent(paragraphs.last()!!.textNodes().single(), preserveSoftLineBreaks = true),
        )
        assertEquals("val first = 1\nval second = 2", document.selectFirst("pre code")!!.wholeText().trimEnd('\n'))
    }
}
