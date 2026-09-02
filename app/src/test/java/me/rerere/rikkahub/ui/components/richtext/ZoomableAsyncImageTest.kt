package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Test

class ZoomableAsyncImageTest {
    @Test
    fun `small markdown badge keeps its css pixel size`() {
        assertEquals(Size(88f, 20f), inlineImageDisplaySizeDp(Size(88f, 20f)))
    }

    @Test
    fun `wide markdown image is reduced without changing its aspect ratio`() {
        assertEquals(Size(360f, 72f), inlineImageDisplaySizeDp(Size(500f, 100f)))
    }

    @Test
    fun `portrait markdown image respects both preview bounds`() {
        assertEquals(Size(140f, 280f), inlineImageDisplaySizeDp(Size(500f, 1000f)))
    }

    @Test
    fun `unknown image dimensions defer to the painter`() {
        assertEquals(Size.Unspecified, inlineImageDisplaySizeDp(Size.Unspecified))
    }
}
