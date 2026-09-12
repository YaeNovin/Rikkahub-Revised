package me.rerere.rikkahub.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import me.rerere.rikkahub.ui.components.ui.tintedSurfaceForeground
import org.junit.Assert.*
import org.junit.Test

class AdaptiveBackgroundReadabilityTest {
    @Test fun `foreground accounts for the original dark overlay`() {
        val raw = listOf(Color.White, Color.White)
        val painted = backgroundSamplesWithOverlay(raw, Color.Black, .65f, .75f)
        assertEquals(Color.White, adaptiveBackgroundReadability(painted, true).foreground)
        assertEquals(listOf(Color.White, Color.White), raw)
        assertEquals(Color.Black.copy(alpha = .675f).compositeOver(Color.White), painted.first())
    }
    @Test fun `mixed wallpaper samples remain unchanged when choosing text color`() {
        val samples = listOf(Color.Black, Color.White, Color(0xFF99707F), Color(0xFF366577))
        for (dark in listOf(true, false)) {
            val result = adaptiveBackgroundReadability(samples, dark)
            assertEquals(samples, result.backgrounds)
            assertEquals(samples, result.rawBackgrounds)
            assertEquals(readableForegroundColor(samples), result.foreground)
        }
    }

    @Test fun `uniform light and dark wallpapers choose contrasting text without changing their colors`() {
        val dark = adaptiveBackgroundReadability(listOf(Color(0xFF121316)), true)
        val light = adaptiveBackgroundReadability(listOf(Color(0xFFF9FAFF)), false)
        assertEquals(Color.White, dark.foreground); assertEquals(listOf(Color(0xFF121316)), dark.backgrounds)
        assertEquals(Color.Black, light.foreground); assertEquals(listOf(Color(0xFFF9FAFF)), light.backgrounds)
    }

    @Test fun `opaque dark surfaces do not inherit black wallpaper text`() {
        val result = tintedSurfaceForeground(Color(0xFF121316), 1f, Color.Black, listOf(Color.White))
        assertEquals(Color.White, result)
        assertTrue(wcagContrastRatio(result, Color(0xFF121316)) >= 4.5f)
    }
}
