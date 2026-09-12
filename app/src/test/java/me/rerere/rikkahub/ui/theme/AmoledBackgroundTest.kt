package me.rerere.rikkahub.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class AmoledBackgroundTest {
    @Test
    fun `amoled mode defaults to softer dark background`() {
        assertEquals(Color(0xFF121316), resolveAmoledBackground(pureBlack = false))
    }

    @Test
    fun `amoled mode can restore original pure black background`() {
        assertEquals(Color.Black, resolveAmoledBackground(pureBlack = true))
    }
}
