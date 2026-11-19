package me.rerere.rikkahub.ui.components.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

internal fun overlayHeightConstraints(
    incoming: Constraints,
    windowHeight: Int,
    fraction: Float,
    verticalMargin: Int,
): Constraints {
    if (windowHeight <= 0 && !incoming.hasBoundedHeight) return incoming
    val available = minOf(incoming.maxHeight, windowHeight.takeIf { it > 0 } ?: incoming.maxHeight)
    val ratio = fraction.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 1f
    val maximum = (available * ratio - verticalMargin.coerceAtLeast(0)).roundToInt().coerceIn(0, available)
    return incoming.copy(minHeight = incoming.minHeight.coerceAtMost(maximum), maxHeight = maximum)
}

/** Dialog/sheet hosts consume system/IME insets; intersect their actual remaining constraints. */
@Composable
internal fun Modifier.windowBoundedHeight(fraction: Float = 1f, verticalMargin: Dp = 0.dp): Modifier {
    val windowHeight = LocalWindowInfo.current.containerSize.height
    val margin = with(LocalDensity.current) { verticalMargin.roundToPx() }
    return layout { measurable, constraints ->
        val child = measurable.measure(overlayHeightConstraints(constraints, windowHeight, fraction, margin))
        layout(child.width, child.height) { child.placeRelative(0, 0) }
    }
}

@Composable
internal fun currentWindowWidthDp(): Int = with(LocalDensity.current) {
    LocalWindowInfo.current.containerSize.width.toDp().value.roundToInt().coerceAtLeast(0)
}
