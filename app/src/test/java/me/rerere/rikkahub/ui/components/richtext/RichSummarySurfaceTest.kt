package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.ui.graphics.Color
import me.rerere.rikkahub.ui.components.message.ReasoningCardState
import me.rerere.rikkahub.ui.components.message.shouldAnimateReasoningSummary
import me.rerere.rikkahub.ui.components.ui.IsolatedBackgroundRendering
import me.rerere.rikkahub.ui.components.ui.shouldDrawIndependentSurfaceBackground
import org.junit.Assert.*
import org.junit.Test

class RichSummarySurfaceTest {
    @Test fun `dark summary does not inherit black wallpaper text`() {
        listOf(.65f, .85f, 1f).forEach { opacity ->
            assertEquals(Color.White, summarySurfaceForeground(Color(0xFF26272C).copy(alpha = opacity), Color.Black, Color.Black, true))
        }
    }
    @Test fun `outlined summary preserves its visible parent text color`() {
        listOf(Color.Black, Color.White).forEach { foreground ->
            assertEquals(foreground, summarySurfaceForeground(Color.Transparent, Color.Gray, foreground, true))
        }
    }
    @Test fun `summary handles light and dark surfaces without wallpaper`() {
        assertEquals(Color.Black, summarySurfaceForeground(Color.White, Color.White, Color.Black, false))
        assertEquals(Color.White, summarySurfaceForeground(Color.Black, Color.Black, Color.Black, false))
    }
    @Test fun `flow stops on expansion and completion and honors performance switch`() {
        assertTrue(shouldAnimateReasoningSummary(true, ReasoningCardState.Collapsed, true))
        assertTrue(shouldAnimateReasoningSummary(true, ReasoningCardState.Preview, true))
        assertFalse(shouldAnimateReasoningSummary(true, ReasoningCardState.Expanded, true))
        ReasoningCardState.entries.forEach { state ->
            assertFalse(shouldAnimateReasoningSummary(false, state, true))
            assertFalse(shouldAnimateReasoningSummary(true, state, false))
        }
    }
    @Test fun `inline surfaces never fall back to independent wallpaper`() {
        assertFalse(shouldDrawIndependentSurfaceBackground(IsolatedBackgroundRendering.SHARED_PAGE))
        assertTrue(shouldDrawIndependentSurfaceBackground(IsolatedBackgroundRendering.INDEPENDENT))
    }
}
