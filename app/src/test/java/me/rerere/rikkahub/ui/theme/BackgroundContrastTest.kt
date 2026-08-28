package me.rerere.rikkahub.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundContrastTest {
    @Test
    fun `wcag relative luminance and contrast match reference values`() {
        assertEquals(0f, wcagRelativeLuminance(Color.Black), 0.0001f)
        assertEquals(1f, wcagRelativeLuminance(Color.White), 0.0001f)
        assertEquals(21f, wcagContrastRatio(Color.Black, Color.White), 0.001f)
        assertEquals(
            4.542f,
            wcagContrastRatio(Color(0xFF767676), Color.White),
            0.01f,
        )
    }

    @Test
    fun `dark backgrounds use a light foreground`() {
        assertEquals(Color.White, readableForegroundColor(Color(0xFF101827)))
    }

    @Test
    fun `light backgrounds use a dark foreground`() {
        assertEquals(Color.Black, readableForegroundColor(Color(0xFFE9EEF5)))
    }

    @Test
    fun `complex backgrounds choose the foreground passing the most sampled regions`() {
        val backgrounds = List(8) { Color(0xFFF4F7FA) } + List(2) { Color(0xFF151922) }

        assertEquals(Color.Black, readableForegroundColor(backgrounds))
    }

    @Test
    fun `background opacity and overlay participate in contrast selection`() {
        val effective = compositeChatBackgroundColor(
            imageColor = Color.Black,
            imageOpacity = 0.2f,
            overlayColor = Color.White,
            overlayAlpha = 0.6f,
        )

        assertTrue(effective.luminance() > 0.179f)
        assertEquals(Color.Black, readableForegroundColor(effective))
    }

    @Test
    fun `black text gets a local white readability halo`() {
        val halo = backgroundReadabilityHaloColor(Color.Black)

        assertEquals(Color.White, halo)
        assertEquals(21f, wcagContrastRatio(Color.Black, halo), 0.001f)
    }

    @Test
    fun `white text gets a local black readability halo`() {
        val shadow = backgroundReadabilityShadow(Color.White)

        assertEquals(Color.Black.red, shadow.color.red, 0.0001f)
        assertEquals(Color.Black.green, shadow.color.green, 0.0001f)
        assertEquals(Color.Black.blue, shadow.color.blue, 0.0001f)
        assertTrue(shadow.color.alpha > 0.9f)
        assertTrue(shadow.blurRadius > 0f)
    }

    @Test
    fun `readability keeps original component accent colors`() {
        val scheme = androidx.compose.material3.lightColorScheme(
            primary = Color(0xFF6750A4),
            secondary = Color(0xFF625B71),
            tertiary = Color(0xFF7D5260),
        )

        val readable = scheme.withReadableForeground(Color.Black)

        assertEquals(scheme.primary, readable.primary)
        assertEquals(scheme.secondary, readable.secondary)
        assertEquals(scheme.tertiary, readable.tertiary)
        assertEquals(Color.Black, readable.onSurface)
    }

    @Test
    fun `foreground extraction failure uses the theme fallback`() = runBlocking {
        val fallback = Color(0xFF123456)

        val foreground = safeBackgroundForegroundExtraction(fallback) {
            throw IOException("background is unavailable")
        }

        assertEquals(fallback, foreground)
    }

    @Test
    fun `foreground extraction preserves coroutine cancellation`() {
        try {
            runBlocking {
                safeBackgroundForegroundExtraction(Color.Black) {
                    throw CancellationException("cancelled")
                }
            }
            fail("Expected cancellation to propagate")
        } catch (_: CancellationException) {
            // Expected: cancellation must not be converted into a fallback color.
        }
    }
}
