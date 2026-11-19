package me.rerere.rikkahub.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import me.rerere.rikkahub.data.datastore.TextColorMode
import me.rerere.rikkahub.ui.components.message.messageTextPalette
import org.junit.Assert.*
import org.junit.Test

class MessageTextPaletteTest {
    @Test fun `mid-tone surfaces keep sampled hues when seven to one is impossible`() {
        val surface = Color(0xFF666666)
        val color = textForegroundFor(listOf(surface), 0xFF7755CC.toInt())
        assertTrue(wcagContrastRatio(color, surface) >= 4.5f)
        assertNotEquals(Color.White, color)
        assertTrue(maxOf(color.red, color.green, color.blue) - minOf(color.red, color.green, color.blue) > .005f)
    }

    @Test fun `selected hue applies to opaque user bubbles and transparent message bodies in dark mode`() {
        for (container in listOf(Color(0xFF26304A), Color.Transparent)) {
            val background = Color(0xFF10131A)
            val app = messageTextPalette(TextColorMode.APP_BACKGROUND, container, listOf(background), 0xFF7755CC.toInt(), Color.White)
            val wallpaper = messageTextPalette(TextColorMode.SYSTEM_WALLPAPER, container, listOf(background), 0xFF008855.toInt(), Color.White)
            assertNotEquals(Color.White, app.foreground)
            assertNotEquals(app.foreground, wallpaper.foreground)
            assertEquals(listOf(container.compositeOver(background)), app.backgrounds)
            assertTrue(wcagContrastRatio(app.foreground, app.backgrounds.single()) >= 7f)
            assertTrue(wcagContrastRatio(wallpaper.foreground, wallpaper.backgrounds.single()) >= 7f)
            assertEquals(Color.White, messageTextPalette(TextColorMode.THEME, container,
                listOf(background), 0xFF7755CC.toInt(), Color.White).foreground)
        }
    }

    @Test fun `wallpaper text targets stronger contrast when that contrast is achievable`() {
        for (background in listOf(Color(0xFFB1A2B2), Color(0xFF17233B), Color.Black)) {
            val color = textForegroundFor(listOf(background), 0xFF7755CC.toInt())
            assertTrue("$color over $background", wcagContrastRatio(color, background) >= 7f)
            assertNotEquals(readableForegroundColor(background), color)
        }
    }
}
