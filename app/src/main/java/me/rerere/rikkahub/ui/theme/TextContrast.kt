package me.rerere.rikkahub.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp

private data class ContrastKey(val preferred: Color, val backgrounds: List<Color>, val minimum: Float)
private val contrastCache = object : LinkedHashMap<ContrastKey, Color>(256, .75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ContrastKey, Color>?) = size > 256
}
private val luminanceCache = object : LinkedHashMap<List<Color>, List<Float>>(32, .75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<List<Color>, List<Float>>?) = size > 32
}

private fun cachedLuminances(backgrounds: List<Color>): List<Float> {
    synchronized(luminanceCache) { luminanceCache[backgrounds]?.let { return it } }
    val key = backgrounds.toList()
    return key.map(::wcagRelativeLuminance).also { synchronized(luminanceCache) { luminanceCache[key] = it } }
}

internal fun hasTextContrast(color: Color, backgrounds: List<Color>, minimum: Float = WCAG_NORMAL_TEXT_MIN_CONTRAST): Boolean {
    if (color == Color.Unspecified || backgrounds.isEmpty()) return false
    // Samples bound a continuous background: a mid-gray passing black AND white
    // still fails on gray pixels between them. Require a consistent luminance polarity.
    val signs = backgrounds.map { background ->
        wcagRelativeLuminance(color.compositeOver(background.copy(alpha = 1f))) >= wcagRelativeLuminance(background)
    }
    return signs.all { it == signs.first() } && backgrounds.all { wcagContrastRatio(color, it) >= minimum }
}

/** All app-owned text uses the normal-text threshold, including small comments and labels.
 * Passing this check is not a claim of complete WCAG conformance (layout, focus, etc.). */
internal fun contrastingTextColor(
    preferred: Color,
    backgrounds: List<Color>,
    minimum: Float = WCAG_NORMAL_TEXT_MIN_CONTRAST,
): Color {
    val key = ContrastKey(preferred, backgrounds.toList(), minimum)
    synchronized(contrastCache) { contrastCache[key]?.let { return it } }
    return computeContrastingTextColor(preferred, backgrounds, minimum).also {
        synchronized(contrastCache) { contrastCache[key] = it }
    }
}

private fun computeContrastingTextColor(preferred: Color, backgrounds: List<Color>, minimum: Float): Color {
    val samples = backgrounds.ifEmpty { listOf(Color.White) }
    val original = preferred.takeUnless { it == Color.Unspecified } ?: readableForegroundColor(samples)
    val luminances = cachedLuminances(samples)
    fun passes(color: Color): Boolean {
        if (color.alpha < 1f) return hasTextContrast(color, samples, minimum)
        val value = wcagRelativeLuminance(color)
        val lighter = value >= luminances.first()
        return luminances.all { (value >= it) == lighter && contrastFromLuminances(value, it) >= minimum }
    }
    if (passes(original)) return original
    val opaque = original.copy(alpha = 1f)
    if (passes(opaque)) return opaque
    val candidates = listOf(Color.Black, Color.White).filter(::passes).map { end ->
        var low = 0f
        var high = 1f
        repeat(20) {
            val middle = (low + high) / 2f
            if (passes(lerp(opaque, end, middle))) high = middle else low = middle
        }
        lerp(opaque, end, (high + .00001f).coerceAtMost(1f))
    }
    // A single color cannot contrast with every pixel of an arbitrary image.
    // Keep the user's surface unchanged and choose a best-effort foreground in that case;
    // this fallback does not guarantee the requested contrast across the whole image.
    return candidates.minByOrNull { color ->
        val dr = color.red - opaque.red
        val dg = color.green - opaque.green
        val db = color.blue - opaque.blue
        dr * dr + dg * dg + db * db
    } ?: if (preservesPaletteContrast(opaque, samples)) opaque
        else if (hasVisiblePaletteHue(opaque)) textForegroundFor(samples, opaque.toArgb(), original)
        else readableForegroundColor(samples)
}

internal data class TextContrastSurface(
    val container: Color,
    val foreground: Color,
    val backgrounds: List<Color>,
)

/** Only correct the foreground. Appearance settings own the surface color and opacity,
 * even when no single text color can reach the target contrast across all underlays. */
internal fun textContrastSurface(
    requestedContainer: Color,
    fallbackContainer: Color,
    preferredForeground: Color,
    underlays: List<Color>,
): TextContrastSurface {
    val samples = underlays.ifEmpty { listOf(fallbackContainer.copy(alpha = 1f)) }
    val backgrounds = samples.map { requestedContainer.compositeOver(it.copy(alpha = 1f)) }
    return TextContrastSurface(
        container = requestedContainer,
        foreground = contrastingTextColor(preferredForeground, backgrounds),
        backgrounds = backgrounds,
    )
}

/** Correct foregrounds without changing wallpapers, semantic fills or the user's palette. */
internal fun ColorScheme.withAccessibleTextRoles(): ColorScheme {
    val surfaces = listOf(surface, surfaceDim, surfaceBright, surfaceContainerLowest,
        surfaceContainerLow, surfaceContainer, surfaceContainerHigh, surfaceContainerHighest)
        .map { it.copy(alpha = 1f) }
    return copy(
        onBackground = contrastingTextColor(onBackground, listOf(background.copy(alpha = 1f))),
        onSurface = contrastingTextColor(onSurface, surfaces),
        onSurfaceVariant = contrastingTextColor(onSurfaceVariant, surfaces + surfaceVariant.copy(alpha = 1f)),
        onPrimary = contrastingTextColor(onPrimary, listOf(primary)),
        onPrimaryContainer = contrastingTextColor(onPrimaryContainer, listOf(primaryContainer)),
        onSecondary = contrastingTextColor(onSecondary, listOf(secondary)),
        onSecondaryContainer = contrastingTextColor(onSecondaryContainer, listOf(secondaryContainer)),
        onTertiary = contrastingTextColor(onTertiary, listOf(tertiary)),
        onTertiaryContainer = contrastingTextColor(onTertiaryContainer, listOf(tertiaryContainer)),
        onError = contrastingTextColor(onError, listOf(error)),
        onErrorContainer = contrastingTextColor(onErrorContainer, listOf(errorContainer)),
        inverseOnSurface = contrastingTextColor(inverseOnSurface, listOf(inverseSurface)),
    )
}
