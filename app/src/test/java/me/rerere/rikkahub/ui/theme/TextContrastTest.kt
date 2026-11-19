package me.rerere.rikkahub.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import org.junit.Assert.*
import org.junit.Test

class TextContrastTest {
    @Test fun `comments meet contrast on known backgrounds without recolouring Highlight or changing surfaces`() {
        val wallpapers = (0..255 step 5).map { Color(it, it, it) } +
            listOf(Color.Red, Color.Green, Color.Blue, Color.Magenta, Color.Cyan, Color.Yellow)
        for ((base, palette) in listOf(Color(0xFF121316) to AtomOneDarkPalette, Color(0xFFF9F9FF) to AtomOneLightPalette)) {
            for (opacity in listOf(0f, .2f, .62f, .9f, 1f)) {
                val requested = base.copy(alpha = opacity)
                for (wallpaper in wallpapers) {
                    val surface = textContrastSurface(requested, base, palette.fallback, listOf(wallpaper))
                    assertEquals(requested, surface.container)
                    val background = requested.compositeOver(wallpaper)
                    val actual = codeHighlightPalette(palette == AtomOneDarkPalette, surface.backgrounds)
                    assertTrue("opacity=$opacity, comment=${actual.comment}, background=$background",
                        wcagContrastRatio(actual.comment, background) >= 4.5f)
                    val original = if (actual.keyword == AtomOneDarkPalette.keyword) AtomOneDarkPalette else AtomOneLightPalette
                    assertEquals(original, actual.copy(comment = original.comment))
                }
            }
        }
    }

    @Test fun `mixed extremes preserve transparency without claiming universal contrast`() {
        val gray = Color(0xFF757575)
        assertTrue(wcagContrastRatio(gray, Color.White) >= 4.5f)
        assertTrue(wcagContrastRatio(gray, Color.Black) >= 4.5f)
        assertFalse(hasTextContrast(gray, listOf(Color.Black, Color.White)))
        val surface = textContrastSurface(Color.Transparent, Color.White, gray, listOf(Color.Black, Color.White))
        assertEquals(Color.Transparent, surface.container)
        assertEquals(listOf(Color.Black, Color.White), surface.backgrounds)
        assertFalse(hasTextContrast(surface.foreground, surface.backgrounds))
    }

    @Test fun `complex wallpapers never increase configured tint or opacity`() {
        val underlays = listOf(Color.Black, Color.White, Color.Red, Color.Blue)
        for (base in listOf(Color.White, Color.Black, Color(0xFF256390))) {
            for (opacity in listOf(0f, .08f, .2f, .62f, .9f, 1f)) {
                val requested = base.copy(alpha = opacity)
                val surface = textContrastSurface(requested, Color.White, Color.Gray, underlays)
                assertEquals(requested, surface.container)
                assertEquals(underlays.map { requested.compositeOver(it) }, surface.backgrounds)
            }
        }
    }

    @Test fun `transparent text uses sampled wallpaper brightness instead of always choosing black`() {
        for ((dominant, accent, expected) in listOf(
            Triple(Color(0xFF101218), Color.White, Color.White),
            Triple(Color(0xFFF2F4F8), Color.Black, Color.Black),
        )) {
            val samples = List(8) { dominant } + List(2) { accent }
            val surface = textContrastSurface(Color.Transparent, Color.White, Color.Gray, samples)
            assertEquals(Color.Transparent, surface.container)
            assertEquals(expected, surface.foreground)
            assertTrue(wcagContrastRatio(surface.foreground, dominant) >= 4.5f)
        }
    }

    @Test fun `toolbar and nested diff inherit configured surfaces without opaque backing`() {
        val bodyTint = Color.White.copy(alpha = .62f)
        val toolbarTint = Color(0xFF256390).copy(alpha = .2f)
        val body = textContrastSurface(bodyTint, Color.White, Color.Black, listOf(Color.Black, Color.White))
        val toolbar = textContrastSurface(toolbarTint, Color.White, Color.Gray, body.backgrounds)
        val diff = textContrastSurface(Color.Transparent, Color.White, Color.Black, body.backgrounds)

        assertEquals(bodyTint, body.container)
        assertEquals(toolbarTint, toolbar.container)
        assertEquals(body.backgrounds.map { toolbarTint.compositeOver(it) }, toolbar.backgrounds)
        assertTrue(wcagContrastRatio(toolbar.foreground, toolbar.backgrounds.first()) >= 4.5f)
        assertTrue(wcagContrastRatio(toolbar.foreground, toolbar.backgrounds.last()) >= 4.5f)
        assertEquals(Color.Transparent, diff.container)
        assertEquals(body.backgrounds, diff.backgrounds)
    }

    @Test fun `readable text preserves its color and configured surface`() {
        val surface = textContrastSurface(Color.White, Color.White, Color(0xFF262626), listOf(Color.Black, Color.White))
        assertEquals(Color.White, surface.container)
        assertEquals(Color(0xFF262626), surface.foreground)
        assertEquals(Color(0xFF262626), contrastingTextColor(Color(0xFF262626), listOf(Color.White)))
    }

    @Test fun `low alpha labels and comments become readable rather than disappearing`() {
        for (background in listOf(Color.White, Color.Black, Color(0xFF7F7F7F))) {
            val actual = contrastingTextColor(Color(0xFFF9A825).copy(alpha = .4f), listOf(background))
            assertTrue(wcagContrastRatio(actual, background) >= 4.5f)
        }
    }

    @Test fun `theme foreground correction preserves semantic fills and backgrounds`() {
        for (original in listOf(lightColorScheme(onSurface = Color(0xFFAAAAAA)),
            darkColorScheme(onSurfaceVariant = Color(0xFF444444)))) {
            val fixed = original.withAccessibleTextRoles()
            assertEquals(original.background, fixed.background)
            assertEquals(original.primary, fixed.primary)
            assertEquals(original.error, fixed.error)
            assertEquals(original.surfaceContainer, fixed.surfaceContainer)
            assertTrue(wcagContrastRatio(fixed.onSurface, fixed.surface) >= 4.5f)
            assertTrue(wcagContrastRatio(fixed.onSurfaceVariant, fixed.surfaceContainerHigh) >= 4.5f)
            assertTrue(wcagContrastRatio(fixed.onPrimary, fixed.primary) >= 4.5f)
            assertTrue(wcagContrastRatio(fixed.onError, fixed.error) >= 4.5f)
        }
    }
}
