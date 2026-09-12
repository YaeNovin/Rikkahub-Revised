package me.rerere.rikkahub.ui.components.ui

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass

@Stable
class GlassBackdrop {
    internal var layer by mutableStateOf<GraphicsLayer?>(null)
    internal var origin by mutableStateOf(Offset.Zero)
    internal var size by mutableStateOf(IntSize.Zero)
    internal var screenScale by mutableStateOf(Offset(1f, 1f))
    var interacting by mutableStateOf(false)
}

internal object GlassRefractionBudget { val surfaces = mutableStateListOf<Any>() }

val LocalGlassBackdrop = compositionLocalOf<GlassBackdrop?> { null }
internal val LocalInsideGlassSurface = compositionLocalOf { false }

internal fun shouldPaintGlassBackdrop(isolated: Boolean, blurRadius: Float, refractionActive: Boolean): Boolean =
    isolated || blurRadius > 0f || refractionActive

@Composable
fun rememberGlassBackdrop(): GlassBackdrop = remember { GlassBackdrop() }

/** Only the background is recorded. Never attach this modifier to a page containing text or glass. */
@Composable
fun Modifier.glassBackdropSource(backdrop: GlassBackdrop?, visible: Boolean = true): Modifier {
    if (backdrop == null) return this
    val layer = rememberGraphicsLayer()
    DisposableEffect(backdrop, layer) {
        onDispose { if (backdrop.layer === layer) { backdrop.layer = null; backdrop.size = IntSize.Zero } }
    }
    return onGloballyPositioned { coordinates ->
        backdrop.origin = coordinates.positionOnScreen()
        backdrop.size = coordinates.size
        backdrop.screenScale = glassScreenScale(backdrop.origin,
            coordinates.localToScreen(Offset(coordinates.size.width.toFloat(), 0f)),
            coordinates.localToScreen(Offset(0f, coordinates.size.height.toFloat())), coordinates.size)
        backdrop.layer = layer
    }.drawWithContent {
        layer.record { this@drawWithContent.drawContent() }
        if (visible) drawLayer(layer)
    }
}

internal fun glassSampleTranslation(sourceOrigin: Offset, targetOrigin: Offset): Offset = sourceOrigin - targetOrigin

internal fun glassScreenScale(origin: Offset, xAxis: Offset, yAxis: Offset, size: IntSize): Offset {
    fun safe(value: Float) = value.takeIf { it.isFinite() && it > .001f } ?: 1f
    return Offset(safe((xAxis.x - origin.x) / size.width.coerceAtLeast(1)), safe((yAxis.y - origin.y) / size.height.coerceAtLeast(1)))
}

internal data class GlassSamplingTransform(val translation: Offset, val scale: Offset)
internal fun glassSamplingTransform(sourceOrigin: Offset, sourceScale: Offset, targetOrigin: Offset, targetScale: Offset): GlassSamplingTransform {
    val offset = glassSampleTranslation(sourceOrigin, targetOrigin)
    return GlassSamplingTransform(Offset(offset.x / targetScale.x, offset.y / targetScale.y), Offset(sourceScale.x / targetScale.x, sourceScale.y / targetScale.y))
}

fun Modifier.trackGlassInteraction(backdrop: GlassBackdrop): Modifier = pointerInput(backdrop) {
    try {
        awaitPointerEventScope {
            while (true) backdrop.interacting = awaitPointerEvent(PointerEventPass.Final).changes.any { it.pressed }
        }
    } finally { backdrop.interacting = false }
}

internal fun glassSurfaceVisible(origin: Offset, size: IntSize, sourceOrigin: Offset, sourceSize: IntSize): Boolean =
    origin.x.isFinite() && origin.y.isFinite() && origin.x < sourceOrigin.x + sourceSize.width &&
        origin.y < sourceOrigin.y + sourceSize.height && origin.x + size.width > sourceOrigin.x && origin.y + size.height > sourceOrigin.y
