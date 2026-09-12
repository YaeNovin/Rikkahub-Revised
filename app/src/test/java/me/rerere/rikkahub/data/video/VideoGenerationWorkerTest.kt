package me.rerere.rikkahub.data.video

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoGenerationWorkerTest {
    @Test
    fun `download file names are normalized and mime driven`() {
        assertEquals("unsafe_name.mp4", safeVideoFileName("../unsafe name.exe", "video/mp4"))
        assertEquals("clip.webm", safeVideoFileName("clip.mp4", "video/webm"))
        assertEquals("generated-video.mov", safeVideoFileName("...", "video/quicktime"))
    }
}
