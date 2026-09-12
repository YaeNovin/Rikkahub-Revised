package me.rerere.material3

import androidx.compose.material3.ColorScheme
import dynamiccolor.ColorSpecs
import dynamiccolor.DynamicScheme
import dynamiccolor.Variant
import hct.Hct
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import palettes.TonalPalette

class DynamicColorSchemeFactoryTest {
    @Test
    fun `default options preserve the legacy custom theme output`() {
        val seeds = listOf(
            Triple(0xFF6750A4L, null, null),
            Triple(0xFF336699L, 0xFFAA2200L, null),
            Triple(0xFF005522L, 0xFF3355AAL, 0xFFAA33CCL),
        )

        for ((primary, secondary, tertiary) in seeds) {
            for (dark in listOf(false, true)) {
                val expected = legacyColorScheme(primary, secondary, tertiary, dark)
                val actual = createDynamicColorScheme(primary, secondary, tertiary, dark)
                assertColorSchemesEqual(expected, actual)
            }
        }
    }

    @Test
    fun `same inputs produce the same colors`() {
        val first = createDynamicColorScheme(0xFF1A73E8, dark = false)
        val second = createDynamicColorScheme(0xFF1A73E8, dark = false)

        assertColorSchemesEqual(first, second)
    }

    @Test
    fun `dynamic scheme cache is bounded and includes options in its key`() {
        DynamicColorSchemeCache.clearForTests()
        createDynamicColorScheme(
            primaryColorArgb = 0xFF6750A4,
            dark = false,
            options = DynamicColorSchemeOptions(variant = MaterialColorVariant.TONAL_SPOT),
        )
        val neutral = createDynamicColorScheme(
            primaryColorArgb = 0xFF6750A4,
            dark = false,
            options = DynamicColorSchemeOptions(variant = MaterialColorVariant.NEUTRAL),
        )
        val tonal = createDynamicColorScheme(
            primaryColorArgb = 0xFF6750A4,
            dark = false,
            options = DynamicColorSchemeOptions(variant = MaterialColorVariant.TONAL_SPOT),
        )

        assertColorSchemesEqual(tonal, createDynamicColorScheme(0xFF6750A4, dark = false))
        assertNotEquals(tonal.primary, neutral.primary)

        repeat(32) { index ->
            createDynamicColorScheme(0xFF000000L + index, dark = index % 2 == 0)
        }
        assertTrue(DynamicColorSchemeCache.sizeForTests() <= 16)
    }

    @Test
    fun `light and dark inputs produce different surfaces`() {
        val light = createDynamicColorScheme(0xFF6750A4, dark = false)
        val dark = createDynamicColorScheme(0xFF6750A4, dark = true)

        assertNotEquals(light.surface, dark.surface)
        assertNotEquals(light.onSurface, dark.onSurface)
    }

    @Test
    fun `explicit secondary and tertiary seeds replace generated palettes`() {
        val generated = createDynamicColorScheme(0xFF6750A4, dark = false)
        val customized = createDynamicColorScheme(
            primaryColorArgb = 0xFF6750A4,
            secondaryColorArgb = 0xFF00A020,
            tertiaryColorArgb = 0xFFE02020,
            dark = false,
        )

        assertNotEquals(generated.secondary, customized.secondary)
        assertNotEquals(generated.tertiary, customized.tertiary)
    }

