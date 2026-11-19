package me.rerere.rikkahub.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import me.rerere.material3.hctColorFromArgb
import me.rerere.material3.hctColorFromComponents
import me.rerere.rikkahub.data.datastore.TextColorMode
import me.rerere.rikkahub.ui.components.ui.LocalAppearanceBackground

internal val LocalWallpaperTextSeed = compositionLocalOf { WallpaperTextSeed() }
internal val LocalTextColorMode = compositionLocalOf { TextColorMode.AUTO_CLEAR }
internal const val TEXT_PALETTE_TARGET_CONTRAST = 7f
internal const val TEXT_PALETTE_CHROMA_EXPERIMENT = 1.11

private data class TextPaletteKey(val backgrounds: List<Color>, val seed: Int?)
private val textPaletteCache = object : LinkedHashMap<TextPaletteKey, Color>(128, .75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<TextPaletteKey, Color>?): Boolean = size > 128
}

@Composable
internal fun currentTextPaletteSeed(): Int? = textPaletteSeed(
    LocalTextColorMode.current,
    LocalAppearanceBackground.current?.readability?.seedArgb,
    LocalWallpaperTextSeed.current.argb,
)

/** Group sampled hues so one highly saturated pixel does not dictate the entire text palette. */
internal fun backgroundTextSeed(samples: List<Color>): Int? {
    val chromatic = samples.map { hctColorFromArgb(it.toArgb()) }
        .filter { it.chroma >= 8 && it.tone in 2.0..99.0 }
    val group = chromatic.groupBy { (it.hue / 30).toInt() }.values
        .maxByOrNull { colors -> colors.sumOf { it.chroma.coerceAtMost(50.0) } } ?: return null
    return group.maxBy { it.chroma }.argb
}

/** HCT supplies hue, while rendered RGB contrast decides whether a text color is usable. */
internal fun textForegroundFor(
    backgrounds: List<Color>,
    seedArgb: Int?,
    fallback: Color = Color.Unspecified,
): Color {
    if (backgrounds.isEmpty()) return fallback
    val key = TextPaletteKey(backgrounds.toList(), seedArgb)
    synchronized(textPaletteCache) { textPaletteCache[key]?.let { return it } }
    return computeTextForeground(backgrounds, seedArgb).also {
        synchronized(textPaletteCache) { textPaletteCache[key] = it }
    }
}

private fun computeTextForeground(backgrounds: List<Color>, seedArgb: Int?): Color {
    val neutral = readableForegroundColor(backgrounds)
    if (seedArgb == null) return neutral
    val seed = hctColorFromArgb(seedArgb)
    val wantsHue = seed.chroma >= 5
    val preferredLight = neutral == Color.White
    // Leave enough tone and chroma for the selected hue to remain visible. RGB contrast,
    // rather than an almost-white/black palette, determines how far we may go.
    val lightTones = (304..400).map { it / 4.0 }
    val darkTones = (128 downTo 0).map { it / 4.0 }
    val tones = if (preferredLight) lightTones + darkTones else darkTones + lightTones
    val chroma = textPaletteChroma(seed.chroma)
    // Candidates are opaque: image luminance is constant throughout the HCT search.
    // Computing sRGB powers for all 240 pixels at every tone dominated cold palette work.
    val luminances = backgrounds.map(::wcagRelativeLuminance)
    fun contrastsFor(color: Color): List<Float> {
        val luminance = wcagRelativeLuminance(color)
        return luminances.map { contrastFromLuminances(luminance, it) }
    }
    val neutralContrasts = contrastsFor(neutral)
    // Mid-tone surfaces sometimes admit no 7:1 foreground. Keep the sampled hue
    // near the best attainable contrast instead of unnecessarily returning pure white.
    val neutralMinimum = neutralContrasts.min()
    val target = if (neutralMinimum >= TEXT_PALETTE_TARGET_CONTRAST) TEXT_PALETTE_TARGET_CONTRAST
        else (neutralMinimum * .96f).coerceAtLeast(WCAG_NORMAL_TEXT_MIN_CONTRAST)
    var normalCandidate: Color? = null
    var mixedCandidate: Color? = null
    var mixedCoverage = -1
    var mixedChroma = -1.0
    for (tone in tones) {
        val candidate = Color(hctColorFromComponents(seed.hue, chroma, tone).argb)
        val contrasts = contrastsFor(candidate)
        if (contrasts.all { it >= target } && (!wantsHue || hasVisiblePaletteHue(candidate))) return candidate
        if (normalCandidate == null && contrasts.all { it >= WCAG_NORMAL_TEXT_MIN_CONTRAST } &&
            (!wantsHue || hasVisiblePaletteHue(candidate))) normalCandidate = candidate
        if (neutralMinimum < WCAG_NORMAL_TEXT_MIN_CONTRAST && hasVisiblePaletteHue(candidate) &&
            preservesPaletteContrast(contrasts, neutralContrasts)) {
            val coverage = contrasts.indices.count {
                neutralContrasts[it] >= WCAG_NORMAL_TEXT_MIN_CONTRAST && contrasts[it] >= WCAG_NORMAL_TEXT_MIN_CONTRAST
            }
            val visibleChroma = hctColorFromArgb(candidate.toArgb()).chroma
            if (coverage > mixedCoverage || (coverage == mixedCoverage && visibleChroma > mixedChroma)) {
                mixedCandidate = candidate
                mixedCoverage = coverage
                mixedChroma = visibleChroma
            }
        }
    }
    normalCandidate?.let { return it }
    mixedCandidate?.let { return it }
    // Very light/dark RGB gamuts can carry a lower-chroma tint even though our
    // visibility threshold excludes it. Retain that hue if contrast permits.
    for (tone in tones) {
        val candidate = Color(hctColorFromComponents(seed.hue, chroma, tone).argb)
        if (contrastsFor(candidate).all { it >= WCAG_NORMAL_TEXT_MIN_CONTRAST }) return candidate
    }
    return neutral
}

