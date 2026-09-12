package me.rerere.rikkahub.ui.pages.setting

import me.rerere.rikkahub.data.datastore.*
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.ui.components.ui.*
import org.junit.Assert.*
import org.junit.Test

class AppearanceSettingsPolicyTest {
    private val capabilities = advancedAppearanceCapabilities(33)

    @Test fun `assistant gradient allows shared card opacity without global background`() {
        val assistant = Assistant(useGradientBackground = true)
        val settings = Settings(assistants = listOf(assistant), assistantId = assistant.id)
        assertTrue(canAdjustCardOpacity(settings, capabilities))
        assertTrue(canAdjustOverlayEffects(settings))
    }
    @Test fun `hidden global image does not enable card slider but can back isolated dialogs`() {
        val settings = Settings(advancedAppearanceSetting = AdvancedAppearanceSetting(enableGlobalBackground = true,
            globalBackground = "file:///image.jpg", pageSurfaceStyle = BackgroundSurfaceStyle.OPAQUE))
        assertFalse(canAdjustCardOpacity(settings, capabilities))
        assertTrue(canAdjustOverlayEffects(settings))
    }
    @Test fun `missing backgrounds disable ineffectual controls`() {
        assertFalse(canAdjustCardOpacity(Settings(), capabilities))
        assertFalse(canAdjustOverlayEffects(Settings()))
    }
    @Test fun `late acknowledgment cannot overwrite a more recent slider commit`() {
        assertFalse(shouldSyncAppearanceSlider(false, .8f, .6f, true))
        assertTrue(shouldSyncAppearanceSlider(false, .8f, .8f, true))
        assertFalse(shouldSyncAppearanceSlider(true, null, .6f, true))
    }
    @Test fun `disabled control or save failure discards pending edits`() {
        assertTrue(shouldSyncAppearanceSlider(true, .8f, .6f, false))
        assertTrue(shouldSyncAppearanceSlider(false, .8f, .6f, true, saveFailed = true))
    }
    @Test fun `runtime failure falls back even on newer Android while preference remains available`() {
        assertEquals(GradientRendererBackend.KOTLIN, resolveGradientRendererBackend(GradientRendererMode.AGSL, 37, runtimeFailed = true))
        assertEquals(GradientRendererBackend.KOTLIN, resolveGradientRendererBackend(GradientRendererMode.AUTO, 33, runtimeFailed = true))
        assertEquals(GradientRendererBackend.AGSL, resolveGradientRendererBackend(GradientRendererMode.AUTO, 33))
    }
}
