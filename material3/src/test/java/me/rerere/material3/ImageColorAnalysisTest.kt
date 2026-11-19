package me.rerere.material3

import org.junit.Assert.*
import org.junit.Test

class ImageColorAnalysisTest {
    @Test fun `a dominant family survives being split into many small shades`() {
        val blueShades = (1..80).associate { index ->
            hctColorFromComponents(230.0 + index % 8, 18.0 + index % 5, 40.0 + index * .5).argb to 10
        }
        val palette = blueShades + (0xFFBB99CC.toInt() to 120) + (0xFFEEDDDD.toInt() to 80)
        val selected = hctColorFromArgb(representativeThemeColor(palette)!!)
        assertTrue("hue=${selected.hue}", selected.hue in 220.0..250.0)
    }
    @Test fun `representative accent ignores tiny saturated specks and unsuitable dark clusters`() {
        val pastel = 0xFF668A9C.toInt()
        assertEquals(pastel, representativeThemeColor(mapOf(0xFF030008.toInt() to 800,
            pastel to 199, 0xFFFF0000.toInt() to 1)))
        assertNull(representativeThemeColor(mapOf(0x00FF0000 to 100, pastel to 0)))
    }
    @Test fun `transparent pixels cannot dominate the seed and population remains accurate`() {
        val blue = 0xFF3269BB.toInt()
        val pixels = IntArray(1000) { if (it < 800) 0x00FF0000 else blue }
        val result = analyzeImageColors(pixels)
        assertEquals(blue, result.seedArgb)
        assertEquals(200, result.opaquePixelCount)
        assertEquals(1, result.colorCount)
    }

    @Test fun `achromatic and empty pictures do not acquire an unrelated blue fallback`() {
        assertNull(analyzeImageColors(intArrayOf()).seedArgb)
        val gray = 0xFF777777.toInt()
        assertEquals(gray, analyzeImageColors(IntArray(500) { gray }).seedArgb)
    }

    @Test fun `image analysis is deterministic and selects a chromatic source from a mixed image`() {
        val image = IntArray(4096) { when (it % 10) {
            in 0..5 -> 0xFF668A9C.toInt()
            in 6..8 -> 0xFFB997A7.toInt()
            else -> 0xFF283249.toInt()
        } }
        val first = analyzeImageColors(image)
        assertEquals(first, analyzeImageColors(image))
        assertTrue(hctColorFromArgb(first.seedArgb!!).chroma > 8)
        assertTrue(first.colorCount in 1..128)
    }
}
