package me.rerere.rikkahub.ui.components.ui

import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import kotlin.math.min
import kotlin.math.roundToInt

internal data class MenuViewportLimits(val maxWidth: Int, val maxHeight: Int, val preferredWidth: Int) {
    fun constrain(incoming: Constraints): Constraints {
        val width = min(incoming.maxWidth, maxWidth)
        val height = min(incoming.maxHeight, maxHeight)
        return Constraints(
            minWidth = incoming.minWidth.coerceAtMost(width), maxWidth = width,
            minHeight = incoming.minHeight.coerceAtMost(height), maxHeight = height,
        )
    }
}

internal fun menuViewportLimits(
    window: IntSize,
    density: Float,
    imeBottom: Int,
    maxHeightDp: Float,
): MenuViewportLimits {
    val safeDensity = density.takeIf { it.isFinite() && it > 0f }?.coerceAtMost(10f) ?: 1f
    val margin = (16 * safeDensity).roundToInt()
    // Keep both dimensions representable even under intrinsic probes or transient window sizes.
    val width = (window.width.toLong() - 2L * margin).coerceIn(1, 8190).toInt()
    val requestedHeight = ((maxHeightDp.takeIf { it.isFinite() && it > 16f } ?: 320f) - 16f)
        .coerceAtMost(8190f / safeDensity) * safeDensity
    val availableHeight = (window.height.toLong() - imeBottom.coerceAtLeast(0) - 2L * margin)
        .coerceIn(1, 8190).toInt()
    return MenuViewportLimits(
        maxWidth = width,
        maxHeight = min(requestedHeight.roundToInt().coerceAtLeast(1), availableHeight),
        preferredWidth = min((280 * safeDensity).roundToInt(), width),
    )
}

/**
 * Material menus query intrinsic width before regular layout. Do not send those synthetic
 * unbounded probes through a layered background containing the entire option list.
 */
internal class MenuViewportMeasurePolicy(private val limits: MenuViewportLimits) : MeasurePolicy {
    override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
        val placeable = measurables.single().measure(limits.constrain(constraints))
        return layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
    }
    override fun IntrinsicMeasureScope.minIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int) = limits.preferredWidth
    override fun IntrinsicMeasureScope.maxIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int) = limits.preferredWidth
    override fun IntrinsicMeasureScope.minIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int) = limits.maxHeight
    override fun IntrinsicMeasureScope.maxIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int) = limits.maxHeight
}
