package me.rerere.ai.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioMimeTypeTest {
    @Test
    fun `normalizes common audio aliases to Gemini compatible MIME types`() {
        assertEquals("audio/mpeg", normalizeAudioMimeType("audio/mp3"))
        assertEquals("audio/mpeg", normalizeAudioMimeType("audio/x-mp3"))
        assertEquals("audio/mp4", normalizeAudioMimeType("audio/x-m4a"))
        assertEquals("audio/wav", normalizeAudioMimeType("audio/x-wav"))
        assertEquals("audio/ogg", normalizeAudioMimeType("application/ogg"))
    }

    @Test
    fun `rejects non audio MIME types`() {
        assertNull(normalizeAudioMimeType("image/png"))
    }
}
