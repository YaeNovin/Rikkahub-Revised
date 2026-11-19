package me.rerere.rikkahub.ui.components.ui

import me.rerere.rikkahub.data.datastore.BackgroundSurfaceStyle

internal data class MaterialSurfacePlan(val opacity: Float, val blurRadius: Float, val grainEnabled: Boolean)

/** Material preferences are independent of wallpaper availability. Only sampling and
 * device capabilities determine whether an optical pass can actually run. */
internal fun resolveMaterialSurface(
    material: BackgroundSurfaceStyle,
    opacity: Float,
    blurRadius: Float,
    effectsEnabled: Boolean,
    blurSupported: Boolean,
    sourceAvailable: Boolean,
    maxBlur: Float,
    powerSave: Boolean = false,
): MaterialSurfacePlan {
    val alpha = if (material.opaqueMaterial) 1f else finiteAppearanceValue(opacity, 0f, 1f, 1f)
    val effects = effectsEnabled && !powerSave
    val limit = maxBlur.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
    val blur = if (effects && material.usesBlur && blurSupported && sourceAvailable && alpha < 1f)
        finiteAppearanceValue(blurRadius, 0f, limit, 0f) else 0f
    return MaterialSurfacePlan(alpha, blur, material == BackgroundSurfaceStyle.ACRYLIC && effects && alpha > 0f)
}
