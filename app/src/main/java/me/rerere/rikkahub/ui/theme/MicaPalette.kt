package me.rerere.rikkahub.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import me.rerere.material3.hctColorFromArgb
import me.rerere.material3.hctColorFromComponents

/** App-owned Mica-inspired colors: an opaque foundation, not a live backdrop effect. */
@Immutable
internal data class MicaPalette(
    val baseTop: Color,
    val baseBottom: Color,
    val card: Color,
    val raisedCard: Color,
    val border: Color,
    val foreground: Color,
    val secondaryForeground: Color,
    val accent: Color,
)

internal fun micaPalette(
    theme: ColorScheme,
    dark: Boolean,
    backgroundSamples: List<Color> = emptyList(),
    tintStrength: Float = .45f,
): MicaPalette {
    val samples = backgroundSamples.filter { it != Color.Unspecified && it.alpha > 0f }
    // Reuse the existing hue grouping so a single colorful pixel cannot dominate the material.
    val seed = if (samples.isEmpty()) theme.primary else {
        backgroundTextSeed(samples)?.let(::Color) ?: Color(
            red = samples.map { it.red }.average().toFloat(),
            green = samples.map { it.green }.average().toFloat(),
            blue = samples.map { it.blue }.average().toFloat(),
        )
    }
    val hct = hctColorFromArgb(seed.toArgb())
    val strength = tintStrength.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: .45f
    val chroma = hct.chroma.coerceAtMost(28.0) * strength
    fun tone(value: Double, colorScale: Double = 1.0) =
        Color(hctColorFromComponents(hct.hue, chroma * colorScale, value).argb)

    val top = tone(if (dark) 13.0 else 91.0)
    val bottom = tone(if (dark) 17.0 else 94.0, .7)
    val card = tone(if (dark) 23.0 else 98.0, .45)
    val raised = tone(if (dark) 29.0 else 100.0, .25)
    val surfaces = listOf(top, bottom, card, raised)
    return MicaPalette(
        baseTop = top,
        baseBottom = bottom,
        card = card,
        raisedCard = raised,
        border = tone(if (dark) 38.0 else 79.0, .3),
        foreground = contrastingTextColor(theme.onSurface, surfaces),
        secondaryForeground = contrastingTextColor(theme.onSurfaceVariant, surfaces),
        accent = contrastingTextColor(theme.primary, surfaces),
    )
}
