package me.rerere.rikkahub.data.datastore

import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Test

class TextColorModeSettingsTest {
    @Test fun `existing installations keep automatic text and all saved background settings`() {
        val previous = """{"enableGlobalBackground":true,"globalBackground":"file:///wallpaper.jpg","globalBackgroundOpacity":0.7,"pageSurfaceOpacity":0.5,"enableAutoAccent":true}"""
        val restored = decodeAdvancedAppearanceSetting(previous)
        assertEquals(TextColorMode.AUTO_CLEAR, restored.textColorMode)
        assertEquals("file:///wallpaper.jpg", restored.globalBackground)
        assertTrue(restored.enableGlobalBackground)
        assertTrue(restored.enableAutoAccent)
        assertEquals(.7f, restored.globalBackgroundOpacity, 0f)
        assertEquals(.5f, restored.pageSurfaceOpacity, 0f)
    }

    @Test fun `each mode persists independently of accent and glass settings`() {
        val original = AdvancedAppearanceSetting(
            enableAutoAccent = true, globalBackground = "file:///background.jpg",
            pageSurfaceOpacity = .51f, globalBackgroundBlurRadius = 14f,
            overlaySurfaceStyle = BackgroundSurfaceStyle.LIQUID_GLASS,
        )
        for (mode in TextColorMode.entries) {
            val updated = original.copy(textColorMode = mode)
            val restored = decodeAdvancedAppearanceSetting(JsonInstant.encodeToString(AdvancedAppearanceSetting.serializer(), updated))
            assertEquals(updated, restored)
            assertEquals(original, restored.copy(textColorMode = TextColorMode.AUTO_CLEAR))
        }
    }
}