    @Test
    fun `black white and low chroma seeds produce opaque role colors`() {
        val seeds = listOf(0xFF000000L, 0xFFFFFFFFL, 0xFF777777L)

        for (seed in seeds) {
            val scheme = createDynamicColorScheme(seed, dark = false)
            listOf(
                scheme.primary,
                scheme.onPrimary,
                scheme.secondary,
                scheme.tertiary,
                scheme.background,
                scheme.surface,
                scheme.error,
            ).forEach { color -> assertEquals(1f, color.alpha, 0f) }
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `contrast below supported range is rejected`() {
        DynamicColorSchemeOptions(contrastLevel = -1.01)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `contrast above supported range is rejected`() {
        DynamicColorSchemeOptions(contrastLevel = 1.01)
    }

    @Test
    fun `supported contrast boundaries can generate schemes`() {
        for (contrast in listOf(-1.0, 1.0)) {
            val scheme = createDynamicColorScheme(
                primaryColorArgb = 0xFF6750A4,
                dark = false,
                options = DynamicColorSchemeOptions(contrastLevel = contrast),
            )
            assertTrue(scheme.primary.alpha == 1f)
        }
    }

    private fun legacyColorScheme(
        primaryColorArgb: Long,
        secondaryColorArgb: Long?,
        tertiaryColorArgb: Long?,
        dark: Boolean,
    ): ColorScheme {
        val sourceHct = Hct.fromInt(primaryColorArgb.toInt())
        val specVersion = DynamicScheme.DEFAULT_SPEC_VERSION
        val platform = DynamicScheme.DEFAULT_PLATFORM
        val contrastLevel = 0.0
        val colorSpec = ColorSpecs.get(specVersion)
        val variant = Variant.TONAL_SPOT

        return DynamicScheme(
            sourceHct,
            variant,
            dark,
            contrastLevel,
            platform,
            specVersion,
            colorSpec.getPrimaryPalette(variant, sourceHct, dark, platform, contrastLevel),
            secondaryColorArgb?.let { TonalPalette.fromInt(it.toInt()) }
                ?: colorSpec.getSecondaryPalette(
                    variant,
                    sourceHct,
                    dark,
                    platform,
                    contrastLevel,
                ),
            tertiaryColorArgb?.let { TonalPalette.fromInt(it.toInt()) }
                ?: colorSpec.getTertiaryPalette(
                    variant,
                    sourceHct,
                    dark,
                    platform,
                    contrastLevel,
                ),
            colorSpec.getNeutralPalette(variant, sourceHct, dark, platform, contrastLevel),
            colorSpec.getNeutralVariantPalette(variant, sourceHct, dark, platform, contrastLevel),
            colorSpec.getErrorPalette(variant, sourceHct, dark, platform, contrastLevel),
        ).toColorScheme()
    }

    private fun assertColorSchemesEqual(expected: ColorScheme, actual: ColorScheme) {
        val roles = listOf(
            "primary" to (expected.primary to actual.primary),
            "onPrimary" to (expected.onPrimary to actual.onPrimary),
            "primaryContainer" to (expected.primaryContainer to actual.primaryContainer),
            "onPrimaryContainer" to (expected.onPrimaryContainer to actual.onPrimaryContainer),
            "secondary" to (expected.secondary to actual.secondary),
            "onSecondary" to (expected.onSecondary to actual.onSecondary),
            "secondaryContainer" to (expected.secondaryContainer to actual.secondaryContainer),
            "onSecondaryContainer" to (expected.onSecondaryContainer to actual.onSecondaryContainer),
            "tertiary" to (expected.tertiary to actual.tertiary),
            "onTertiary" to (expected.onTertiary to actual.onTertiary),
            "tertiaryContainer" to (expected.tertiaryContainer to actual.tertiaryContainer),
            "onTertiaryContainer" to (expected.onTertiaryContainer to actual.onTertiaryContainer),
            "background" to (expected.background to actual.background),
            "onBackground" to (expected.onBackground to actual.onBackground),
            "surface" to (expected.surface to actual.surface),
            "onSurface" to (expected.onSurface to actual.onSurface),
            "surfaceVariant" to (expected.surfaceVariant to actual.surfaceVariant),
            "onSurfaceVariant" to (expected.onSurfaceVariant to actual.onSurfaceVariant),
            "surfaceTint" to (expected.surfaceTint to actual.surfaceTint),
            "inverseSurface" to (expected.inverseSurface to actual.inverseSurface),
            "inverseOnSurface" to (expected.inverseOnSurface to actual.inverseOnSurface),
            "inversePrimary" to (expected.inversePrimary to actual.inversePrimary),
            "error" to (expected.error to actual.error),
            "onError" to (expected.onError to actual.onError),
            "errorContainer" to (expected.errorContainer to actual.errorContainer),
            "onErrorContainer" to (expected.onErrorContainer to actual.onErrorContainer),
            "outline" to (expected.outline to actual.outline),
            "outlineVariant" to (expected.outlineVariant to actual.outlineVariant),
            "scrim" to (expected.scrim to actual.scrim),
            "surfaceBright" to (expected.surfaceBright to actual.surfaceBright),
            "surfaceDim" to (expected.surfaceDim to actual.surfaceDim),
            "surfaceContainer" to (expected.surfaceContainer to actual.surfaceContainer),
            "surfaceContainerHigh" to
                (expected.surfaceContainerHigh to actual.surfaceContainerHigh),
            "surfaceContainerHighest" to
                (expected.surfaceContainerHighest to actual.surfaceContainerHighest),
            "surfaceContainerLow" to (expected.surfaceContainerLow to actual.surfaceContainerLow),
            "surfaceContainerLowest" to
                (expected.surfaceContainerLowest to actual.surfaceContainerLowest),
            "primaryFixed" to (expected.primaryFixed to actual.primaryFixed),
            "primaryFixedDim" to (expected.primaryFixedDim to actual.primaryFixedDim),
            "onPrimaryFixed" to (expected.onPrimaryFixed to actual.onPrimaryFixed),
            "onPrimaryFixedVariant" to
                (expected.onPrimaryFixedVariant to actual.onPrimaryFixedVariant),
            "secondaryFixed" to (expected.secondaryFixed to actual.secondaryFixed),
            "secondaryFixedDim" to (expected.secondaryFixedDim to actual.secondaryFixedDim),
            "onSecondaryFixed" to (expected.onSecondaryFixed to actual.onSecondaryFixed),
            "onSecondaryFixedVariant" to
                (expected.onSecondaryFixedVariant to actual.onSecondaryFixedVariant),
            "tertiaryFixed" to (expected.tertiaryFixed to actual.tertiaryFixed),
            "tertiaryFixedDim" to (expected.tertiaryFixedDim to actual.tertiaryFixedDim),
            "onTertiaryFixed" to (expected.onTertiaryFixed to actual.onTertiaryFixed),
            "onTertiaryFixedVariant" to
                (expected.onTertiaryFixedVariant to actual.onTertiaryFixedVariant),
        )

        roles.forEach { (name, values) ->
            assertEquals(name, values.first, values.second)
        }
    }
}
