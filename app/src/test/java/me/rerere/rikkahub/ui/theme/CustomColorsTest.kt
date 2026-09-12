package me.rerere.rikkahub.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class CustomColorsTest {
    @Test
    fun `scaffold stays transparent over an active background`() {
        assertEquals(
            Color.Transparent,
            resolveScaffoldContainerColor(
                backgroundActive = true,
                defaultColor = Color.Red,
            )
        )
    }

    @Test
    fun `scaffold keeps its original color without a background`() {
        assertEquals(
            Color.Red,
            resolveScaffoldContainerColor(
                backgroundActive = false,
                defaultColor = Color.Red,
            )
        )
    }
}
