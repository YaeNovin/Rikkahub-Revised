package me.rerere.rikkahub.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import me.rerere.material3.hctColorFromArgb
import me.rerere.rikkahub.data.datastore.TextColorMode
import me.rerere.rikkahub.data.model.GradientBackgroundPreset
import me.rerere.rikkahub.ui.components.ui.tintedSurfaceForeground
import org.junit.Assert.*
import org.junit.Test

class TextColorPaletteTest {
    private val redSeed = Color(0xFFFF4655).toArgb()
    private val blueSeed = Color(0xFF276BDF).toArgb()

    @Test fun `auto clear stays neutral and modes do not borrow each others sources`() {
        assertNull(textPaletteSeed(TextColorMode.THEME, redSeed, blueSeed))
        assertNull(textPaletteSeed(TextColorMode.AUTO_CLEAR, redSeed, blueSeed))
        assertEquals(redSeed, textPaletteSeed(TextColorMode.APP_BACKGROUND, redSeed, blueSeed))
        assertEquals(blueSeed, textPaletteSeed(TextColorMode.SYSTEM_WALLPAPER, redSeed, blueSeed))
        assertNull(textPaletteSeed(TextColorMode.APP_BACKGROUND, null, blueSeed))
        assertNull(textPaletteSeed(TextColorMode.SYSTEM_WALLPAPER, redSeed, null))
        assertEquals(Color.White, textForegroundFor(listOf(Color(0xFF121316)), null))
        assertEquals(Color.Black, textForegroundFor(listOf(Color.White), null))
    }

    @Test fun `light and dark text has a visible hue with verified rgb contrast`() {
        for (background in listOf(Color(0xFF121316), Color(0xFFF8F9FF))) {
            val red = textForegroundFor(listOf(background), redSeed)
            val blue = textForegroundFor(listOf(background), blueSeed)
            assertNotEquals(red, blue)
            for (foreground in listOf(red, blue)) {
                assertTrue(wcagContrastRatio(foreground, background) >= 4.5f)
                assertTrue(hctColorFromArgb(foreground.toArgb()).chroma in 18.0..34.0)
                assertEquals(1f, foreground.alpha, 0f)
            }
        }
    }

    @Test fun `color correction is safe across midtones and saturated solid backgrounds`() {
        val backgrounds = (0..255 step 5).map { Color(it, it, it) } +
            listOf(Color.Red, Color.Green, Color.Blue, Color.Yellow, Color.Cyan, Color.Magenta)
        for (background in backgrounds) {
            for (seed in listOf(redSeed, blueSeed, null)) {
                val result = textForegroundFor(listOf(background), seed)
                assertTrue("$background / $result", wcagContrastRatio(result, background) >= 4.5f)
            }
        }
    }

    @Test fun `mixed image retains hue without reducing neutral contrast coverage or modifying pixels`() {
        val samples = listOf(Color.Black, Color.White, Color(0xFFAA6688))
        val original = samples.toList()
        val neutral = readableForegroundColor(samples)
        val actual = textForegroundFor(samples, blueSeed)
        assertNotEquals(neutral, actual)
        assertTrue(samples.count { wcagContrastRatio(actual, it) >= 4.5f } >= samples.count { wcagContrastRatio(neutral, it) >= 4.5f })
        assertTrue(samples.minOf { wcagContrastRatio(actual, it) } >= samples.minOf { wcagContrastRatio(neutral, it) })
        assertEquals(original, samples)
        assertEquals(Color.Magenta, textForegroundFor(emptyList(), blueSeed, Color.Magenta))
    }

    @Test fun `turning text colors off restores the selected theme foreground`() {
        val themeText = Color(0xFFE8E0E9)
        val samples = listOf(Color(0xFF121316))
        val blue = backgroundTextForeground(TextColorMode.SYSTEM_WALLPAPER, samples, blueSeed, themeText, true)
        val red = backgroundTextForeground(TextColorMode.APP_BACKGROUND, samples, redSeed, themeText, true)
        assertNotEquals(blue, red)
        assertNotEquals(themeText, blue)
        assertEquals(themeText, backgroundTextForeground(TextColorMode.THEME, samples, blueSeed, themeText, true))
        assertEquals(Color.White, backgroundTextForeground(TextColorMode.AUTO_CLEAR, samples, null, themeText, true))
        assertEquals(themeText, backgroundTextForeground(TextColorMode.APP_BACKGROUND, samples, null, themeText, false))
        assertEquals(themeText, backgroundTextForeground(TextColorMode.AUTO_CLEAR, samples, null, themeText, false))
    }

