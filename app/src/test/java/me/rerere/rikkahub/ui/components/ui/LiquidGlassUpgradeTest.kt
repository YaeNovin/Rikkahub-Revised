package me.rerere.rikkahub.ui.components.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import me.rerere.rikkahub.data.model.*
import me.rerere.rikkahub.data.datastore.AdvancedAppearanceSetting
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Test

class LiquidGlassUpgradeTest {
    @Test fun `presets are distinct and custom edits start from visible preset`() {
        val soft = LiquidGlassSettings(preset = LiquidGlassPreset.SOFT)
        val standard = LiquidGlassSettings()
        val clear = LiquidGlassSettings(preset = LiquidGlassPreset.CLEAR)
        assertTrue(soft.parameters().highlight < standard.parameters().highlight)
        assertTrue(clear.parameters().tint < standard.parameters().tint)
        val edited = soft.edit { it.copy(edgeWidth = 3f) }
        assertEquals(LiquidGlassPreset.CUSTOM, edited.preset)
        assertEquals(soft.parameters().highlight, edited.parameters().highlight)
        assertEquals(3f, edited.parameters().edgeWidth)
    }
    @Test fun `old settings load safely and new parameters round trip`() {
        val old = JsonInstant.decodeFromString(AdvancedAppearanceSetting.serializer(), "{}")
        assertEquals(LiquidGlassPreset.STANDARD, old.liquidGlass.preset)
        assertFalse(old.liquidGlass.applyToComposer)
        val settings = old.copy(liquidGlass = LiquidGlassSettings(renderer = LiquidGlassRenderer.AGSL, applyToComposer = true).edit { it.copy(refraction = 12f) })
        assertEquals(settings, JsonInstant.decodeFromString(AdvancedAppearanceSetting.serializer(), JsonInstant.encodeToString(AdvancedAppearanceSetting.serializer(), settings)))
    }
    @Test fun `invalid parameters cannot reach graphics APIs`() {
        val params = LiquidGlassParameters(highlight = Float.NaN, tint = 10f, edgeWidth = -5f, refraction = Float.POSITIVE_INFINITY, innerShadow = -1f).normalized()
        assertTrue(params.highlight.isFinite())
        assertEquals(.2f, params.tint)
        assertEquals(.5f, params.edgeWidth)
        assertEquals(6f, params.refraction)
        assertEquals(0f, params.innerShadow)
    }
    private fun profile(sdk: Int = 33, busy: Boolean = false, power: Boolean = false, area: Float = 10000f, slot: Int = 0, failed: Boolean = false, options: LiquidGlassSettings = LiquidGlassSettings()) =
        resolveGlassProfile(options, sdk, failed, busy, power, area, slot, advancedAppearanceCapabilities(sdk).maxLiveBlurRadius)

    @Test fun `unsupported SDKs and runtime failures always fall back`() {
        assertFalse(profile(sdk = 32).refractionEnabled)
        assertFalse(profile(sdk = 26).refractionEnabled)
        assertEquals(0f, profile(sdk = 26).blurLimit)
        assertTrue(profile().refractionEnabled)
        assertFalse(profile(failed = true).refractionEnabled)
        assertFalse(profile(options = LiquidGlassSettings(renderer = LiquidGlassRenderer.STANDARD)).refractionEnabled)
    }
    @Test fun `large and excess surfaces skip refraction`() {
        assertFalse(profile(area = MAX_REFRACTION_AREA_DP + 1f).refractionEnabled)
        assertFalse(profile(slot = MAX_REFRACTION_SURFACES).refractionEnabled)
        assertFalse(profile(slot = -1).refractionEnabled)
        assertFalse(profile(area = 0f).refractionEnabled)
    }
    @Test fun `typing and power saving reduce work without changing saved preferences`() {
        assertFalse(profile(busy = true).refractionEnabled)
        assertEquals(8f, profile(busy = true).blurLimit)
        assertEquals(4f, profile(power = true).blurLimit)
        assertTrue(profile(power = true, options = LiquidGlassSettings(adaptivePerformance = false)).refractionEnabled)
    }
    @Test fun `popup sampling remains aligned in screen coordinates`() {
        assertEquals(Offset(-40f, -100f), glassSampleTranslation(Offset(0f, 24f), Offset(40f, 124f)))
        assertEquals(Offset(0f, 0f), glassSampleTranslation(Offset(0f, 24f), Offset(0f, 24f)))
    }
    @Test fun `offscreen surfaces do not consume refraction budget`() {
        val viewport = IntSize(400, 800)
        assertTrue(glassSurfaceVisible(Offset(20f, 24f), IntSize(100, 100), Offset.Zero, viewport))
        assertFalse(glassSurfaceVisible(Offset(0f, 900f), IntSize(100, 100), Offset.Zero, viewport))
        assertFalse(glassSurfaceVisible(Offset(Float.NaN, 0f), IntSize(100, 100), Offset.Zero, viewport))
    }
    @Test fun `scaled popup maps local pixels to the same source background`() {
        val scale = glassScreenScale(Offset(20f, 40f), Offset(120f, 40f), Offset(20f, 90f), IntSize(200, 100))
        assertEquals(Offset(.5f, .5f), scale)
        val transform = glassSamplingTransform(Offset.Zero, Offset(1f, 1f), Offset(20f, 40f), scale)
        assertEquals(Offset(-40f, -80f), transform.translation)
        assertEquals(Offset(2f, 2f), transform.scale)
    }
    @Test fun `standard surfaces with zero blur never steal advanced rendering slots`() {
        assertFalse(needsGlassRenderSlot(true, 0f, 33, LiquidGlassRenderer.STANDARD, 6f, 10000f, false))
        assertFalse(needsGlassRenderSlot(true, 0f, 33, LiquidGlassRenderer.AUTO, 0f, 10000f, false))
        assertFalse(needsGlassRenderSlot(true, 0f, 33, LiquidGlassRenderer.AUTO, 6f, MAX_REFRACTION_AREA_DP + 1f, false))
        assertTrue(needsGlassRenderSlot(true, 8f, 31, LiquidGlassRenderer.STANDARD, 6f, 10000f, false))
        assertTrue(needsGlassRenderSlot(true, 0f, 33, LiquidGlassRenderer.AUTO, 6f, 10000f, false))
    }
}
