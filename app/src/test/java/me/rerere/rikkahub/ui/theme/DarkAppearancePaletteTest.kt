package me.rerere.rikkahub.ui.theme

import androidx.compose.ui.graphics.Color
import me.rerere.rikkahub.data.datastore.TextColorMode
import org.junit.Assert.*
import org.junit.Test

class DarkAppearancePaletteTest {
    @Test fun `dark and AMOLED surfaces remain readable for every text palette mode`() {
        for (surface in listOf(Color.Black, Color(0xFF121316), Color(0xFF202838))) {
            for (mode in TextColorMode.entries) {
                val seed = textPaletteSeed(mode, 0xFF7755CC.toInt(), 0xFF00AA88.toInt())
                val color = backgroundTextForeground(mode, listOf(surface), seed, Color(0xFFE6EDF3), true)
                assertTrue("$mode on $surface", wcagContrastRatio(color, surface) >= 4.5)
                assertNotEquals(Color.Black, color)
            }
        }
    }

    @Test fun `background palette changes cannot reuse a dark mode foreground on a bright image`() {
        for (mode in listOf(TextColorMode.AUTO_CLEAR, TextColorMode.APP_BACKGROUND, TextColorMode.SYSTEM_WALLPAPER)) {
            val seed = textPaletteSeed(mode, 0xFF7755CC.toInt(), 0xFF00AA88.toInt())
            val dark = backgroundTextForeground(mode, listOf(Color.Black), seed, Color.White, true)
            val bright = backgroundTextForeground(mode, listOf(Color(0xFFF7F0FF)), seed, Color.White, true)
            assertNotEquals(dark, bright)
            assertTrue(wcagContrastRatio(bright, Color(0xFFF7F0FF)) >= 4.5)
            assertTrue(wcagContrastRatio(dark, Color.Black) >= 4.5)
        }
    }
}
