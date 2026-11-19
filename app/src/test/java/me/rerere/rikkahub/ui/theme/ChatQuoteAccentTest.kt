package me.rerere.rikkahub.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import me.rerere.material3.hctColorFromArgb
import org.junit.Assert.*
import org.junit.Test

class ChatQuoteAccentTest {
    private val seeds = listOf(Color(0xFFFF4655), Color(0xFF2677FF), Color(0xFF20B46A), Color(0xFFAE47DF), Color(0xFFFFCC25), Color.Cyan)

    @Test fun `light dark and AMOLED accents preserve the chosen hue while remaining vivid with high contrast`() {
        for (seed in seeds) for (background in listOf(Color.White, Color(0xFFF8F9FF), Color(0xFF121316), Color.Black)) {
            val actual = chatQuoteAccent(seed, listOf(background))
            val hct = hctColorFromArgb(actual.toArgb())
            val original = hctColorFromArgb(seed.toArgb())
            val distance = kotlin.math.abs(hct.hue - original.hue).let { minOf(it, 360 - it) }
            assertTrue("$seed / $background / $actual", wcagContrastRatio(actual, background) >= 4.5f)
            assertTrue("chroma ${hct.chroma}", hct.chroma >= 20)
            assertTrue("hue drift $distance", distance < 8.0)
            assertNotEquals(Color.Black, actual)
            assertNotEquals(Color.White, actual)
            assertEquals(1f, actual.alpha, 0f)
        }
    }

    @Test fun `selected text palette takes priority over unrelated material primary and bubbles`() {
        assertEquals(seeds[0], chatQuoteSeed(seeds[0].toArgb(), seeds[1], seeds[2]))
        assertEquals(seeds[1], chatQuoteSeed(null, seeds[1], seeds[2]))
        assertEquals(seeds[2], chatQuoteSeed(null, Color.Black, seeds[2]))
        assertEquals(seeds[2], chatQuoteSeed(Color.Gray.toArgb(), Color.White, seeds[2]))
        assertNotEquals(chatQuoteAccent(seeds[0], listOf(Color.Black)), chatQuoteAccent(seeds[1], listOf(Color.Black)))
    }

    @Test fun `mixed dark image does not wash a red emphasis into pale low chroma text`() {
        val samples = listOf(0xFFADADAD, 0xFF9A7E79, 0xFF1A2D28, 0xFF65736B, 0xFF444B46).map { Color(it) }
        val actual = chatQuoteAccent(Color(0xFFDB4940), samples, Color(0xFFFFEFEE))
        assertTrue(hctColorFromArgb(actual.toArgb()).chroma >= 30)
        assertNotEquals(Color(0xFFFFEFEE), actual)
        assertTrue(samples.any { wcagContrastRatio(actual, it) >= 4.5f })
    }

    @Test fun `mixed wallpapers keep hue without claiming every pixel can reach the same contrast`() {
        val backgrounds = listOf(Color.Black, Color.White, Color(0xFF886655))
        for (seed in seeds) {
            val actual = chatQuoteAccent(seed, backgrounds)
            assertTrue(hctColorFromArgb(actual.toArgb()).chroma >= 12)
            assertNotEquals(Color.Black, actual)
            assertNotEquals(Color.White, actual)
            assertTrue(backgrounds.any { wcagContrastRatio(actual, it) >= 4.5f })
        }
    }

    @Test fun `assistant emphasis on mixed pink and green wallpaper is chromatic instead of near black`() {
        val samples = listOf(0xFFFFFCFA, 0xFFFFE5DE, 0xFFF6C3BA, 0xFFD8CFCC, 0xFFA7B8AF, 0xFF31584F)
            .map { Color(it) }
        val body = Color(0xFF08283B)
        val actual = chatQuoteAccent(body, samples, body)
        val hct = hctColorFromArgb(actual.toArgb())
        assertNotEquals(body, actual)
        assertTrue("chroma=${hct.chroma}", hct.chroma >= 30 &&
            hct.chroma >= hctColorFromArgb(body.toArgb()).chroma * 1.25)
        assertTrue("tone=${hct.tone}", hct.tone >= 34.5)
        assertTrue(wcagContrastRatio(actual, samples.first()) >= 4.5f)
    }

    @Test fun `purple user bubble emphasis no longer converges on the normal text color`() {
        val body = Color(0xFFF6D5FF)
        val background = Color(0xFF4F378B)
        val actual = chatQuoteAccent(Color(0xFFAE47DF), listOf(background), body)
        assertNotEquals(body, actual)
        assertTrue(wcagContrastRatio(actual, background) >= 4.5f)
        assertTrue(hctColorFromArgb(actual.toArgb()).chroma > hctColorFromArgb(body.toArgb()).chroma)
    }
}
