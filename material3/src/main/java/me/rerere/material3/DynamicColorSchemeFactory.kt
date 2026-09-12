package me.rerere.material3

import androidx.compose.material3.ColorScheme
import dynamiccolor.ColorSpec.SpecVersion
import dynamiccolor.ColorSpecs
import dynamiccolor.DynamicScheme
import dynamiccolor.Variant
import hct.Hct
import palettes.TonalPalette
import java.util.LinkedHashMap

enum class MaterialColorVariant {
    MONOCHROME,
    NEUTRAL,
    TONAL_SPOT,
    VIBRANT,
    EXPRESSIVE,
    FIDELITY,
    CONTENT,
    RAINBOW,
    FRUIT_SALAD,
    CMF,
}

enum class MaterialColorPlatform {
    PHONE,
    WATCH,
}

enum class MaterialColorSpecVersion {
    SPEC_2021,
    SPEC_2025,
    SPEC_2026,
}

data class DynamicColorSchemeOptions(
    val variant: MaterialColorVariant = MaterialColorVariant.TONAL_SPOT,
    val contrastLevel: Double = 0.0,
    val platform: MaterialColorPlatform = MaterialColorPlatform.PHONE,
    val specVersion: MaterialColorSpecVersion = MaterialColorSpecVersion.SPEC_2021,
) {
    init {
        require(contrastLevel in -1.0..1.0) {
            "contrastLevel must be between -1.0 and 1.0"
        }
    }
}

internal data class DynamicColorSchemeCacheKey(
    val primaryColorArgb: Long,
    val secondaryColorArgb: Long?,
    val tertiaryColorArgb: Long?,
    val dark: Boolean,
    val options: DynamicColorSchemeOptions,
)

private const val MAX_DYNAMIC_COLOR_SCHEME_CACHE_ENTRIES = 16

internal object DynamicColorSchemeCache {
    private val lock = Any()
    private val entries = LinkedHashMap<DynamicColorSchemeCacheKey, ColorScheme>(
        MAX_DYNAMIC_COLOR_SCHEME_CACHE_ENTRIES,
        0.75f,
        true,
    )

    fun get(key: DynamicColorSchemeCacheKey): ColorScheme? = synchronized(lock) {
        entries[key]
    }

    fun put(key: DynamicColorSchemeCacheKey, value: ColorScheme) = synchronized(lock) {
        entries[key] = value
        while (entries.size > MAX_DYNAMIC_COLOR_SCHEME_CACHE_ENTRIES) {
            entries.entries.iterator().apply {
                if (hasNext()) {
                    next()
                    remove()
                }
            }
        }
    }

    internal fun clearForTests() = synchronized(lock) {
        entries.clear()
    }

    internal fun sizeForTests(): Int = synchronized(lock) { entries.size }
}

fun createDynamicColorScheme(
    primaryColorArgb: Long,
    secondaryColorArgb: Long? = null,
    tertiaryColorArgb: Long? = null,
    dark: Boolean,
    options: DynamicColorSchemeOptions = DynamicColorSchemeOptions(),
): ColorScheme {
    val key = DynamicColorSchemeCacheKey(
        primaryColorArgb = primaryColorArgb,
        secondaryColorArgb = secondaryColorArgb,
        tertiaryColorArgb = tertiaryColorArgb,
        dark = dark,
        options = options,
    )
    DynamicColorSchemeCache.get(key)?.let { return it }

    val generated = createDynamicColorSchemeUncached(
        primaryColorArgb = primaryColorArgb,
        secondaryColorArgb = secondaryColorArgb,
        tertiaryColorArgb = tertiaryColorArgb,
        dark = dark,
        options = options,
    )
    DynamicColorSchemeCache.put(key, generated)
    return generated
}

