package me.rerere.rikkahub.ui.pages.setting

import me.rerere.rikkahub.data.datastore.*
import me.rerere.rikkahub.data.model.*
import me.rerere.rikkahub.ui.components.ui.*
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Test

class FormalMaterialPolicyTest {
    @Test fun `only glass family enables blur and dormant parameters survive`() {
        for (material in BackgroundSurfaceStyle.entries) {
            val glass = material in listOf(BackgroundSurfaceStyle.FROSTED, BackgroundSurfaceStyle.LIQUID_GLASS, BackgroundSurfaceStyle.ACRYLIC)
            assertEquals(glass, canAdjustMaterialBlur(material, true))
            assertFalse(canAdjustMaterialBlur(material, false))
            assertFalse(canAdjustMaterialBlur(material, true, effectsEnabled = false))
            assertFalse(canAdjustMaterialBlur(material, true, sourceAvailable = false))
            val rendered = resolveMaterialSurface(material, .6f, 17f, true, true, true, 24f)
            assertEquals(if (glass) 17f else 0f, rendered.blurRadius)
            assertEquals(if (material.opaqueMaterial) 1f else .6f, rendered.opacity)
        }
    }

    @Test fun `recommended appearance persists materials without resetting user parameters`() {
        val original = Settings(displaySetting = DisplaySetting(inputBlurRadius = 13f, inputSurfaceOpacity = .44f),
            advancedAppearanceSetting = AdvancedAppearanceSetting(globalBackground = "file:///saved.png",
                enableInputPerformanceEffects = false, overlaySurfaceOpacity = .48f, overlaySurfaceBlurRadius = 27f))
        val updated = original.withMicaAcrylicAppearance()
        val appearance = updated.advancedAppearanceSetting
        assertEquals(BackgroundSurfaceStyle.MICA, appearance.pageSurfaceStyle)
        assertEquals(BackgroundSurfaceStyle.MICA, appearance.navigationSurfaceStyle)
        assertEquals(BackgroundSurfaceStyle.ACRYLIC, updated.chatComposerMaterial())
        assertEquals(BackgroundSurfaceStyle.ACRYLIC, appearance.overlaySurfaceStyle)
        assertEquals(13f, updated.displaySetting.inputBlurRadius)
        assertEquals(.44f, updated.displaySetting.inputSurfaceOpacity)
        assertFalse(appearance.enableInputPerformanceEffects)
        assertEquals(.48f, appearance.overlaySurfaceOpacity)
        assertEquals(27f, appearance.overlaySurfaceBlurRadius)
        assertEquals(original.advancedAppearanceSetting.globalBackground, appearance.globalBackground)
        assertEquals(appearance, decodeAdvancedAppearanceSetting(JsonInstant.encodeToString(AdvancedAppearanceSetting.serializer(), appearance)))
    }

    @Test fun `mica works without an image and never enables top bar blur`() {
        val settings = Settings().withMicaAcrylicAppearance()
        assertFalse(settings.hasActiveChatBackground())
        assertTrue(advancedAppearanceCapabilities(26).supportsSurfaceStyle(BackgroundSurfaceStyle.MICA))
        assertTrue(canAdjustCardOpacity(settings, advancedAppearanceCapabilities(33)))
        assertFalse(shouldApplyTopBarBlur(true, true, true, true, true, BackgroundSurfaceStyle.MICA))
        assertFalse(shouldApplyTopBarBlur(true, true, true, true, true, BackgroundSurfaceStyle.TRANSLUCENT))
    }

    @Test fun `assistant wallpaper blur follows page material without deleting its radius`() {
        val assistant = Assistant(background = "file:///saved.png", backgroundBlurRadius = 23f)
        val original = Settings(assistants = listOf(assistant), assistantId = assistant.id)
        for (material in BackgroundSurfaceStyle.entries) {
            val settings = original.copy(advancedAppearanceSetting = original.advancedAppearanceSetting.copy(pageSurfaceStyle = material))
            assertEquals(if (material.usesBlur) 23f else 0f, settings.resolveChatBackground().blurRadius)
            assertEquals(23f, settings.getCurrentAssistant().backgroundBlurRadius)
        }
    }
}
