package me.rerere.rikkahub.ui.components.ui

import me.rerere.rikkahub.data.datastore.BackgroundSurfaceStyle
import me.rerere.rikkahub.data.datastore.ChatBubbleStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvancedAppearanceCompatibilityTest {
    @Test
    fun `android 11 disables realtime blur and uses stable fallbacks`() {
        val capabilities = advancedAppearanceCapabilities(30)

        assertEquals(AdvancedAppearanceSupport.UNSUPPORTED, capabilities.blurSupport)
        assertFalse(capabilities.supportsRealtimeBlur)
        assertFalse(capabilities.supportsSurfaceStyle(BackgroundSurfaceStyle.FROSTED))
        assertFalse(capabilities.supportsSurfaceStyle(BackgroundSurfaceStyle.LIQUID_GLASS))
        assertTrue(capabilities.supportsSurfaceStyle(BackgroundSurfaceStyle.TRANSLUCENT))
        assertEquals(
            BackgroundSurfaceStyle.TRANSLUCENT,
            capabilities.effectiveSurfaceStyle(BackgroundSurfaceStyle.LIQUID_GLASS),
        )
        assertEquals(
            ChatBubbleStyle.OUTLINED,
            capabilities.effectiveBubbleStyle(ChatBubbleStyle.FROSTED),
        )
        assertEquals(0f, capabilities.limitLiveBlur(24f))
    }

    @Test
    fun `android 12 reduces blur and optical strength`() {
        val capabilities = advancedAppearanceCapabilities(31)

        assertEquals(AdvancedAppearanceSupport.REDUCED, capabilities.blurSupport)
        assertTrue(capabilities.supportsRealtimeBlur)
        assertTrue(capabilities.usesReducedEffects)
        assertTrue(capabilities.supportsSurfaceStyle(BackgroundSurfaceStyle.LIQUID_GLASS))
        assertTrue(capabilities.supportsBubbleStyle(ChatBubbleStyle.FROSTED))
        assertEquals(20f, capabilities.limitBackgroundBlur(40f))
        assertEquals(12f, capabilities.limitLiveBlur(40f))
        assertEquals(0.55f, capabilities.limitOpticalStrength(1f))
    }

    @Test
    fun `android 13 and newer preserve full effects`() {
        val capabilities = advancedAppearanceCapabilities(33)

        assertEquals(AdvancedAppearanceSupport.FULL, capabilities.blurSupport)
        assertTrue(capabilities.supportsRealtimeBlur)
        assertFalse(capabilities.usesReducedEffects)
        assertEquals(40f, capabilities.limitBackgroundBlur(40f))
        assertEquals(40f, capabilities.limitLiveBlur(40f))
        assertEquals(1f, capabilities.limitOpticalStrength(1f))
        assertEquals(
            BackgroundSurfaceStyle.LIQUID_GLASS,
            capabilities.effectiveSurfaceStyle(BackgroundSurfaceStyle.LIQUID_GLASS),
        )
        assertEquals(
            ChatBubbleStyle.LIQUID_GLASS,
            capabilities.effectiveBubbleStyle(ChatBubbleStyle.LIQUID_GLASS),
        )
    }
}
