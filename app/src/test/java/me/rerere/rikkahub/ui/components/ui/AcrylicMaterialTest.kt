package me.rerere.rikkahub.ui.components.ui

import androidx.compose.ui.unit.IntSize
import me.rerere.rikkahub.data.datastore.*
import me.rerere.rikkahub.data.model.*
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Test

class AcrylicMaterialTest {
    @Test fun `explicit material round trips and legacy options retain meaning`() {
        for (material in BackgroundSurfaceStyle.entries) {
            val settings = Settings(displaySetting = DisplaySetting(inputBlurRadius = 19f, inputSurfaceOpacity = .42f))
                .withChatComposerMaterial(material)
            val encoded = JsonInstant.encodeToString(AdvancedAppearanceSetting.serializer(), settings.advancedAppearanceSetting)
            val decoded = decodeAdvancedAppearanceSetting(encoded)
            assertEquals(material, settings.copy(advancedAppearanceSetting = decoded).chatComposerMaterial())
            assertEquals(19f, settings.displaySetting.inputBlurRadius)
            assertEquals(.42f, settings.displaySetting.inputSurfaceOpacity)
        }
        val old = decodeAdvancedAppearanceSetting("{}")
        assertNull(old.composerSurfaceStyle)
        assertEquals(BackgroundSurfaceStyle.FROSTED,
            Settings(advancedAppearanceSetting = old, displaySetting = DisplaySetting(enableBlurEffect = true)).chatComposerMaterial())
        assertTrue(old.enableOverlayPerformanceEffects)
    }

    @Test fun `adding replacing and removing wallpapers never resets surface materials`() {
        for (style in BackgroundSurfaceStyle.entries) {
            val appearance = AdvancedAppearanceSetting(pageSurfaceStyle = style, composerSurfaceStyle = style,
                overlaySurfaceStyle = style, overlaySurfaceOpacity = .43f)
            for (image in listOf("file:///first.png", "file:///second.png", null)) {
                val selected = appearance.selectGlobalBackground(image)
                assertEquals(style, selected.pageSurfaceStyle)
                assertEquals(style, selected.composerSurfaceStyle)
                assertEquals(style, selected.overlaySurfaceStyle)
                assertEquals(.43f, selected.overlaySurfaceOpacity)
            }
        }
    }

    @Test fun `legacy dock migration cannot overwrite an explicit solid composer selection`() {
        val display = DisplaySetting(inputSurfaceOpacity = .42f, inputBlurRadius = 19f)
        val appearance = AdvancedAppearanceSetting(composerSurfaceStyle = BackgroundSurfaceStyle.OPAQUE,
            enableChatDockGlass = true, chatDockGlassOpacity = .8f, chatDockGlassBlurRadius = 12f)
        assertEquals(display, me.rerere.rikkahub.data.datastore.migration.migrateComposerDisplay(display, appearance))
    }

    @Test fun `source capabilities and effects change only the render plan`() {
        val normal = resolveMaterialSurface(BackgroundSurfaceStyle.ACRYLIC, .6f, 20f, true, true, true, 24f)
        assertEquals(MaterialSurfacePlan(.6f, 20f, true), normal)
        assertEquals(MaterialSurfacePlan(.6f, 0f, true), resolveMaterialSurface(BackgroundSurfaceStyle.ACRYLIC, .6f, 20f, true, true, false, 24f))
        assertEquals(MaterialSurfacePlan(.6f, 0f, true), resolveMaterialSurface(BackgroundSurfaceStyle.ACRYLIC, .6f, 20f, true, false, true, 24f))
        assertEquals(MaterialSurfacePlan(.6f, 0f, false), resolveMaterialSurface(BackgroundSurfaceStyle.ACRYLIC, .6f, 20f, false, true, true, 24f))
        assertEquals(MaterialSurfacePlan(.6f, 0f, false), resolveMaterialSurface(BackgroundSurfaceStyle.ACRYLIC, .6f, 20f, true, true, true, 24f, powerSave = true))
        assertEquals(MaterialSurfacePlan(1f, 0f, false), resolveMaterialSurface(BackgroundSurfaceStyle.OPAQUE, .1f, 20f, true, true, true, 24f))
    }

    @Test fun `zero opacity adds neither hidden tint nor visible grain`() {
        assertEquals(MaterialSurfacePlan(0f, 12f, false), resolveMaterialSurface(BackgroundSurfaceStyle.ACRYLIC, 0f, 40f, true, true, true, 12f))
        assertEquals(0f, resolveMaterialSurface(BackgroundSurfaceStyle.ACRYLIC, .6f, 40f, true, true, true, Float.NaN).blurRadius)
    }

    @Test fun `snapshot memory is bounded and aspect ratio preserved`() {
        assertEquals(IntSize.Zero, acrylicSnapshotSize(0, 2400))
        assertEquals(IntSize(576, 1280), acrylicSnapshotSize(1260, 2800))
        assertEquals(IntSize(1280, 576), acrylicSnapshotSize(2800, 1260))
        assertEquals(IntSize(400, 700), acrylicSnapshotSize(400, 700))
    }

    @Test fun `acrylic remains selectable on old Android and after wallpaper removal`() {
        assertTrue(advancedAppearanceCapabilities(26).supportsSurfaceStyle(BackgroundSurfaceStyle.ACRYLIC))
        val original = Settings().withChatComposerMaterial(BackgroundSurfaceStyle.ACRYLIC)
        val changed = original.copy(advancedAppearanceSetting = original.advancedAppearanceSetting.copy(
            globalBackground = null, enableGlobalBackground = false, enableInputPerformanceEffects = false))
        assertEquals(BackgroundSurfaceStyle.ACRYLIC, changed.chatComposerMaterial())
        assertEquals(original.displaySetting, changed.displaySetting)
    }
}
