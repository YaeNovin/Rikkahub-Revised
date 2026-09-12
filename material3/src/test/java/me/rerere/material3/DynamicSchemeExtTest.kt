package me.rerere.material3

import androidx.compose.ui.graphics.Color
import dynamiccolor.DynamicScheme
import hct.Hct
import org.junit.Assert.assertEquals
import org.junit.Test
import scheme.SchemeTonalSpot

class DynamicSchemeExtTest {
    @Test
    fun `maps every light color role from the dynamic scheme`() {
        assertAllRolesMapped(
            SchemeTonalSpot(Hct.fromInt(0xFF6750A4.toInt()), false, 0.0),
        )
    }

    @Test
    fun `maps every dark color role from the dynamic scheme`() {
        assertAllRolesMapped(
            SchemeTonalSpot(Hct.fromInt(0xFF6750A4.toInt()), true, 0.0),
        )
    }

    private fun assertAllRolesMapped(source: DynamicScheme) {
        val actual = source.toColorScheme()
        val roles = listOf(
            "primary" to (source.primary to actual.primary),
            "onPrimary" to (source.onPrimary to actual.onPrimary),
            "primaryContainer" to (source.primaryContainer to actual.primaryContainer),
            "onPrimaryContainer" to (source.onPrimaryContainer to actual.onPrimaryContainer),
            "inversePrimary" to (source.inversePrimary to actual.inversePrimary),
            "secondary" to (source.secondary to actual.secondary),
            "onSecondary" to (source.onSecondary to actual.onSecondary),
            "secondaryContainer" to (source.secondaryContainer to actual.secondaryContainer),
            "onSecondaryContainer" to (source.onSecondaryContainer to actual.onSecondaryContainer),
            "tertiary" to (source.tertiary to actual.tertiary),
            "onTertiary" to (source.onTertiary to actual.onTertiary),
            "tertiaryContainer" to (source.tertiaryContainer to actual.tertiaryContainer),
            "onTertiaryContainer" to (source.onTertiaryContainer to actual.onTertiaryContainer),
            "background" to (source.background to actual.background),
            "onBackground" to (source.onBackground to actual.onBackground),
            "surface" to (source.surface to actual.surface),
            "onSurface" to (source.onSurface to actual.onSurface),
            "surfaceVariant" to (source.surfaceVariant to actual.surfaceVariant),
            "onSurfaceVariant" to (source.onSurfaceVariant to actual.onSurfaceVariant),
            "surfaceTint" to (source.surfaceTint to actual.surfaceTint),
            "inverseSurface" to (source.inverseSurface to actual.inverseSurface),
            "inverseOnSurface" to (source.inverseOnSurface to actual.inverseOnSurface),
            "error" to (source.error to actual.error),
            "onError" to (source.onError to actual.onError),
            "errorContainer" to (source.errorContainer to actual.errorContainer),
            "onErrorContainer" to (source.onErrorContainer to actual.onErrorContainer),
            "outline" to (source.outline to actual.outline),
            "outlineVariant" to (source.outlineVariant to actual.outlineVariant),
            "scrim" to (source.scrim to actual.scrim),
            "surfaceBright" to (source.surfaceBright to actual.surfaceBright),
            "surfaceDim" to (source.surfaceDim to actual.surfaceDim),
            "surfaceContainer" to (source.surfaceContainer to actual.surfaceContainer),
            "surfaceContainerHigh" to (source.surfaceContainerHigh to actual.surfaceContainerHigh),
            "surfaceContainerHighest" to
                (source.surfaceContainerHighest to actual.surfaceContainerHighest),
            "surfaceContainerLow" to (source.surfaceContainerLow to actual.surfaceContainerLow),
            "surfaceContainerLowest" to
                (source.surfaceContainerLowest to actual.surfaceContainerLowest),
            "primaryFixed" to (source.primaryFixed to actual.primaryFixed),
            "primaryFixedDim" to (source.primaryFixedDim to actual.primaryFixedDim),
            "onPrimaryFixed" to (source.onPrimaryFixed to actual.onPrimaryFixed),
            "onPrimaryFixedVariant" to
                (source.onPrimaryFixedVariant to actual.onPrimaryFixedVariant),
            "secondaryFixed" to (source.secondaryFixed to actual.secondaryFixed),
            "secondaryFixedDim" to (source.secondaryFixedDim to actual.secondaryFixedDim),
            "onSecondaryFixed" to (source.onSecondaryFixed to actual.onSecondaryFixed),
            "onSecondaryFixedVariant" to
                (source.onSecondaryFixedVariant to actual.onSecondaryFixedVariant),
            "tertiaryFixed" to (source.tertiaryFixed to actual.tertiaryFixed),
            "tertiaryFixedDim" to (source.tertiaryFixedDim to actual.tertiaryFixedDim),
            "onTertiaryFixed" to (source.onTertiaryFixed to actual.onTertiaryFixed),
            "onTertiaryFixedVariant" to
                (source.onTertiaryFixedVariant to actual.onTertiaryFixedVariant),
        )

        roles.forEach { (name, values) ->
            assertEquals(name, Color(values.first), values.second)
        }
    }
}
