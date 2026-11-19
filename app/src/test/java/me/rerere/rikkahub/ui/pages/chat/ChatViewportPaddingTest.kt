package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatViewportPaddingTest {
    @Test fun `IME values are read during layout and stay current without reconstructing padding`() {
        var reads = 0
        var bottom = 84.dp
        var header = 70.dp
        val scaffold = object : PaddingValues {
            override fun calculateLeftPadding(layoutDirection: LayoutDirection) = 3.dp.also { reads++ }
            override fun calculateRightPadding(layoutDirection: LayoutDirection) = 7.dp.also { reads++ }
            override fun calculateTopPadding() = 10.dp.also { reads++ }
            override fun calculateBottomPadding() = bottom.also { reads++ }
        }
        val page = chatPagePadding(scaffold) { header }
        val overlay = chatOverlayPadding(page, true)
        val messages = chatMessagePadding(overlay)
        val footer = chatComposerPadding(page)
        val withSuggestions = chatMessagePadding(chatOverlayPadding(page, false))
        assertEquals(0, reads)
        assertEquals(132.dp, messages.calculateBottomPadding())
        bottom = 380.dp
        header = 92.dp
        assertEquals(428.dp, messages.calculateBottomPadding())
        assertEquals(380.dp, footer.calculateBottomPadding())
        assertEquals(108.dp, messages.calculateTopPadding())
        assertEquals(48.dp, withSuggestions.calculateBottomPadding())
        assertEquals(3.dp, overlay.calculateLeftPadding(LayoutDirection.Rtl))
        assertEquals(7.dp, overlay.calculateRightPadding(LayoutDirection.Rtl))
        assertEquals(0.dp, footer.calculateTopPadding())
    }
}
