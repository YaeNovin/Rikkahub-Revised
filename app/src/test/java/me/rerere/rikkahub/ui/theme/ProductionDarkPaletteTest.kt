package me.rerere.rikkahub.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.*
import org.junit.Test

class ProductionDarkPaletteTest {
    @Test fun `pale chromatic wallpaper still supplies a hue instead of falling back to pure black`() {
        val pale = Color(0xFFF9E8FF)
        val seed = backgroundTextSeed(listOf(pale))
        assertNotNull(seed)
        val foreground = textForegroundFor(listOf(pale), seed)
        assertNotEquals(Color.Black, foreground)
        assertTrue(wcagContrastRatio(foreground, pale) >= 4.5f)
    }

    @Test fun `dark card tokens do not inherit the black foreground selected for a bright wallpaper`() {
        val page = PresetThemes.first().standardDark.withReadableForeground(Color.Black)
        val surface = Color(0xFF413738)
        for (seed in listOf(0xFF005A88.toInt(), 0xFF7755CC.toInt())) {
            val actual = page.withSurfaceTextColors(textForegroundFor(listOf(surface), seed))
            assertTrue(wcagContrastRatio(actual.onSurface, surface) >= 4.5f)
            assertTrue(wcagContrastRatio(actual.onSurfaceVariant, surface) >= 4.5f)
            assertNotEquals(Color.Black, actual.onSurface)
            assertEquals(Color.Black, page.onSurface)
            assertEquals(page.surface, actual.surface)
        }
    }

    @Test fun `all real dark themes retain light readable roles when wallpaper hue arrives`() {
        val themes = PresetThemes.map { it.standardDark } + listOf(0xFF607D98, 0xFFFFCC00, 0xFF005500)
            .map { CustomTheme(primaryColorArgb = it).generateColorScheme(true) }
        for (theme in themes) for (seed in listOf(null, 0xFF607D98.toInt(), 0xFF000000.toInt(), 0xFFFFFFFF.toInt())) {
            val actual = theme.withTextPalette(seed).withAccessibleTextRoles()
            for ((text, surface) in listOf(actual.onBackground to actual.background,
                actual.onSurface to actual.surface, actual.onSurfaceVariant to actual.surfaceVariant)) {
                assertNotEquals(Color.Black, text)
                assertTrue(wcagContrastRatio(text, surface) >= 4.5f)
            }
        }
    }
}
