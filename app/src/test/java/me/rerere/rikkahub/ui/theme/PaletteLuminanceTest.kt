package me.rerere.rikkahub.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.pow

class PaletteLuminanceTest {
    @Test fun `cached luminance retains WCAG ratios across hue and brightness boundaries`() {
        val colors = (0..255 step 17).map { Color(it, 255-it, (it * 7) % 256) } + listOf(Color.White, Color.Black)
        fun luminance(color: Color): Double {
            fun linear(channel: Float): Double = if (channel <= .04045f) channel / 12.92
                else ((channel + .055) / 1.055).pow(2.4)
            return .2126 * linear(color.red) + .7152 * linear(color.green) + .0722 * linear(color.blue)
        }
        for (foreground in colors) for (background in colors) {
            val a = luminance(foreground)
            val b = luminance(background)
            val expected = (maxOf(a,b) + .05) / (minOf(a,b) + .05)
            assertEquals(expected.toFloat(), contrastFromLuminances(wcagRelativeLuminance(foreground),
                wcagRelativeLuminance(background)), .00001f)
        }
    }
}
