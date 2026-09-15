package me.rerere.rikkahub.ui.components.ui

import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.*
import org.junit.Test

class MenuViewportTest {
    @Test fun menuHeightComesFromViewportRatherThanOptionCount() {
        val limits = menuViewportLimits(IntSize(1080, 2400), 3f, 0, 240f)
        val bounds = limits.constrain(Constraints(maxWidth = 1000, maxHeight = Constraints.Infinity))
        assertEquals(672, bounds.maxHeight)
        assertTrue(bounds.maxWidth <= 1080)
        assertTrue(bounds.hasBoundedHeight)
        assertTrue(bounds.hasBoundedWidth)
    }
    @Test fun keyboardAndTransientSmallWindowsNeverInvertBounds() {
        for (window in listOf(IntSize.Zero, IntSize(400, 600), IntSize(Int.MAX_VALUE, Int.MAX_VALUE))) {
            for (density in listOf(0f, 1f, 3f, Float.NaN, Float.POSITIVE_INFINITY)) {
                val limits = menuViewportLimits(window, density, 1200, Float.POSITIVE_INFINITY)
                val bounded = limits.constrain(Constraints())
                assertTrue(bounded.maxWidth in 1..8190)
                assertTrue(bounded.maxHeight in 1..8190)
                assertTrue(bounded.minWidth <= bounded.maxWidth)
                assertTrue(bounded.minHeight <= bounded.maxHeight)
            }
        }
    }
    @Test fun intrinsicQueriesDoNotMeasureLayeredBackgroundOrLongColumn() {
        val policy = MenuViewportMeasurePolicy(menuViewportLimits(IntSize(1080, 2400), 3f, 0, 320f))
        val scope = object : IntrinsicMeasureScope {
            override val density = 3f
            override val fontScale = 1f
            override val layoutDirection = LayoutDirection.Ltr
        }
        val unsafeChild = object : IntrinsicMeasurable {
            override val parentData: Any? = null
            override fun minIntrinsicWidth(height: Int): Int = error("Unexpected background intrinsic query")
            override fun maxIntrinsicWidth(height: Int): Int = error("Unexpected background intrinsic query")
            override fun minIntrinsicHeight(width: Int): Int = error("Unexpected long list intrinsic query")
            override fun maxIntrinsicHeight(width: Int): Int = error("Unexpected long list intrinsic query")
        }
        with(policy) {
            with(scope) {
                assertEquals(840, maxIntrinsicWidth(listOf(unsafeChild), Constraints.Infinity))
                assertEquals(912, maxIntrinsicHeight(listOf(unsafeChild), 32767))
                assertEquals(840, minIntrinsicWidth(listOf(unsafeChild), Constraints.Infinity))
                assertEquals(912, minIntrinsicHeight(listOf(unsafeChild), 32767))
            }
        }
    }
    @Test fun callerCanRequestSmallerSizeWithoutForcingViewportToMaximum() {
        val limits = menuViewportLimits(IntSize(1080, 2400), 3f, 0, 320f)
        assertEquals(Constraints.fixed(400, 200), limits.constrain(Constraints.fixed(400, 200)))
    }
}
