package me.rerere.rikkahub.ui.components.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.cos

class AppearanceRenderMathTest {
    @Test fun `non finite imported effects never reach drawing primitives`() {
        listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY).forEach {
            assertEquals(0f, advancedAppearanceCapabilities(33).limitLiveBlur(it), 0f)
            assertEquals(0f, advancedAppearanceCapabilities(31).limitBackgroundBlur(it), 0f)
            assertEquals(0f, advancedAppearanceCapabilities(33).limitOpticalStrength(it), 0f)
            assertEquals(1f, isolatedSurfaceTintAlpha(it), 0f)
        }
    }
    @Test fun `opaque dark tint overrides black text chosen for a light image`() {
        assertEquals(Color.White, tintedSurfaceForeground(Color(0xFF121316), 1f, Color.Black))
        assertEquals(Color.White, tintedSurfaceForeground(Color(0xFF121316), .85f, Color.Black))
        assertEquals(Color.Black, tintedSurfaceForeground(Color.White, 1f, Color.White))
    }
    @Test fun `low tint preserves image based foreground`() {
        assertEquals(Color.Black, tintedSurfaceForeground(Color.Black, .1f, Color.Black))
        assertEquals(Color.White, tintedSurfaceForeground(Color.White, .1f, Color.White))
    }
    @Test fun `diagonal gradients use physical pixels on portrait and landscape`() {
        assertEquals(1f / 6f, Size(400f, 800f).gradientPosition(Offset(200f, 0f), 45f), .0001f)
        assertEquals(1f / 6f, Size(800f, 400f).gradientPosition(Offset(0f, 200f), 45f), .0001f)
        assertEquals(.5f, Size(400f, 800f).gradientPosition(Offset(200f, 400f), 45f), .0001f)
        assertEquals(0f, Size(400f, 800f).gradientPosition(Offset(200f, 0f), 0f), .0001f)
    }
    @Test fun `fractional motion stays continuous over base period boundaries`() {
        listOf(5500L to 1.15, 8500L to .9, 6200L to 1.1).forEach { (period, frequency) ->
            assertEquals(cos(gradientMotionPhase(period - 1, period, frequency)), cos(gradientMotionPhase(period + 1, period, frequency)), .01f)
            assertEquals(cos(gradientMotionPhase(3599999, period, frequency)), cos(gradientMotionPhase(3600001, period, frequency)), .01f)
        }
    }
}
