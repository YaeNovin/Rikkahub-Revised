package me.rerere.rikkahub.ui.components.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.*
import org.junit.Test

class OverlayBackgroundRestorationTest {
    @Test fun `menu background never depends on the shared page layer being available`() {
        assertFalse(shouldUseSharedSurfaceBackground(IsolatedBackgroundRendering.INDEPENDENT, true))
        assertFalse(shouldUseSharedSurfaceBackground(IsolatedBackgroundRendering.INDEPENDENT, false))
        assertTrue(shouldUseSharedSurfaceBackground(IsolatedBackgroundRendering.SHARED_PAGE, true))
    }
    @Test fun `dark menu uses readable text before image loads or when image fails`() {
        val dark = Color(0xFF1B1D22)
        assertEquals(Color.White, isolatedSurfaceForeground(false, dark, dark, .35f, Color.White, Color.Black))
        // A foreground inherited from a bright chat image must not create black on black.
        assertEquals(Color.White, isolatedSurfaceForeground(false, dark, dark, .35f, Color.Black, Color.Black))
    }
    @Test fun `light menu uses readable fallback before image loads`() {
        assertEquals(Color.Black, isolatedSurfaceForeground(false, Color.White, Color.White, .35f, Color.Black, Color.White))
    }
    @Test fun `loaded image retains foreground unless menu tint requires stronger contrast`() {
        val dark = Color(0xFF1B1D22)
        assertEquals(Color.Black, isolatedSurfaceForeground(true, dark, dark, .1f, Color.White, Color.Black))
        assertEquals(Color.White, isolatedSurfaceForeground(true, dark, dark, 1f, Color.White, Color.Black))
    }
}
