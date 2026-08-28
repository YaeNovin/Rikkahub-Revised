package me.rerere.rikkahub.ui.components.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.blur.material3.Material3
import me.rerere.rikkahub.ui.theme.LocalDarkMode

@Composable
fun liquidGlassBorder(strength: Float = 1f): BorderStroke {
    val safeStrength = LocalAdvancedAppearanceCapabilities.current
        .limitOpticalStrength(strength)
    val darkMode = LocalDarkMode.current
    val scheme = MaterialTheme.colorScheme
    val absorption = if (darkMode) Color(0xFF02060D) else scheme.primary
    return BorderStroke(
        width = 1.dp,
        brush = Brush.linearGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.76f * safeStrength),
                Color.White.copy(alpha = 0.20f * safeStrength),
                scheme.primary.copy(alpha = 0.28f * safeStrength),
                Color.Transparent,
                absorption.copy(alpha = 0.22f * safeStrength),
            )
        ),
    )
}

fun liquidGlassContainerColor(baseColor: Color, opacity: Float): Color =
    baseColor.copy(alpha = opacity.coerceIn(0f, 1f))

/** Liquid glass keeps refraction/highlight layers even when blur is disabled. */
internal fun liquidGlassBlurRadius(requestedRadius: Float): Float =
    requestedRadius.coerceIn(0f, 24f)

/**
 * Shared live-background treatment for the chat input and its independent Dock.
 * The blur is painted on a background-only child so Haze never captures the
 * text field or toolbar content into a rectangular intermediate layer.
 */
@Composable
fun LiveLiquidGlassSurface(
    enabled: Boolean,
    hazeState: HazeState,
    blurRadius: Float,
    opacity: Float,
    shape: Shape,
    modifier: Modifier = Modifier,
    strength: Float = 0.68f,
    borderStrength: Float = 0.48f,
    content: @Composable BoxScope.() -> Unit,
) {
    val capabilities = LocalAdvancedAppearanceCapabilities.current
    if (!enabled || !capabilities.supportsRealtimeBlur) {
        Box(modifier = modifier, content = content)
        return
    }

    val safeBlurRadius = capabilities.limitLiveBlur(liquidGlassBlurRadius(blurRadius))
    val hazeStyle = HazeBlurStyle.Material3 {
        blurRadius(safeBlurRadius.dp)
    }
    val baseColor = MaterialTheme.colorScheme.surfaceContainerLow
    val refraction = liquidGlassRefractionBrush(strength)
    Box(
        modifier = modifier
            .shadow(elevation = 1.dp, shape = shape, clip = false)
            .clip(shape),
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .clip(shape)
                .then(
                    if (safeBlurRadius > 0f) {
                        Modifier.hazeBlur(
                            input = HazeInput.Sources(hazeState),
                            style = hazeStyle,
                        )
                    } else {
                        Modifier
                    }
                )
                .background(liquidGlassContainerColor(baseColor, opacity))
                .background(refraction),
        )
        content()
        Box(
            modifier = Modifier
                .matchParentSize()
                .border(liquidGlassBorder(borderStrength), shape),
        )
    }
}

/**
 * Dock-only glass surface. The blur is painted on a background-only child (clipped
 * to the shape) and the toolbar content is drawn as a separate layer on top, so
 * Haze never captures the toolbar content into a rectangular intermediate strip.
 * The content is kept out of the blurred/offscreen layer so the dock reads as a
 * clean rounded glass pill behind the tools instead of a blurred rectangle.
 */
@Composable
fun LiveDockGlassSurface(
    enabled: Boolean,
    hazeState: HazeState,
    blurRadius: Float,
    opacity: Float,
    shape: Shape,
    modifier: Modifier = Modifier,
    strength: Float = 0.68f,
    borderStrength: Float = 0.48f,
    content: @Composable BoxScope.() -> Unit,
) {
    val capabilities = LocalAdvancedAppearanceCapabilities.current
    if (!enabled || !capabilities.supportsRealtimeBlur) {
        Box(modifier = modifier, content = content)
        return
    }

    val safeBlurRadius = capabilities.limitLiveBlur(liquidGlassBlurRadius(blurRadius))
    val hazeStyle = HazeBlurStyle.Material3 {
        blurRadius(safeBlurRadius.dp)
    }
    val baseColor = MaterialTheme.colorScheme.surfaceContainerLow
    val refraction = liquidGlassRefractionBrush(strength)
    Box(
        modifier = modifier
            .shadow(elevation = 1.dp, shape = shape, clip = false)
            .clip(shape),
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .clip(shape)
                .then(
                    if (safeBlurRadius > 0f) {
                        Modifier.hazeBlur(
                            input = HazeInput.Sources(hazeState),
                            style = hazeStyle,
                        )
                    } else {
                        Modifier
                    }
                )
                .background(liquidGlassContainerColor(baseColor, opacity))
                .background(refraction),
        )
        content()
        Box(
            modifier = Modifier
                .matchParentSize()
                .border(liquidGlassBorder(borderStrength), shape),
        )
    }
}

@Composable
fun liquidGlassRefractionBrush(strength: Float = 1f): Brush {
    val safeStrength = LocalAdvancedAppearanceCapabilities.current
        .limitOpticalStrength(strength)
    val darkMode = LocalDarkMode.current
    val scheme = MaterialTheme.colorScheme
    val ambientTint = if (darkMode) Color(0xFF7BA7D9) else scheme.primary
    val absorption = if (darkMode) Color(0xFF02060D) else scheme.primary
    return Brush.linearGradient(
        colors = listOf(
            Color.White.copy(alpha = 0.20f * safeStrength),
            Color.White.copy(alpha = 0.06f * safeStrength),
            Color.Transparent,
            ambientTint.copy(alpha = 0.08f * safeStrength),
            absorption.copy(alpha = 0.22f * safeStrength),
        )
    )
}

@Composable
private fun liquidGlassAmbientHighlightBrush(strength: Float): Brush {
    val safeStrength = LocalAdvancedAppearanceCapabilities.current
        .limitOpticalStrength(strength)
    return Brush.radialGradient(
        colors = listOf(
            Color.White.copy(alpha = 0.12f * safeStrength),
            Color.White.copy(alpha = 0.03f * safeStrength),
            Color.Transparent,
        )
    )
}

/**
 * Paint the optical layers above a blurred background. The caller clips the
 * containing surface, so every layer follows the same shape.
 */
@Composable
fun LiquidGlassSurfaceLayers(
    modifier: Modifier = Modifier,
    strength: Float = 1f,
) {
    val safeStrength = strength.coerceIn(0f, 1f)
    val refractionBrush = liquidGlassRefractionBrush(safeStrength)
    val ambientHighlightBrush = liquidGlassAmbientHighlightBrush(safeStrength)

    Box(modifier = modifier) {
        // A diagonal refraction/absorption ramp gives the surface visible
        // thickness instead of a uniform translucent wash.
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(refractionBrush)
        )
        // A broad, low-alpha radial highlight simulates reflected ambient light
        // without turning the content into a white card.
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(ambientHighlightBrush)
        )
    }
}
