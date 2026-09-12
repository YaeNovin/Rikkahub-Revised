package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.ui.graphics.Color
import org.junit.Assert.*
import org.junit.Test

class ChatSuggestionSurfaceTest {
    @Test fun `outlined suggestions over wallpaper use configured translucency rather than opaque fallback`() {
        val surface = Color(0xFF121316)
        val result = suggestionContainerColor(Color.Transparent, surface, true, true, .62f)
        assertEquals(surface.copy(alpha = .62f), result)
        assertTrue(result.alpha < 1f)
    }
    @Test fun `existing rich surface opacity is reused exactly`() {
        val rich = Color(0xFFE4E3EE).copy(alpha = .35f)
        assertEquals(rich, suggestionContainerColor(rich, Color.White, true, true, .7f))
    }
    @Test fun `disabled effects and absent background use a normal theme surface`() {
        for ((background, effects) in listOf(false to true, true to false, false to false)) {
            assertEquals(Color.Black, suggestionContainerColor(Color.Transparent, Color.Black.copy(alpha = .2f), background, effects, .6f))
        }
    }
    @Test fun `invalid imported opacity cannot produce invisible or invalid drawing colors`() {
        for (value in listOf(Float.NaN, Float.POSITIVE_INFINITY, -1f, 8f)) {
            val color = suggestionContainerColor(Color.Transparent, Color.White, true, true, value)
            assertTrue(color.alpha in .19f.. .91f)
        }
    }
}
