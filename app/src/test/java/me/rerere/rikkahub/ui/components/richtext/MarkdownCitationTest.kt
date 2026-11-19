package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.runBlocking
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import org.junit.Assert.*
import org.junit.Test

class MarkdownCitationTest {
    private fun native(source: String): Pair<AnnotatedString, Map<String, InlineTextContent>> {
        val normalized = normalizeMarkdownCitations(source)
        val tree = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(normalized)
        val inline = mutableMapOf<String, InlineTextContent>()
        val text = buildAnnotatedString {
            appendMarkdownNodeContent(tree, normalized, inlineContents = inline, colorScheme = darkColorScheme(),
                density = Density(1f), style = TextStyle(fontSize = 16.sp), enableLatexRendering = false)
        }
        return text to inline
    }

    private fun html(source: String): Pair<AnnotatedString, Map<String, InlineTextContent>> {
        val inline = mutableMapOf<String, InlineTextContent>()
        val text = buildAnnotatedString {
            buildMarkdownDocument(source).body().childNodes().forEach { node ->
                appendHtmlInlineNode(node, darkColorScheme(), inline, Density(1f), TextStyle(fontSize = 16.sp), false, {}, preserveSoftLineBreaks = false)
            }
        }
        return text to inline
    }

    @Test fun `screenshot grouped citations become separate inline content in both renderers`() {
        val source = "模型结论[citation:fae5f3,9d7feb]。再验证[citation:9d7feb,54d9dd]。"
        assertEquals("模型结论[citation,1](fae5f3)[citation,2](9d7feb)。再验证[citation,2](9d7feb)[citation,3](54d9dd)。",
            normalizeMarkdownCitations(source))
        for ((text, inline) in listOf(native(source), html(source))) {
            assertFalse(text.text.contains("citation:"))
            assertTrue(text.text.contains("[1][2]"))
            assertEquals(3, inline.size)
        }
    }

    @Test fun `normalization accepts spacing and fullwidth commas and removes duplicate IDs`() {
        assertEquals("[citation,1](fae5f3)[citation,2](9d7feb)",
            normalizeMarkdownCitations("[CITATION: fae5f3 ， 9d7feb,fae5f3 ]"))
    }

    @Test fun `code fences nested quoted fences indented code and inline code keep literal citation source`() {
        val sources = listOf("```text\n[citation:fae5f3,9d7feb]\n```",
            "> ~~~text\n> [citation:fae5f3,9d7feb]\n> ~~~",
            "```text\n[citation:fae5f3,9d7feb]", "    [citation:fae5f3,9d7feb]",
            "``[citation:fae5f3,9d7feb]``", "`[citation:fae5f3,9d7feb]`")
        sources.forEach { assertEquals(it, it, normalizeMarkdownCitations(it)) }
    }

    @Test fun `escaped markers links images and mathematical literals are not rewritten`() {
        val sources = listOf("\\[citation:fae5f3,9d7feb]", "[link [citation:fae5f3]](https://example.test)",
            "![citation:fae5f3](https://example.test/image.png)",
            "[link](https://example.test/[citation:fae5f3])", "`[citation:fae5f3]`", "<code>[citation:fae5f3]</code>")
        sources.forEach { assertEquals(it, it, normalizeMarkdownCitations(it)) }
    }

    @Test fun `incomplete streamed or malformed citations remain readable until completed`() {
        for (source in listOf("text [citation:fae5f3,9d7", "[citation:]", "[citation:abc/def]", "[citation:abc123,]")) {
            assertEquals(source, normalizeMarkdownCitations(source))
        }
        assertEquals("text [citation,1](fae5f3)", normalizeMarkdownCitations("text [citation:fae5f3]"))
    }

    @Test fun `canonical IDs need not be six characters and invalid forms do not drop text`() {
        for (source in listOf("[citation,docs.example](source_123456)", "[citation](1)", "[citation,docs](abc123,def456)")) {
            assertTrue(source, native(source).second.isNotEmpty())
            assertTrue(source, html(source).second.isNotEmpty())
        }
        val invalid = "before [citation,docs.example](https://example.test) after"
        for ((text, _) in listOf(native(invalid), html(invalid))) {
            assertTrue(text.text, text.text.contains("docs.example"))
            assertTrue(text.text.contains("before") && text.text.contains("after"))
        }
    }

    @Test fun `the same source with different labels does not overwrite inline content`() {
        val source = "[citation,one.example](abc123) [citation,two.example](abc123)"
        assertEquals(2, native(source).second.size)
        assertEquals(2, html(source).second.size)
    }

    @Test fun `formatted link labels retain bold italic and code instead of Markdown markers`() {
        val source = "[**bold** and *italic* and `code`](https://example.test)"
        for ((text, _) in listOf(native(source), html(source))) {
            assertFalse(text.text, text.text.contains("**") || text.text.contains("`"))
            assertTrue(text.text.contains("bold") && text.text.contains("italic") && text.text.contains("code"))
            assertTrue(text.spanStyles.any { it.item.fontWeight == FontWeight.Bold })
            assertEquals("https://example.test", (text.getLinkAnnotations(0, text.length).first().item as LinkAnnotation.Url).url)
        }
    }

    @Test fun `native prose decodes escapes and entities exactly once`() {
        assertEquals("*literal* & <tag> &lt;", native("\\*literal\\* &amp; &lt;tag&gt; &amp;lt;").first.text)
    }

    @Test fun `inline code retains multiple spaces and literal entities in both renderers`() {
        for ((text, _) in listOf(native("`a  b &amp; c`"), html("`a  b &amp; c`"))) {
            assertTrue(text.text, text.text.contains("a  b &amp; c"))
        }
        assertEquals("a b", markdownCodeSpanText("`a\nb`"))
        assertEquals("`value`", markdownCodeSpanText("`` `value` ``"))
        assertEquals("  ", markdownCodeSpanText("`  `"))
    }

    @Test fun `angle link destinations and URL entities are decoded`() {
        val source = "[docs](<https://example.test/a?x=1&amp;y=2>)"
        for ((text, _) in listOf(native(source), html(source))) {
            assertEquals("https://example.test/a?x=1&y=2", (text.getLinkAnnotations(0, text.length).first().item as LinkAnnotation.Url).url)
        }
    }

    @Test fun `reference links use the document resolver including formatted labels`() = runBlocking {
        val source = "[**reference**][docs]\n\n[docs]: https://example.test/reference"
        preloadMarkdownContents(listOf(source), 4096)
        assertTrue(isMarkdownDocumentCached(source))
        val (text, _) = html(source)
        assertEquals("https://example.test/reference", (text.getLinkAnnotations(0, text.length).first().item as LinkAnnotation.Url).url)
        assertFalse(text.text.contains("[docs]"))
    }
}
