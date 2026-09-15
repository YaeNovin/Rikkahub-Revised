package me.rerere.rikkahub.ui.components.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.*
import org.junit.Test

class SwipeRevealActionsTest {
    @Test fun `closed cards expose no actions regardless of foreground opacity`() {
        for (rtl in listOf(false, true)) {
            assertEquals(0f, swipeActionRevealWidth(0f, 320f, rtl), 0f)
            assertEquals(0f, swipeActionRevealWidth(Float.NaN, 320f, rtl), 0f)
            assertEquals(0f, swipeActionRevealWidth(Float.POSITIVE_INFINITY, 320f, rtl), 0f)
        }
    }

    @Test fun `only the exposed logical end strip is visible during drag and reset`() {
        listOf(0f, 1f, 60f, 120f, 320f, 900f, 100f, 0f).forEach { displacement ->
            val expected = displacement.coerceAtMost(320f)
            assertEquals(expected, swipeActionRevealWidth(-displacement, 320f, false), 0f)
            assertEquals(expected, swipeActionRevealWidth(displacement, 320f, true), 0f)
        }
        assertEquals(0f, swipeActionRevealWidth(80f, 320f, false), 0f)
        assertEquals(0f, swipeActionRevealWidth(-80f, 320f, true), 0f)
        assertEquals(0f, swipeActionRevealWidth(-80f, 0f, false), 0f)
    }

    @Test fun `overlay tabs inherit the background while ordinary tabs retain the theme`() {
        assertEquals(Color.Transparent, appearanceTabContainer(true, Color.Black))
        assertEquals(Color.Black, appearanceTabContainer(false, Color.Black))
        assertEquals(Color.White, appearanceTabContainer(false, Color.White))
    }
}
