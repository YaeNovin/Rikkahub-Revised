package me.rerere.highlight

import androidx.compose.ui.graphics.Color
import org.junit.Assert.*
import org.junit.Test

class TerminalAndCacheTest {
    @Test fun `ANSI aliases highlight controls and log severity without changing source`() {
        val source = "\\x1b[31mERROR\\x1b[0m 中文\n\u001b[32mSUCCESS\u001b[0m\nWARN INFO DEBUG"
        val highlighter = CodeHighlighter()
        for (alias in listOf("ansi", "terminal", "xterm", "console-output")) {
            assertTrue(highlighter.supports(alias))
            val tokens = highlighter.highlight(source, alias)
            assertEquals(source, tokens.joinToString("") { it.content })
            val styled = tokens.filterIsInstance<HighlightToken.Styled>()
            assertTrue(styled.any { it.type == "char.escape" })
            assertTrue(styled.any { it.content == "ERROR" && it.type == "deletion" })
            assertTrue(styled.any { it.content == "SUCCESS" && it.type == "addition" })
        }
    }

    @Test fun `returning to a message reuses highlights while color changes get a fresh palette`() {
        val code = "val cachedBoundary = 42"
        val dark = HighlightTextColorPalette.Default
        val light = dark.copy(keyword = Color.Black, number = Color.Blue)
        val first = prepareHighlightedCode(code, "kotlin", dark, 32768)
        assertFalse(first.spanStyles.isEmpty())
        assertSame(first, prepareHighlightedCode(code, "kotlin", dark, 32768))
        val recolored = prepareHighlightedCode(code, "kotlin", light, 32768)
        assertEquals(first.text, recolored.text)
        assertNotEquals(first.spanStyles, recolored.spanStyles)
    }
}
