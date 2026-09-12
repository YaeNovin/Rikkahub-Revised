package me.rerere.material3

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HctColorTest {
    @Test
    fun `ARGB conversion preserves the source ARGB`() {
        val hct = hctColorFromArgb(0x806750A4.toInt())

        assertEquals(0x806750A4.toInt(), hct.argb)
        assertTrue(hct.hue in 0.0..360.0)
        assertTrue(hct.chroma >= 0.0)
        assertTrue(hct.tone in 0.0..100.0)
    }

    @Test
    fun `component conversion clamps public picker ranges`() {
        val hct = hctColorFromComponents(
            hue = 500.0,
            chroma = 200.0,
            tone = -20.0,
        )

        assertTrue(hct.hue in 0.0..360.0)
        assertTrue(hct.chroma in 0.0..150.0)
        assertEquals(0.0, hct.tone, 0.01)
        assertEquals(0xFF000000.toInt(), hct.argb)
    }
}
