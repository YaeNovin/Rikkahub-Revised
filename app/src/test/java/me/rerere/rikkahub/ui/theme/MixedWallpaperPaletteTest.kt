package me.rerere.rikkahub.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import me.rerere.rikkahub.ui.components.richtext.withReadableSpans
import org.junit.Assert.*
import org.junit.Test

class MixedWallpaperPaletteTest {
    @Test fun `readability typography removes old shadows and increases weight instead`() {
        val style = TextStyle(shadow = androidx.compose.ui.graphics.Shadow(Color.Black,
            androidx.compose.ui.geometry.Offset.Zero, 4.5f))
        val changed = style.withReadabilityWeight()
        assertNull(changed.shadow)
        assertEquals(440, changed.fontWeight!!.weight)
        val typography = Typography.withReadabilityWeight()
        assertEquals(440, typography.bodyLarge.fontWeight!!.weight)
        assertEquals(550, typography.labelLarge.fontWeight!!.weight)
    }
    // A photo-like range of pastel highlights and colored shadows, rather than a flat wallpaper.
    private val raw = listOf(0xFFC6B7CE, 0xFFAFC6D4, 0xFFFCF0F4, 0xFFEFE3E7,
        0xFFA8A7C6, 0xFFBBCFDA, 0xFFEAA7AE, 0xFF94ABBD, 0xFFC6B7CE, 0xFF8590A4,
        0xFFAEC5D5, 0xFFFFE3E5, 0xFF8387A4, 0xFFACA3BE, 0xFFB5828B, 0xFFBFB9D3).map(::Color)

    @Test fun `mixed wallpapers retain a distinct visible hue for both text color sources`() {
        val image = backgroundSamplesWithOverlay(raw, Color(0xFF15111A), .28f, .42f)
        for (surface in listOf(Color.Transparent, Color(0xFF413738).copy(alpha = .35f))) {
            val painted = image.map { surface.compositeOver(it) }
            val app = textForegroundFor(painted, 0xFF8F799F.toInt())
            val wallpaper = textForegroundFor(painted, 0xFF5B95B4.toInt())
            assertNotEquals(app, wallpaper)
            for (color in listOf(app, wallpaper)) {
                assertNotEquals(Color.White, color)
                assertNotEquals(Color.Black, color)
                assertTrue(hasVisiblePaletteHue(color))
                assertTrue(preservesPaletteContrast(color, painted))
            }
        }
    }

    @Test fun `Markdown contrast correction does not bleach an already bounded palette to white`() {
        val samples = backgroundSamplesWithOverlay(raw, Color(0xFF15111A), .28f, .42f)
            .map { Color(0xFF413738).copy(alpha = .35f).compositeOver(it) }
        val selected = textForegroundFor(samples, 0xFF5B95B4.toInt())
        assertEquals(selected, contrastingTextColor(selected, samples))
        val text = AnnotatedString("Message body").withReadableSpans(samples, selected)
        assertTrue(text.spanStyles.isNotEmpty())
        assertTrue(text.spanStyles.all { it.item.color == selected })
    }
}