    @Test fun `readability only overrides foreground roles and leaves buttons and outlines alone`() {
        for (scheme in listOf(lightColorScheme(), darkColorScheme())) {
            val changed = scheme.withReadableForeground(Color(blueSeed))
            assertEquals(scheme.toString(), changed.copy(onSurface = scheme.onSurface,
                onBackground = scheme.onBackground, onSurfaceVariant = scheme.onSurfaceVariant).toString())
        }
    }

    @Test fun `accent extraction ignores neutral colors and isolated saturated outliers`() {
        assertNull(backgroundTextSeed(listOf(Color.Black, Color.White, Color.Gray)))
        val dominant = backgroundTextSeed(List(12) { Color(blueSeed) } + Color(redSeed))
        assertEquals(blueSeed, dominant)
    }

    @Test fun `wallpaper metadata wins and unsupported sources fall back safely`() {
        assertEquals(redSeed, resolveWallpaperTextSeed(redSeed, blueSeed).argb)
        assertEquals(blueSeed, resolveWallpaperTextSeed(null, blueSeed).argb)
        assertNull(resolveWallpaperTextSeed(null, null).argb)
    }

    @Test fun `all background and semantic color roles remain identical`() {
        for (original in listOf(lightColorScheme(), darkColorScheme())) {
            val result = original.withTextPalette(blueSeed)
            // Comparing the full scheme after undoing only these three foreground fields also
            // catches accidental edits to surface/container/outline/semantic roles.
            assertEquals(original.toString(), result.copy(
                onSurface = original.onSurface,
                onSurfaceVariant = original.onSurfaceVariant,
                onBackground = original.onBackground,
            ).toString())
            assertTrue(wcagContrastRatio(result.onSurface, result.surface) >= 4.5f)
            assertTrue(wcagContrastRatio(result.onBackground, result.background) >= 4.5f)
            assertTrue(wcagContrastRatio(result.onSurfaceVariant, result.surfaceVariant) >= 4.5f)
            assertSame(original, original.withTextPalette(null))
        }
    }

    @Test fun `amoled colors remain exact when following wallpaper`() {
        for (background in listOf(AMOLED_DARK_BACKGROUND, AMOLED_PURE_BLACK_BACKGROUND)) {
            val scheme = darkColorScheme(background = background, surface = background).withTextPalette(redSeed)
            assertEquals(background, scheme.background)
            assertEquals(background, scheme.surface)
            assertTrue(wcagContrastRatio(scheme.onBackground, background) >= 4.5f)
        }
    }

    @Test fun `gradient palette stays the same when foreground hue changes`() {
        for (dark in listOf(true, false)) {
            val scheme = if (dark) darkColorScheme() else lightColorScheme()
            for (preset in GradientBackgroundPreset.entries) {
                for (followTheme in listOf(true, false)) {
                    assertEquals(
                        createGradientBackgroundPalette(scheme, dark, followTheme, preset),
                        createGradientBackgroundPalette(scheme.withTextPalette(blueSeed), dark, followTheme, preset),
                    )
                }
            }
        }
    }

    @Test fun `opaque surface recalculates text tone without losing the selected hue`() {
        val samples = listOf(Color.White)
        val dark = Color(0xFF121316)
        val result = tintedSurfaceForeground(dark, 1f, Color.Black, samples, redSeed)
        assertEquals(textForegroundFor(listOf(dark), redSeed), result)
        assertNotEquals(Color.White, result)
        assertTrue(wcagContrastRatio(result, dark) >= 4.5f)
    }

    @Test fun `composer and glass text are validated against the actual tint composite`() {
        val samples = listOf(Color(0xFFEBDEEF), Color(0xFFFFE0DC))
        val tint = Color(0xFF121316)
        val painted = samples.map { tint.copy(alpha = .8f).compositeOver(it) }
        val result = tintedSurfaceForeground(tint, .8f, Color.Black, samples, blueSeed)
        assertEquals(textForegroundFor(painted, blueSeed), result)
        assertTrue(painted.all { wcagContrastRatio(result, it) >= 4.5f })
        assertEquals(.8f, tint.copy(alpha = .8f).alpha, .001f)
    }
}
