package me.rerere.rikkahub.data.model

import me.rerere.rikkahub.data.datastore.*
import org.junit.Assert.*
import org.junit.Test

class BackgroundSelectionTest {
    @Test fun `selecting image makes it visible without losing gradient configuration`() {
        val result = Assistant(useGradientBackground = true, backgroundOpacity = 0f, gradientBackgroundSpeed = 2f).selectBackground(" file:///image.jpg ")
        assertFalse(result.useGradientBackground)
        assertEquals(1f, result.backgroundOpacity)
        assertEquals(2f, result.gradientBackgroundSpeed)
        assertEquals("file:///image.jpg", result.background)
    }
    @Test fun `reenabling transparent gradient restores visibility`() {
        assertEquals(1f, Assistant(backgroundOpacity = 0f).selectGradientBackground(true).backgroundOpacity)
        assertEquals(.5f, Assistant(backgroundOpacity = .5f).selectGradientBackground(true).backgroundOpacity)
    }
    @Test fun `global image selection recovers opaque page but never opts into chat override`() {
        val result = AdvancedAppearanceSetting(pageSurfaceStyle = BackgroundSurfaceStyle.OPAQUE, globalBackgroundOpacity = Float.NaN).selectGlobalBackground("file:///image.jpg")
        assertTrue(result.enableGlobalBackground)
        assertFalse(result.applyGlobalBackgroundToChat)
        assertEquals(BackgroundSurfaceStyle.TRANSLUCENT, result.pageSurfaceStyle)
        assertEquals(1f, result.globalBackgroundOpacity)
    }
    @Test fun `clearing global image restores assistant background selection`() {
        val appearance = AdvancedAppearanceSetting(enableGlobalBackground = true, globalBackground = "file:///image.jpg", applyGlobalBackgroundToChat = true)
        assertFalse(appearance.selectGlobalBackground(null).applyGlobalBackgroundToChat)
        assertTrue(appearance.selectGlobalBackground("file:///new.jpg").applyGlobalBackgroundToChat)
    }
    @Test fun `image urls reject invalid schemes`() {
        assertTrue(me.rerere.rikkahub.ui.pages.assistant.detail.isBackgroundImageUrlValid(" https://example.com/a.png "))
        assertFalse(me.rerere.rikkahub.ui.pages.assistant.detail.isBackgroundImageUrlValid("javascript:alert(1)"))
        assertFalse(me.rerere.rikkahub.ui.pages.assistant.detail.isBackgroundImageUrlValid("https://"))
    }
}