private fun createDynamicColorSchemeUncached(
    primaryColorArgb: Long,
    secondaryColorArgb: Long?,
    tertiaryColorArgb: Long?,
    dark: Boolean,
    options: DynamicColorSchemeOptions,
): ColorScheme {
    val sourceHct = Hct.fromInt(primaryColorArgb.toInt())
    val variant = options.variant.toMaterialColorUtilitiesVariant()
    val platform = options.platform.toMaterialColorUtilitiesPlatform()
    val specVersion = resolveSupportedSpecVersion(
        variant = variant,
        requested = options.specVersion.toMaterialColorUtilitiesSpecVersion(),
    )
    val colorSpec = ColorSpecs.get(specVersion)
    val contrastLevel = options.contrastLevel

    val primaryPalette = colorSpec.getPrimaryPalette(
        variant,
        sourceHct,
        dark,
        platform,
        contrastLevel,
    )
    val secondaryPalette = secondaryColorArgb?.let {
        TonalPalette.fromInt(it.toInt())
    } ?: colorSpec.getSecondaryPalette(
        variant,
        sourceHct,
        dark,
        platform,
        contrastLevel,
    )
    val tertiaryPalette = tertiaryColorArgb?.let {
        TonalPalette.fromInt(it.toInt())
    } ?: colorSpec.getTertiaryPalette(
        variant,
        sourceHct,
        dark,
        platform,
        contrastLevel,
    )

    return DynamicScheme(
        sourceHct,
        variant,
        dark,
        contrastLevel,
        platform,
        specVersion,
        primaryPalette,
        secondaryPalette,
        tertiaryPalette,
        colorSpec.getNeutralPalette(variant, sourceHct, dark, platform, contrastLevel),
        colorSpec.getNeutralVariantPalette(variant, sourceHct, dark, platform, contrastLevel),
        colorSpec.getErrorPalette(variant, sourceHct, dark, platform, contrastLevel),
    ).toColorScheme()
}

private fun MaterialColorVariant.toMaterialColorUtilitiesVariant(): Variant = when (this) {
    MaterialColorVariant.MONOCHROME -> Variant.MONOCHROME
    MaterialColorVariant.NEUTRAL -> Variant.NEUTRAL
    MaterialColorVariant.TONAL_SPOT -> Variant.TONAL_SPOT
    MaterialColorVariant.VIBRANT -> Variant.VIBRANT
    MaterialColorVariant.EXPRESSIVE -> Variant.EXPRESSIVE
    MaterialColorVariant.FIDELITY -> Variant.FIDELITY
    MaterialColorVariant.CONTENT -> Variant.CONTENT
    MaterialColorVariant.RAINBOW -> Variant.RAINBOW
    MaterialColorVariant.FRUIT_SALAD -> Variant.FRUIT_SALAD
    MaterialColorVariant.CMF -> Variant.CMF
}

private fun MaterialColorPlatform.toMaterialColorUtilitiesPlatform(): DynamicScheme.Platform =
    when (this) {
        MaterialColorPlatform.PHONE -> DynamicScheme.Platform.PHONE
        MaterialColorPlatform.WATCH -> DynamicScheme.Platform.WATCH
    }

private fun MaterialColorSpecVersion.toMaterialColorUtilitiesSpecVersion(): SpecVersion =
    when (this) {
        MaterialColorSpecVersion.SPEC_2021 -> SpecVersion.SPEC_2021
        MaterialColorSpecVersion.SPEC_2025 -> SpecVersion.SPEC_2025
        MaterialColorSpecVersion.SPEC_2026 -> SpecVersion.SPEC_2026
    }

private fun resolveSupportedSpecVersion(
    variant: Variant,
    requested: SpecVersion,
): SpecVersion = when {
    variant == Variant.CMF -> requested
    variant in setOf(
        Variant.EXPRESSIVE,
        Variant.VIBRANT,
        Variant.TONAL_SPOT,
        Variant.NEUTRAL,
    ) -> if (requested == SpecVersion.SPEC_2026) SpecVersion.SPEC_2025 else requested
    else -> SpecVersion.SPEC_2021
}
