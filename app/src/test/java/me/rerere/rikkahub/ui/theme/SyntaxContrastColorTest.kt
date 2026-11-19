package me.rerere.rikkahub.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.buildAnnotatedString
import me.rerere.highlight.CodeHighlighter
import me.rerere.highlight.HighlightTextColorPalette
import me.rerere.highlight.buildHighlightText
import me.rerere.highlight.highlightedLines
import org.junit.Assert.*
import org.junit.Test

class SyntaxContrastColorTest {
    private fun assertOriginalRoles(original: HighlightTextColorPalette, actual: HighlightTextColorPalette) {
        assertEquals("Only comment may change", original, actual.copy(comment = original.comment))
    }

    @Test fun `dark wallpapers use the original dark palette even in a light application theme`() {
        val backgrounds = listOf(Color.Black, Color(0xFF101218), Color(0xFF282C34))
        for (darkTheme in listOf(false, true)) {
            val palette = codeHighlightPalette(darkTheme, backgrounds)
            assertOriginalRoles(AtomOneDarkPalette, palette)
            assertTrue(backgrounds.all { wcagContrastRatio(palette.comment, it) >= 4.5f })
        }
    }

    @Test fun `light backgrounds use original light tokens and a readable darker comment`() {
        val backgrounds = listOf(Color.White, Color(0xFFF9F9FF))
        for (darkTheme in listOf(false, true)) {
            val palette = codeHighlightPalette(darkTheme, backgrounds)
            assertOriginalRoles(AtomOneLightPalette, palette)
            assertTrue(backgrounds.all { wcagContrastRatio(palette.comment, it) >= 4.5f })
        }
    }

    @Test fun `mixed wallpaper keeps original roles and uses the dominant surface or theme on a tie`() {
        for (dark in listOf(false, true)) {
            val original = if (dark) AtomOneDarkPalette else AtomOneLightPalette
            assertOriginalRoles(original, codeHighlightPalette(dark, listOf(Color.Black, Color.White)))
            assertOriginalRoles(original, codeHighlightPalette(dark, emptyList()))
            assertOriginalRoles(original, codeHighlightPalette(dark, listOf(Color.Unspecified, Color.Transparent)))
        }
        assertOriginalRoles(AtomOneDarkPalette, codeHighlightPalette(false, List(8) { Color.Black } + Color.White))
        assertOriginalRoles(AtomOneLightPalette, codeHighlightPalette(true, List(8) { Color.White } + Color.Black))
    }

    @Test fun `opaque code or message surface takes priority over wallpaper brightness`() {
        val surface = textContrastSurface(Color.White, Color.Black, Color.White, listOf(Color.Black))
        assertOriginalRoles(AtomOneLightPalette, codeHighlightPalette(true, surface.backgrounds))
        assertEquals(Color.White, surface.container)
        val transparent = textContrastSurface(Color.Transparent, Color.White, Color.Black, listOf(Color.Black))
        assertOriginalRoles(AtomOneDarkPalette, codeHighlightPalette(false, transparent.backgrounds))
        assertEquals(Color.Transparent, transparent.container)
    }

    @Test fun `actual Highlight output retains original token spans and readable multiline comments`() {
        val code = "/* 注释\nval fake = true\n*/\ndata class Star(val count: Int)\nfun draw() = \"violet\"\nval count = 42"
        for ((dark, background) in listOf(false to Color.Black, true to Color.White)) {
            val palette = codeHighlightPalette(dark, listOf(background))
            val original = if (background == Color.Black) AtomOneDarkPalette else AtomOneLightPalette
            val highlighted = buildAnnotatedString {
                CodeHighlighter().highlight(code, "kotlin").forEach { buildHighlightText(it, palette) }
            }
            assertEquals(code, highlighted.text)
            assertTrue(highlighted.spanStyles.any { it.item.color == original.keyword })
            assertTrue(highlighted.spanStyles.any { it.item.color == original.string })
            assertTrue(highlighted.spanStyles.any { it.item.color == original.number })
            assertTrue(highlighted.spanStyles.any { it.item.color == original.function })
            val comment = highlighted.highlightedLines()[1]
            assertTrue(comment.spanStyles.any { it.item.color == palette.comment })
            assertFalse(comment.spanStyles.any { it.item.color == original.keyword })
            assertTrue(wcagContrastRatio(palette.comment, background) >= 4.5f)
        }
    }
}
