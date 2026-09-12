package me.rerere.rikkahub.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import me.rerere.material3.hctColorFromArgb
import me.rerere.material3.hctColorFromComponents
import me.rerere.rikkahub.data.datastore.TextColorMode
import me.rerere.rikkahub.ui.components.ui.LocalAppearanceBackground

internal val LocalWallpaperTextSeed = compositionLocalOf { WallpaperTextSeed() }
internal val LocalTextColorMode = compositionLocalOf { TextColorMode.AUTO_CLEAR }

@Composable
internal fun currentTextPaletteSeed(): Int? = textPaletteSeed(
    LocalTextColorMode.current,
    LocalAppearanceBackground.current?.readability?.seedArgb,
    LocalWallpaperTextSeed.current.argb,
)

/** Group sampled hues so one highly saturated pixel does not dictate the entire text palette. */
internal fun backgroundTextSeed(samples: List<Color>): Int? {
    val chromatic = samples.map { hctColorFromArgb(it.toArgb()) }
        .filter { it.chroma >= 8 && it.tone in 8.0..92.0 }
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
    val neutral = readableForegroundColor(backgrounds)
    if (seedArgb == null) return neutral
    val seed = hctColorFromArgb(seedArgb)
    val preferredLight = neutral == Color.White
    // Leave enough tone and chroma for the selected hue to remain visible. RGB contrast,
    // rather than an almost-white/black palette, determines how far we may go.
    val tones = if (preferredLight) (82..100).toList() + (28 downTo 0).toList()
        else (28 downTo 0).toList() + (82..100).toList()
    val chroma = seed.chroma.coerceAtMost(32.0)
    val neutralContrasts = backgrounds.map { wcagContrastRatio(neutral, it) }
    val passing = neutralContrasts.count { it >= WCAG_NORMAL_TEXT_MIN_CONTRAST }
    var mixedCandidate: Color? = null
    for (tone in tones) {
        val candidate = Color(hctColorFromComponents(seed.hue, chroma, tone.toDouble()).argb)
        val contrasts = backgrounds.map { wcagContrastRatio(candidate, it) }
        if (contrasts.all { it >= WCAG_NORMAL_TEXT_MIN_CONTRAST }) return candidate
        if (mixedCandidate == null && contrasts.count { it >= WCAG_NORMAL_TEXT_MIN_CONTRAST } >= passing &&
            contrasts.min() >= neutralContrasts.min()) mixedCandidate = candidate
    }
    // A mixed image may admit no globally passing color. Retain the hue only if it
    // preserves both the neutral fallback's passing coverage and worst contrast.
    return mixedCandidate ?: neutral
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