internal fun textPaletteChroma(sourceChroma: Double): Double =
    if (sourceChroma >= 5) (sourceChroma * 1.35).coerceIn(40.0, 56.0) * TEXT_PALETTE_CHROMA_EXPERIMENT
    else sourceChroma

internal fun hasVisiblePaletteHue(color: Color): Boolean =
    hctColorFromArgb(color.toArgb()).chroma >= 5.0 &&
        maxOf(color.red, color.green, color.blue) - minOf(color.red, color.green, color.blue) >= .06f

private fun preservesPaletteContrast(contrasts: List<Float>, neutral: List<Float>): Boolean {
    // Samples just across 4.5 must not make all colored candidates disappear.
    // Bound every sample's shortfall to 15%. Saturation and weight improve visual
    // separation, but do not claim WCAG compliance for arbitrary mixed photos.
    // Selection still maximizes the number reaching the full 4.5 threshold.
    return contrasts.indices.all { contrasts[it] >= neutral[it] * .85f }
}

internal fun preservesPaletteContrast(color: Color, backgrounds: List<Color>): Boolean {
    val neutral = readableForegroundColor(backgrounds)
    return hasVisiblePaletteHue(color) && preservesPaletteContrast(
        backgrounds.map { wcagContrastRatio(color, it) }, backgrounds.map { wcagContrastRatio(neutral, it) })
}

internal fun textPaletteSeed(mode: TextColorMode, appSeed: Int?, wallpaperSeed: Int?): Int? = when (mode) {
    TextColorMode.THEME -> null
    TextColorMode.AUTO_CLEAR -> null
    TextColorMode.APP_BACKGROUND -> appSeed
    TextColorMode.SYSTEM_WALLPAPER -> wallpaperSeed
}

internal fun backgroundTextForeground(
    mode: TextColorMode, backgrounds: List<Color>, seed: Int?, themeForeground: Color, hasBackground: Boolean,
): Color = when {
    mode == TextColorMode.THEME -> themeForeground
    !hasBackground && seed == null -> themeForeground
    else -> textForegroundFor(backgrounds, seed, themeForeground)
}

internal fun ColorScheme.withTextPalette(seed: Int?): ColorScheme {
    if (seed == null) return this
    return copy(
        onBackground = textForegroundFor(listOf(background.copy(alpha = 1f)), seed),
        onSurface = textForegroundFor(listOf(surface.copy(alpha = 1f)), seed),
        onSurfaceVariant = textForegroundFor(listOf(surfaceVariant.copy(alpha = 1f)), seed),
    )
}

/** A wallpaper foreground belongs to the page, not to every card painted over it. */
internal fun ColorScheme.withSurfaceTextColors(foreground: Color, secondary: Color = foreground): ColorScheme =
    copy(onSurface = foreground, onSurfaceVariant = secondary, onBackground = foreground)

@Composable
internal fun SurfaceTextTheme(foreground: Color, secondary: Color = foreground,
    backgrounds: List<Color>? = null, content: @Composable () -> Unit) {
    val samples = backgrounds ?: me.rerere.rikkahub.ui.components.richtext.LocalTextSurfaceBackgrounds.current
        ?: LocalAppearanceBackground.current?.readability?.backgrounds.orEmpty()
    androidx.compose.runtime.CompositionLocalProvider(
        me.rerere.rikkahub.ui.components.richtext.LocalTextSurfaceBackgrounds provides samples) {
    MaterialTheme(colorScheme = MaterialTheme.colorScheme.withSurfaceTextColors(foreground, secondary)) {
        val style = androidx.compose.material3.LocalTextStyle.current
        androidx.compose.material3.ProvideTextStyle(
            if (LocalTextColorMode.current == TextColorMode.THEME) style
            else style.copy(shadow = null), content)
    }
    }
}
