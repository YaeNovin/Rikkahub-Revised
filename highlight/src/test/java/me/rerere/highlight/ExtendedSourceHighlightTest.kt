package me.rerere.highlight

import org.junit.Assert.*
import org.junit.Test

class ExtendedSourceHighlightTest {
    @Test fun `default cap stays unchanged and extended JSON sources opt in explicitly`() {
        val code = "{\"layers\":[" + List(180) { "{\"type\":\"star\",\"radius\":76,\"active\":true}" }.joinToString(",") + "]}"
        assertTrue(code.length > 4096 && code.length < 32768)
        val highlighter = CodeHighlighter()
        val plain = highlightedCodeText(code, "json", HighlightTextColorPalette.Default, highlighter)
        assertEquals(code, plain.text); assertTrue(plain.spanStyles.isEmpty())
        val colored = highlightedCodeText(code, "json", HighlightTextColorPalette.Default, highlighter, 32768)
        assertEquals(code, colored.text); assertTrue(colored.spanStyles.size > 180)
    }
    @Test fun `extended limit cannot remove hard cap or truncate original source`() {
        val code = "{\"title\":\"" + "x".repeat(32768) + "\"}"
        val result = highlightedCodeText(code, "json", HighlightTextColorPalette.Default, CodeHighlighter(), Int.MAX_VALUE)
        assertEquals(code, result.text); assertTrue(result.spanStyles.isEmpty())
        val disabled = highlightedCodeText("{\"x\":1}", "json", HighlightTextColorPalette.Default, CodeHighlighter(), 0)
        assertTrue(disabled.spanStyles.isEmpty())
    }
    @Test fun `JSON keys strings literals and numbers receive distinct theme colors`() {
        val code = """{"title":"曼陀罗","radius":70,"animate":true,"optional":null}"""
        val colors = HighlightTextColorPalette.Default
        val result = highlightedCodeText(code, "json", colors, CodeHighlighter())
        assertEquals(code, result.text)
        assertTrue(result.spanStyles.any { it.item.color == colors.attrName })
        assertTrue(result.spanStyles.any { it.item.color == colors.string })
        assertTrue(result.spanStyles.any { it.item.color == colors.number })
        assertTrue(result.spanStyles.any { it.item.color == colors.keyword })
    }
    @Test fun `incomplete and malformed JSON remains verbatim and never executes`() {
        val code = """{"title":"<script>alert(1)</script>","layers":[{"radius":6"""
        val result = highlightedCodeText(code, "json", HighlightTextColorPalette.Default, CodeHighlighter(), 32768)
        assertEquals(code, result.text)
        assertTrue(result.spanStyles.isNotEmpty())
    }
}
