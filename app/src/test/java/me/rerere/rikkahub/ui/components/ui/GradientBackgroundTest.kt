package me.rerere.rikkahub.ui.components.ui

import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import me.rerere.rikkahub.data.datastore.GradientRendererMode
import me.rerere.rikkahub.data.model.GradientBackgroundPreset
import me.rerere.rikkahub.data.model.GradientBackgroundCustomColors
import me.rerere.rikkahub.ui.theme.createGradientBackgroundPalette
import me.rerere.rikkahub.ui.theme.updateGradientCustomColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GradientBackgroundTest {
    @Test
    fun `auto renderer uses AGSL only on Android 13 and newer`() {
        assertEquals(
            GradientRendererBackend.KOTLIN,
            resolveGradientRendererBackend(GradientRendererMode.AUTO, sdkInt = 32),
        )
        assertEquals(
            GradientRendererBackend.AGSL,
            resolveGradientRendererBackend(GradientRendererMode.AUTO, sdkInt = 33),
        )
        assertEquals(
            GradientRendererBackend.AGSL,
            resolveGradientRendererBackend(GradientRendererMode.AUTO, sdkInt = 37),
        )
    }

    @Test
    fun `unsupported explicit AGSL request falls back to Kotlin`() {
        assertEquals(
            GradientRendererBackend.KOTLIN,
            resolveGradientRendererBackend(GradientRendererMode.AGSL, sdkInt = 32),
        )
        assertEquals(
            GradientRendererBackend.KOTLIN,
            resolveGradientRendererBackend(GradientRendererMode.KOTLIN, sdkInt = 37),
        )
    }

    @Test
    fun `legacy palette remains the default`() {
        val palette = createGradientBackgroundPalette(
            colorScheme = lightColorScheme(),
            dark = false,
            followTheme = false,
        )

        assertEquals(Color(0xFFAFD0F2), palette.baseStops.first().second)
        assertEquals(Color(0xFF9EC5F0), palette.blobs.first().color)
    }

    @Test
    fun `theme palette uses current material roles`() {
        val scheme = lightColorScheme(
            primary = Color(0xFF123456),
            secondary = Color(0xFF337755),
            tertiary = Color(0xFF884466),
        )
        val palette = createGradientBackgroundPalette(
            colorScheme = scheme,
            dark = false,
            followTheme = true,
        )

        assertEquals(scheme.primary, palette.blobs[0].color)
        assertEquals(scheme.secondary, palette.blobs[1].color)
        assertEquals(scheme.tertiary, palette.blobs[2].color)
    }

    @Test
    fun `custom colors replace every editable gradient color`() {
        val custom = GradientBackgroundCustomColors(
            baseColors = listOf(
                0xFF101112,
                0xFF202122,
                0xFF303132,
                0xFF404142,
                0xFF505152,
            ),
            blobColors = listOf(
                0xFFAA1122,
                0xFF22AA33,
                0xFF3344AA,
                0xFFAA44AA,
            ),
        )
        val palette = createGradientBackgroundPalette(
            colorScheme = lightColorScheme(),
            dark = false,
            followTheme = false,
            customColors = custom,
        )

        assertEquals(custom.baseColors.map { Color(it.toInt()) }, palette.baseStops.map { it.second })
        assertEquals(custom.blobColors.map { Color(it.toInt()) }, palette.blobs.map { it.color })
        assertTrue(palette.blobs.first().alpha > 0.72f)
    }

    @Test
    fun `editing one base color preserves existing values and leaves blobs untouched`() {
        val scheme = lightColorScheme()
        val defaultPalette = createGradientBackgroundPalette(
            colorScheme = scheme,
            dark = false,
            followTheme = false,
        )
        val existing = GradientBackgroundCustomColors(
            baseColors = listOf(0xFF010203),
            blobColors = listOf(0xFF112233),
        )

        val updated = updateGradientCustomColor(
            customColors = existing,
            defaultPalette = defaultPalette,
            editingBlob = false,
            selectedIndex = 2,
            color = Color(0xFFABCDEF),
        )

        assertEquals(5, updated.baseColors.size)
        assertEquals(0xFF010203, updated.baseColors[0])
        assertEquals(0xFFABCDEF, updated.baseColors[2])
        assertEquals(existing.blobColors, updated.blobColors)
    }

    @Test
    fun `editing one blob color initializes only the blob palette`() {
        val defaultPalette = createGradientBackgroundPalette(
            colorScheme = lightColorScheme(),
            dark = false,
            followTheme = false,
        )

        val updated = updateGradientCustomColor(
            customColors = GradientBackgroundCustomColors(),
            defaultPalette = defaultPalette,
            editingBlob = true,
            selectedIndex = 3,
            color = Color(0xFF445566),
        )

        assertTrue(updated.baseColors.isEmpty())
        assertEquals(4, updated.blobColors.size)
        assertEquals(0xFF445566, updated.blobColors[3])
    }

    @Test
    fun `successive edits keep the last color and stable palette sizes`() {
        val defaultPalette = createGradientBackgroundPalette(
            colorScheme = lightColorScheme(),
            dark = false,
            followTheme = false,
        )
        val first = updateGradientCustomColor(
            customColors = GradientBackgroundCustomColors(),
            defaultPalette = defaultPalette,
            editingBlob = false,
            selectedIndex = 1,
            color = Color(0xFF102030),
        )
        val last = updateGradientCustomColor(
            customColors = first,
            defaultPalette = defaultPalette,
            editingBlob = false,
            selectedIndex = 1,
            color = Color(0xFF708090),
        )

        assertEquals(5, last.baseColors.size)
        assertEquals(0xFF708090, last.baseColors[1])
        assertTrue(last.blobColors.isEmpty())
    }

    @Test
    fun `fixed palette presets provide distinct colors while classic stays compatible`() {
        val scheme = lightColorScheme()
        val classic = createGradientBackgroundPalette(scheme, dark = false, followTheme = false)
        val aurora = createGradientBackgroundPalette(
            scheme,
            dark = false,
            followTheme = false,
            preset = GradientBackgroundPreset.AURORA,
        )
        val monochrome = createGradientBackgroundPalette(
            scheme,
            dark = false,
            followTheme = false,
            preset = GradientBackgroundPreset.MONOCHROME,
        )

        assertEquals(Color(0xFFAFD0F2), classic.baseStops.first().second)
        assertTrue(aurora.baseStops.first().second != classic.baseStops.first().second)
        assertTrue(monochrome.blobs.first().color != aurora.blobs.first().color)
    }

    @Test
    fun `older devices use fewer slower and weaker gradient layers`() {
        val full = profile(AdvancedAppearanceSupport.FULL)
        val reduced = profile(AdvancedAppearanceSupport.REDUCED)
        val unsupported = profile(AdvancedAppearanceSupport.UNSUPPORTED)

        assertEquals(4, full.blobCount)
        assertEquals(3, reduced.blobCount)
        assertEquals(2, unsupported.blobCount)
        assertTrue(full.frameIntervalMillis < reduced.frameIntervalMillis)
        assertTrue(reduced.frameIntervalMillis < unsupported.frameIntervalMillis)
        assertTrue(full.motionScale > reduced.motionScale)
        assertTrue(reduced.motionScale > unsupported.motionScale)
    }

    @Test
    fun `system reduced motion stops animation when respected`() {
        val respected = resolveGradientRenderProfile(
            support = AdvancedAppearanceSupport.FULL,
            animationRequested = true,
            performanceEffectsEnabled = true,
            respectSystemReducedMotion = true,
            systemMotionAllowed = false,
            requestedMotionScale = 1f,
        )
        val ignored = resolveGradientRenderProfile(
            support = AdvancedAppearanceSupport.FULL,
            animationRequested = true,
            performanceEffectsEnabled = true,
            respectSystemReducedMotion = false,
            systemMotionAllowed = false,
            requestedMotionScale = 1f,
        )

        assertFalse(respected.animationEnabled)
        assertTrue(ignored.animationEnabled)
    }

    @Test
    fun `performance switch keeps a static lightweight gradient`() {
        val profile = resolveGradientRenderProfile(
            support = AdvancedAppearanceSupport.FULL,
            animationRequested = true,
            performanceEffectsEnabled = false,
            respectSystemReducedMotion = false,
            systemMotionAllowed = true,
            requestedMotionScale = 1f,
        )

        assertFalse(profile.animationEnabled)
        assertEquals(2, profile.blobCount)
    }

    @Test
    fun `AGSL profile refreshes more smoothly than Kotlin on supported devices`() {
        val agsl = resolveGradientRenderProfile(
            support = AdvancedAppearanceSupport.FULL,
            rendererBackend = GradientRendererBackend.AGSL,
            animationRequested = true,
            performanceEffectsEnabled = true,
            respectSystemReducedMotion = true,
            systemMotionAllowed = true,
            requestedMotionScale = 1f,
        )
        val kotlin = profile(AdvancedAppearanceSupport.FULL)

        assertTrue(agsl.frameIntervalMillis < kotlin.frameIntervalMillis)
        assertEquals(4, agsl.blobCount)
    }

    @Test
    fun `active chat interaction reduces gradient refresh work`() {
        val idle = resolveGradientRenderProfile(
            support = AdvancedAppearanceSupport.FULL,
            rendererBackend = GradientRendererBackend.AGSL,
            animationRequested = true,
            performanceEffectsEnabled = true,
            respectSystemReducedMotion = true,
            systemMotionAllowed = true,
            requestedMotionScale = 1f,
            interactionInProgress = false,
        )
        val busy = resolveGradientRenderProfile(
            support = AdvancedAppearanceSupport.FULL,
            rendererBackend = GradientRendererBackend.AGSL,
            animationRequested = true,
            performanceEffectsEnabled = true,
            respectSystemReducedMotion = true,
            systemMotionAllowed = true,
            requestedMotionScale = 1f,
            interactionInProgress = true,
        )

        assertTrue(busy.frameIntervalMillis > idle.frameIntervalMillis)
        assertTrue(busy.animationEnabled)
    }

    @Test
    fun `requested light layers are clamped by device support and performance mode`() {
        assertEquals(
            4,
            resolveGradientRenderProfile(
                support = AdvancedAppearanceSupport.FULL,
                animationRequested = true,
                performanceEffectsEnabled = true,
                respectSystemReducedMotion = false,
                systemMotionAllowed = true,
                requestedMotionScale = 1f,
                requestedBlobCount = 99,
            ).blobCount,
        )
        assertEquals(
            0,
            resolveGradientRenderProfile(
                support = AdvancedAppearanceSupport.FULL,
                animationRequested = true,
                performanceEffectsEnabled = true,
                respectSystemReducedMotion = false,
                systemMotionAllowed = true,
                requestedMotionScale = 1f,
                requestedBlobCount = 0,
            ).blobCount,
        )
        assertEquals(
            2,
            resolveGradientRenderProfile(
                support = AdvancedAppearanceSupport.FULL,
                animationRequested = true,
                performanceEffectsEnabled = false,
                respectSystemReducedMotion = false,
                systemMotionAllowed = true,
                requestedMotionScale = 1f,
                requestedBlobCount = 4,
            ).blobCount,
        )
    }

    @Test
    fun `wide layouts use a centered tighter gradient distribution`() {
        val phone = resolveGradientLayoutProfile(width = 1080f, height = 2400f)
        val foldable = resolveGradientLayoutProfile(width = 1768f, height = 2208f)
        val landscape = resolveGradientLayoutProfile(width = 2400f, height = 1080f)

        assertEquals(0f, phone.verticalShift)
        assertTrue(foldable.verticalShift > phone.verticalShift)
        assertTrue(landscape.verticalShift > foldable.verticalShift)
        assertTrue(foldable.radiusScale < phone.radiusScale)
        assertTrue(landscape.radiusScale < foldable.radiusScale)
        assertTrue(landscape.verticalMotionScale < phone.verticalMotionScale)
    }

    private fun profile(support: AdvancedAppearanceSupport) = resolveGradientRenderProfile(
        support = support,
        animationRequested = true,
        performanceEffectsEnabled = true,
        respectSystemReducedMotion = true,
        systemMotionAllowed = true,
        requestedMotionScale = 1f,
    )
}
