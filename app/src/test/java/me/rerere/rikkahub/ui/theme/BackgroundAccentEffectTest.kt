package me.rerere.rikkahub.ui.theme

import me.rerere.rikkahub.data.datastore.AdvancedAppearanceSetting
import org.junit.Assert.*
import org.junit.Test

class BackgroundAccentEffectTest {
    private val settings = AdvancedAppearanceSetting(enableGlobalBackground = true, enableAutoAccent = true, globalBackground = "file:///current.jpg")

    @Test fun `stale extraction never overwrites new image or stored accent`() {
        assertEquals(settings, settings.withExtractedBackgroundAccent("file:///old.jpg", 123L))
        val stored = settings.copy(autoAccentColorArgb = 456L)
        assertEquals(stored, stored.withExtractedBackgroundAccent("file:///current.jpg", 123L))
        assertEquals(123L, settings.withExtractedBackgroundAccent("file:///current.jpg", 123L).autoAccentColorArgb)
    }
    @Test fun `switching off background or extraction discards in flight result`() {
        val inactive = settings.copy(enableGlobalBackground = false)
        val disabled = settings.copy(enableAutoAccent = false)
        assertEquals(inactive, inactive.withExtractedBackgroundAccent("file:///current.jpg", 123L))
        assertEquals(disabled, disabled.withExtractedBackgroundAccent("file:///current.jpg", 123L))
    }
}
