package me.rerere.rikkahub.ui.components.ui

import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Test

class ImagePreviewDialogTest {
    @Test
    fun `small wide images get a useful preview canvas without losing aspect ratio`() {
        assertEquals(Size(1080f, 216f), previewCanvasSize(Size(100f, 20f)))
    }

    @Test
    fun `large images keep their intrinsic preview dimensions`() {
        assertEquals(Size(1600f, 900f), previewCanvasSize(Size(1600f, 900f)))
    }

    @Test
    fun `unknown intrinsic size uses a stable square canvas`() {
        assertEquals(Size(1080f, 1080f), previewCanvasSize(Size.Unspecified))
    }
}
