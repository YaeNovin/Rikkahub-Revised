package me.rerere.highlight

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.buildAnnotatedString
import org.junit.Assert.*
import org.junit.Test

class WholeBlockHighlightTest {
    @Test fun `line-number rows retain multiline comment and string context`() {
        val code = "/*\nval fake = true\n*/\nval message = \"\"\"hello\nval alsoFake = false\n\"\"\"\nval answer = 42\n"
        val palette = HighlightTextColorPalette.Default
        val highlighted = highlightedCodeText(code, "kotlin", palette, CodeHighlighter())
        val rows = highlighted.highlightedLines()
        assertEquals(code.lines(), rows.map { it.text })
        assertTrue(rows[1].spanStyles.any { it.item.color == palette.comment })
        assertFalse(rows[1].spanStyles.any { it.item.color == palette.keyword })
        assertTrue(rows[4].spanStyles.any { it.item.color == palette.string })
        assertTrue(rows[6].spanStyles.any { it.item.color == palette.keyword })
        assertEquals("", rows.last().text)
    }

    @Test fun `CRLF rows keep offsets and colors without newline drift`() {
        val text = highlightedCodeText("/*\r\nclass Test\r\n*/\r\n", "kotlin", HighlightTextColorPalette.Default, CodeHighlighter())
        val rows = text.highlightedLines()
        assertEquals(listOf("/*", "class Test", "*/", ""), rows.map { it.text })
        assertTrue(rows[1].spanStyles.any { it.start == 0 && it.end == rows[1].length })
    }

    @Test fun `class type function and boolean scopes use their own palette slots`() {
        val palette = HighlightTextColorPalette.Default.copy(className = Color.Red, function = Color.Blue, boolean = Color.Green)
        for ((scope, color) in listOf("title.class" to Color.Red, "type" to Color.Red,
            "title.function" to Color.Blue, "literal" to Color.Green)) {
            val text = buildAnnotatedString { buildHighlightText(HighlightToken.Styled("token", scope), palette) }
            assertEquals(color, text.spanStyles.single().item.color)
        }
    }
}
