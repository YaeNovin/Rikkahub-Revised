package me.rerere.rikkahub.ui.components.ui

import org.junit.Assert.assertThrows
import org.junit.Assert.*
import org.junit.Test

class BitmapComposerTest {
    @Test fun `long dense conversations are completely covered without overlaps or gaps`() {
        for (width in listOf(540,1080,1620,2000)) for (height in listOf(1,7407,14233,60000)) {
            val pages = exportImageSlices(width, height)
            assertEquals(0, pages.first().top)
            assertEquals(height, pages.sumOf { it.height })
            pages.zipWithNext().forEach { (a,b) -> assertEquals(a.top+a.height, b.top) }
            pages.forEach { requireExportBitmapSize(width, it.height) }
        }
    }
    @Test fun `dimensions cannot silently exceed the canvas bound`() {
        assertThrows(IllegalArgumentException::class.java) { requireExportBitmapSize(100,8193) }
    }
    @Test
    fun acceptsBitmapAtPixelLimit() {
        requireExportBitmapSize(width = 2_000, height = 4_000)
    }

    @Test
    fun rejectsBitmapAbovePixelLimit() {
        assertThrows(IllegalArgumentException::class.java) {
            requireExportBitmapSize(width = 2_001, height = 4_000)
        }
    }

    @Test
    fun rejectsEmptyBitmap() {
        assertThrows(IllegalArgumentException::class.java) {
            requireExportBitmapSize(width = 0, height = 1)
        }
    }
}
