package me.rerere.rikkahub.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

internal const val WCAG_NORMAL_TEXT_MIN_CONTRAST = 4.5f
private const val WCAG_SRGB_LINEAR_THRESHOLD = 0.04045
private const val WCAG_DARK_OFFSET = 0.05

internal fun compositeChatBackgroundColor(
    imageColor: Color,
    imageOpacity: Float,
    overlayColor: Color,
    overlayAlpha: Float,
): Color {
    val opaqueBase = overlayColor.copy(alpha = 1f)
    val imageComposite = imageColor
        .copy(alpha = imageColor.alpha * imageOpacity.coerceIn(0f, 1f))
        .compositeOver(opaqueBase)
    return overlayColor
        .copy(alpha = overlayAlpha.coerceIn(0f, 1f))
        .compositeOver(imageComposite)
}

internal fun readableForegroundColor(background: Color): Color {
    val blackContrast = wcagContrastRatio(Color.Black, background)
    val whiteContrast = wcagContrastRatio(Color.White, background)
    return if (blackContrast >= whiteContrast) Color.Black else Color.White
}

internal fun readableForegroundColor(backgrounds: Iterable<Color>): Color {
    val samples = backgrounds.toList()
    if (samples.isEmpty()) return Color.Unspecified

    fun score(candidate: Color): Triple<Int, Float, Float> {
        val contrasts = samples
            .map { background -> wcagContrastRatio(candidate, background) }
            .sorted()
        val passingSamples = contrasts.count { it >= WCAG_NORMAL_TEXT_MIN_CONTRAST }
        val lowerDecile = contrasts[((contrasts.lastIndex) * 0.1f).toInt()]
        val average = contrasts.average().toFloat()
        return Triple(passingSamples, lowerDecile, average)
    }

    val blackScore = score(Color.Black)
    val whiteScore = score(Color.White)
    return when {
        blackScore.first != whiteScore.first -> if (blackScore.first > whiteScore.first) Color.Black else Color.White
        blackScore.second != whiteScore.second -> if (blackScore.second > whiteScore.second) Color.Black else Color.White
        else -> if (blackScore.third >= whiteScore.third) Color.Black else Color.White
    }
}

/** WCAG 2.x relative luminance for an sRGB color. */
internal fun wcagRelativeLuminance(color: Color): Float {
    fun linearize(channel: Float): Double {
        val value = channel.coerceIn(0f, 1f).toDouble()
        return if (value <= WCAG_SRGB_LINEAR_THRESHOLD) {
            value / 12.92
        } else {
            ((value + 0.055) / 1.055).pow(2.4)
        }
    }

    return (
        0.2126 * linearize(color.red) +
            0.7152 * linearize(color.green) +
            0.0722 * linearize(color.blue)
        ).toFloat()
}

/** WCAG contrast ratio: (lighter relative luminance + 0.05) / (darker + 0.05). */
internal fun wcagContrastRatio(foreground: Color, background: Color): Float {
    val opaqueBackground = background.copy(alpha = 1f)
    val effectiveForeground = foreground.compositeOver(opaqueBackground)
    val foregroundLuminance = wcagRelativeLuminance(effectiveForeground)
    val backgroundLuminance = wcagRelativeLuminance(opaqueBackground)
    val lighter = max(foregroundLuminance, backgroundLuminance)
    val darker = min(foregroundLuminance, backgroundLuminance)
    return ((lighter + WCAG_DARK_OFFSET) / (darker + WCAG_DARK_OFFSET)).toFloat()
}
