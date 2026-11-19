package me.rerere.rikkahub.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import org.junit.Assert.*
import org.junit.Test

class MicaPaletteTest {
    @Test fun `standalone Mica panels inherit opaque material roles instead of the page scheme`() {
        for (dark in listOf(false, true)) {
            val original = if (dark) darkColorScheme() else lightColorScheme()
            val palette = micaPalette(original, dark, listOf(Color.Blue))
            val result = micaColorScheme(original, palette)
            assertEquals(palette.baseTop, result.background)
            assertEquals(palette.card, result.surface)
            assertEquals(palette.card, result.surfaceContainer)
            assertEquals(palette.raisedCard, result.surfaceContainerHigh)
            assertEquals(palette.foreground, result.onSurface)
            assertEquals(palette.secondaryForeground, result.onSurfaceVariant)
            assertNotEquals(result.background, result.surfaceContainer)
            assertEquals(1f, result.background.alpha)
            assertEquals(1f, result.surfaceContainer.alpha)
            assertEquals(original.error, result.error)
        }
    }
    @Test fun `all combinations retain readable text and opaque ordered layers`() {
        val sources = listOf(emptyList(), listOf(Color.Black), listOf(Color.White),
            listOf(Color.Red, Color.Green, Color.Blue), listOf(Color(0xFF527CC6), Color(0xFF9870AB)))
        for (dark in listOf(false, true)) for (samples in sources) for (strength in listOf(0f, .45f, 1f)) {
            val theme = if (dark) darkColorScheme() else lightColorScheme()
            val palette = micaPalette(theme, dark, samples, strength)
            val bases = (0..20).map { lerp(palette.baseTop, palette.baseBottom, it / 20f) }
            for (surface in bases + palette.card + palette.raisedCard) {
                assertEquals(1f, surface.alpha)
                for (text in listOf(palette.foreground, palette.secondaryForeground, palette.accent)) {
                    assertTrue("dark=$dark samples=$samples strength=$strength", wcagContrastRatio(text, surface) >= 4.5f)
                }
            }
            assertTrue(wcagRelativeLuminance(palette.card) > wcagRelativeLuminance(palette.baseBottom))
            assertTrue(wcagRelativeLuminance(palette.raisedCard) > wcagRelativeLuminance(palette.card))
        }
    }

    @Test fun `wallpaper changes tint while absence still produces a complete material`() {
        val theme = lightColorScheme(primary = Color(0xFF2A7754))
        val absent = micaPalette(theme, false)
        val blue = micaPalette(theme, false, listOf(Color.Blue))
        val red = micaPalette(theme, false, listOf(Color.Red))
        assertNotEquals(absent.baseTop, blue.baseTop)
        assertNotEquals(blue.baseTop, red.baseTop)
        assertNotEquals(absent.card, absent.baseTop)
        assertEquals(absent, micaPalette(theme, false, listOf(Color.Unspecified, Color.Transparent)))
    }

    @Test fun `invalid and out of range tint values remain stable`() {
        val theme = darkColorScheme()
        assertEquals(micaPalette(theme, true), micaPalette(theme, true, tintStrength = Float.NaN))
        assertEquals(micaPalette(theme, true, tintStrength = 0f), micaPalette(theme, true, tintStrength = -1f))
        assertEquals(micaPalette(theme, true, tintStrength = 1f), micaPalette(theme, true, tintStrength = 5f))
    }
}
