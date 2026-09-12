package me.rerere.rikkahub.ui.components.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.model.parameters
import me.rerere.rikkahub.ui.context.LocalSettings
import dev.chrisbanes.haze.blur.hazeBlur

/** Haze tracks the shared source as the component scrolls; effects apply only to that source. */
@Composable
internal fun GlassBackdropLayer(blurRadius: Float, shape: Shape, glass: Boolean, modifier: Modifier = Modifier,
    sourceOverride: dev.chrisbanes.haze.HazeState? = null) {
    val backdrop = LocalGlassBackdrop.current ?: return
    val hazeSource = sourceOverride ?: LocalGlobalBackgroundHazeState.current ?: return
    val caps = LocalAdvancedAppearanceCapabilities.current
    val options = LocalSettings.current.advancedAppearanceSetting.liquidGlass
    val params = options.parameters()
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val hardware = LocalView.current.isHardwareAccelerated
    var origin by remember { mutableStateOf(Offset.Zero) }
    var dimensions by remember { mutableStateOf(IntSize.Zero) }
    val areaDp = dimensions.width.toFloat() * dimensions.height / (density.density * density.density)
    val token = remember { Any() }
    val visible = glassSurfaceVisible(origin, dimensions, backdrop.origin, backdrop.size)
    val participates = hardware && areaDp > 0f && visible && caps.supportsRealtimeBlur &&
        needsGlassRenderSlot(glass, blurRadius, caps.sdkInt, options.renderer, params.refraction, areaDp, GlassRefractionRuntime.failed)
    val potentialRefraction = participates && glass && areaDp <= MAX_REFRACTION_AREA_DP && caps.sdkInt >= 33 && params.refraction > 0f &&
        options.renderer != me.rerere.rikkahub.data.model.LiquidGlassRenderer.STANDARD
    DisposableEffect(participates) {
        if (participates) GlassRefractionBudget.surfaces.add(token)
        onDispose { GlassRefractionBudget.surfaces.remove(token) }
    }
    val busy = LocalGlassBusy.current || backdrop.interacting || LocalAppearanceBackground.current?.gradientInteractionInProgress == true || WindowInsets.isImeVisible
    val profile = resolveGlassProfile(options, caps.sdkInt, GlassRefractionRuntime.failed, busy,
        LocalGlassPowerSave.current, areaDp, GlassRefractionBudget.surfaces.indexOf(token), caps.maxLiveBlurRadius)
    val size = Size(dimensions.width.toFloat(), dimensions.height.toFloat())
    val outline = remember(shape, size, density, direction) { shape.createOutline(size, direction, density) }
    val renderer = remember(potentialRefraction, options.renderer, GlassRefractionRuntime.failed) {
        if (potentialRefraction && !GlassRefractionRuntime.failed) createGlassRefractionRenderer() else null
    }
    val effect = remember(renderer, profile, size, outline, params, density) {
        if (glass && profile.refractionEnabled && params.refraction > 0f && outline !is Outline.Generic) {
            val radii = if (outline is Outline.Rounded) with(outline.roundRect) { floatArrayOf(topLeftCornerRadius.x, topRightCornerRadius.x, bottomRightCornerRadius.x, bottomLeftCornerRadius.x) } else FloatArray(4)
            renderer?.effect(size, radii, with(density) { (params.edgeWidth * 12f).dp.toPx() }, with(density) { params.refraction.dp.toPx() })
        } else null
    }
    val blur = if (GlassRefractionBudget.surfaces.indexOf(token) in 0 until MAX_REFRACTION_SURFACES) caps.limitLiveBlur(blurRadius).coerceAtMost(profile.blurLimit) else 0f
    Box(modifier
        .onGloballyPositioned { origin = it.positionOnScreen(); dimensions = it.size }
        .clip(shape)
        .graphicsLayer { renderEffect = effect }
        .then(if (blur > 0f || effect != null) Modifier.hazeBlur(
            input = dev.chrisbanes.haze.HazeInput.Sources(hazeSource),
            style = backgroundOnlyBlurStyle(if (effect != null) blur.coerceAtLeast(1f) else blur),
        ) else Modifier))
}
