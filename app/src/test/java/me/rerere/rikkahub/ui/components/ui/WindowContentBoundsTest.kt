package me.rerere.rikkahub.ui.components.ui

import androidx.compose.ui.unit.Constraints
import org.junit.Assert.*
import org.junit.Test

class WindowContentBoundsTest {
    @Test fun `use IME constrained parent rather than the full display and do not subtract IME twice`() {
        val limits = overlayHeightConstraints(Constraints(maxWidth = 800, maxHeight = 600), 2400, .88f, 0)
        assertEquals(528, limits.maxHeight)
        assertEquals(800, limits.maxWidth)
    }

    @Test fun `small window has no forced minimum or negative space`() {
        val limits = overlayHeightConstraints(Constraints(minHeight = 32, maxHeight = 32), 32, 1f, 48)
        assertEquals(0, limits.minHeight)
        assertEquals(0, limits.maxHeight)
    }

    @Test fun `unbounded preview uses current window and transient window uses parent`() {
        assertEquals(952, overlayHeightConstraints(Constraints(), 1000, 1f, 48).maxHeight)
        assertEquals(252, overlayHeightConstraints(Constraints(maxHeight = 300), 0, 1f, 48).maxHeight)
        assertEquals(Constraints(), overlayHeightConstraints(Constraints(), 0, 1f, 48))
    }

    @Test fun `bad fraction cannot grow beyond parent`() {
        assertEquals(100, overlayHeightConstraints(Constraints(maxHeight = 100), 200, Float.NaN, -20).maxHeight)
        assertEquals(100, overlayHeightConstraints(Constraints(maxHeight = 100), 200, 5f, 0).maxHeight)
    }
}
