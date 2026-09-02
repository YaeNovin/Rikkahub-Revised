package me.rerere.rikkahub.ui.components.richtext

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RichTextLoadingPolicyTest {
    @Test
    fun `graphical fences reserve preview height while parsing`() {
        assertEquals(
            240,
            markdownLoadingPlaceholderHeightDp("""```mermaid\nflowchart LR\nA --> B\n```"""),
        )
        assertEquals(
            240,
            markdownLoadingPlaceholderHeightDp("""```chart\n{}\n```"""),
        )
    }

    @Test
    fun `ordinary markdown uses a bounded lightweight placeholder`() {
        assertEquals(20, markdownLoadingPlaceholderHeightDp("one line"))
        assertEquals(120, markdownLoadingPlaceholderHeightDp((1..20).joinToString("\n")))
    }

    @Test
    fun `graphical markdown is parsed less frequently while streaming`() {
        assertEquals(
            GRAPHICAL_STREAMING_MARKDOWN_PARSE_INTERVAL_MS,
            streamingMarkdownParseIntervalMs("""```mermaid\nflowchart LR\nA --> B\n```"""),
        )
        assertEquals(
            GRAPHICAL_STREAMING_MARKDOWN_PARSE_INTERVAL_MS,
            streamingMarkdownParseIntervalMs("<svg viewBox=\"0 0 10 10\"></svg>"),
        )
        assertEquals(
            STREAMING_MARKDOWN_PARSE_INTERVAL_MS,
            streamingMarkdownParseIntervalMs("ordinary **streaming** markdown"),
        )
    }

    @Test
    fun `preloading a table also prepares its markdown cells`() = runBlocking {
        val table = """
            | Warmup header | Second header |
            | --- | --- |
            | Warmup cell alpha | Warmup cell beta |
        """.trimIndent()

        preloadMarkdownContents(listOf(table), maxTotalChars = 16 * 1024)

        assertTrue(isMarkdownContentPreloaded(table))
        assertTrue(isMarkdownContentPreloaded("Warmup header"))
        assertTrue(isMarkdownContentPreloaded("Warmup cell alpha"))
    }
}
