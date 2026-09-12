package me.rerere.rikkahub.ui.components.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import dev.chrisbanes.haze.blur.HazeBlurStyle
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.ui.theme.wcagContrastRatio
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

internal fun finiteAppearanceValue(value: Float, min: Float, max: Float, fallback: Float): Float =
    if (value.isFinite()) value.coerceIn(min, max) else fallback.coerceIn(min, max)

/** Callers paint one tint layer themselves; blur must not add another theme tint. */
internal fun backgroundOnlyBlurStyle(radius: Float): HazeBlurStyle = HazeBlurStyle.then {
    blurRadius(finiteAppearanceValue(radius, 0f, 40f, 0f).dp)
    backgroundColor(Color.Transparent)
    colorEffects(emptyList())
    noiseFactor(0f)
}

/** Once a tint guarantees readable text for any image, use that foreground. */
internal fun tintedSurfaceForeground(tint: Color, alpha: Float, imageForeground: Color, samples: List<Color>? = null, seedArgb: Int? = null): Color {
    val overlay = tint.copy(alpha = finiteAppearanceValue(alpha, 0f, 1f, 1f))
    if (!samples.isNullOrEmpty()) {
        val painted = samples.map { overlay.compositeOver(it) }
        if (seedArgb != null) return me.rerere.rikkahub.ui.theme.textForegroundFor(painted, seedArgb, imageForeground)
        return listOf(Color.Black, Color.White).maxBy { candidate -> painted.minOf { wcagContrastRatio(candidate, it) } }
    }
    val backgrounds = listOf(overlay.compositeOver(Color.Black), overlay.compositeOver(Color.White))
    if (seedArgb != null) return me.rerere.rikkahub.ui.theme.textForegroundFor(backgrounds, seedArgb, imageForeground)
    val candidate = listOf(Color.Black, Color.White).maxBy { foreground -> backgrounds.minOf { wcagContrastRatio(foreground, it) } }
    return if (backgrounds.minOf { wcagContrastRatio(candidate, it) } >= 4.5f || imageForeground == Color.Unspecified) candidate else imageForeground
}

/** Streaming text and scrolling must not repeatedly rebuild HCT colors for unchanged surfaces. */
@Composable
internal fun rememberTintedSurfaceForeground(
    tint: Color,
    alpha: Float,
    imageForeground: Color,
    samples: List<Color>? = null,
    seedArgb: Int? = null,
): Color {
    val mode = me.rerere.rikkahub.ui.theme.LocalTextColorMode.current
    return remember(tint, alpha, imageForeground, samples, seedArgb, mode) {
        if (mode == me.rerere.rikkahub.data.datastore.TextColorMode.THEME) imageForeground
        else tintedSurfaceForeground(tint, alpha, imageForeground, samples, seedArgb)
    }
}

internal fun gradientMotionPhase(elapsedMillis: Long, periodMillis: Long, frequency: Double = 1.0): Float {
    val cycles = elapsedMillis.coerceAtLeast(0L).toDouble() / periodMillis * frequency
    return ((cycles % 1.0) * 2.0 * PI).toFloat()
}

internal fun Size.gradientAxisPoint(progress: Float, angle: Float): Offset {
    val center = Offset(width / 2f, height / 2f)
    val radians = angle * PI.toFloat() / 180f
    val direction = Offset(sin(radians), cos(radians))
    val extent = (abs(direction.x) * width + abs(direction.y) * height) / 2f
    return center + direction * (extent * (progress * 2f - 1f))
}

internal fun Size.gradientPosition(point: Offset, angle: Float): Float {
    val start = gradientAxisPoint(0f, angle)
    val axis = gradientAxisPoint(1f, angle) - start
    val denominator = axis.x * axis.x + axis.y * axis.y
    return if (denominator <= 0f) .5f else (((point.x - start.x) * axis.x + (point.y - start.y) * axis.y) / denominator).coerceIn(0f, 1f)
}
