package me.rerere.rikkahub.ui.components.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.model.parameters
import me.rerere.rikkahub.ui.context.LocalSettings

/** All surfaces share the same settings. No fixed blue overlay is applied. */
@Composable
fun liquidGlassBorder(strength: Float = 1f): BorderStroke {
    val parameters = LocalSettings.current.advancedAppearanceSetting.liquidGlass.parameters()
    val scale = LocalAdvancedAppearanceCapabilities.current.limitOpticalStrength(strength)
    return BorderStroke(parameters.edgeWidth.dp, Brush.linearGradient(listOf(
        Color.White.copy(alpha = parameters.highlight * scale),
        Color.White.copy(alpha = parameters.highlight * scale * .2f),
        Color.Transparent,
        Color.Black.copy(alpha = parameters.innerShadow * scale),
    )))
}

fun liquidGlassContainerColor(baseColor: Color, opacity: Float): Color =
    baseColor.copy(alpha = finiteAppearanceValue(opacity, 0f, 1f, 1f))

internal fun liquidGlassBlurRadius(requestedRadius: Float): Float =
    finiteAppearanceValue(requestedRadius, 0f, 24f, 0f)

/** Shape-clipped edge light and inner shading; refraction is applied to a separate background layer. */
@Composable
fun LiquidGlassSurfaceLayers(
    modifier: Modifier = Modifier,
    strength: Float = 1f,
    shape: Shape = MaterialTheme.shapes.extraSmall,
    drawTint: Boolean = true,
    drawEdges: Boolean = true,
) {
    val params = LocalSettings.current.advancedAppearanceSetting.liquidGlass.parameters()
    val caps = LocalAdvancedAppearanceCapabilities.current
    val backdrop = LocalGlassBackdrop.current
    val busy = LocalGlassBusy.current || backdrop?.interacting == true || LocalAppearanceBackground.current?.gradientInteractionInProgress == true
    val reduction = resolveGlassProfile(LocalSettings.current.advancedAppearanceSetting.liquidGlass, caps.sdkInt,
        GlassRefractionRuntime.failed, busy, LocalGlassPowerSave.current, 0f, -1, caps.maxLiveBlurRadius).opticalScale
    val scale = caps.limitOpticalStrength(strength) * reduction
    val primary = MaterialTheme.colorScheme.primary
    val tint = remember(primary, params.tint, scale) { primary.copy(alpha = params.tint * scale) }
    Box(modifier.drawWithCache {
        if (size.width <= 0f || size.height <= 0f) return@drawWithCache onDrawBehind { }
        val outline = shape.createOutline(size, layoutDirection, this)
        val highlight = Brush.linearGradient(listOf(Color.White.copy(alpha = params.highlight * scale), Color.Transparent, Color.Black.copy(alpha = params.innerShadow * scale)))
        val innerShade = Brush.linearGradient(listOf(Color.Transparent, Color.Black.copy(alpha = params.innerShadow * scale)))
        onDrawBehind {
            if (size.width <= 0f || size.height <= 0f) return@onDrawBehind
            if (drawTint) drawOutline(outline, tint)
            // Stroke clipping keeps both light and shadow inside the surface.
            if (drawEdges) {
                drawOutline(outline, innerShade, style = Stroke(params.edgeWidth.dp.toPx() * 4f))
                drawOutline(outline, highlight, style = Stroke(params.edgeWidth.dp.toPx() * 2f))
            }
        }
    })
}
