package me.rerere.rikkahub.ui.context

/** Surface roles share one normalization policy so opacity has the same meaning everywhere. */
enum class AppearanceSurfaceRole {
    CARD,
    TOP_BAR,
    INPUT,
    DOCK,
}

data class AppearanceSurfaceOpacityPolicy(
    val card: Float = 1f,
    val topBar: Float = 1f,
    val input: Float = 1f,
    val dock: Float = 1f,
)

private const val MIN_READABLE_SURFACE_ALPHA = 0.35f

internal fun normalizeAppearanceSurfaceOpacity(
    role: AppearanceSurfaceRole,
    value: Float,
): Float = me.rerere.rikkahub.ui.components.ui.finiteAppearanceValue(value,
    if (role == AppearanceSurfaceRole.INPUT || role == AppearanceSurfaceRole.DOCK) 0f else MIN_READABLE_SURFACE_ALPHA, 1f, 1f)

internal fun AppearanceSurfaceOpacityPolicy.forRole(role: AppearanceSurfaceRole): Float = when (role) {
    AppearanceSurfaceRole.CARD -> card
    AppearanceSurfaceRole.TOP_BAR -> topBar
    AppearanceSurfaceRole.INPUT -> input
    AppearanceSurfaceRole.DOCK -> dock
}

internal fun appearanceSurfaceOpacityPolicy(
    cardOpacity: Float,
    topBarOpacity: Float,
    inputOpacity: Float,
    dockOpacity: Float,
    cardBackgroundActive: Boolean,
    cardMaterial: me.rerere.rikkahub.data.datastore.BackgroundSurfaceStyle = me.rerere.rikkahub.data.datastore.BackgroundSurfaceStyle.TRANSLUCENT,
): AppearanceSurfaceOpacityPolicy = AppearanceSurfaceOpacityPolicy(
    // Card material owns opacity, independently of whether the page uses an image.
    // The wallpaper-only top-bar blur path retains its solid no-source fallback.
    card = normalizeAppearanceSurfaceOpacity(
        AppearanceSurfaceRole.CARD,
        if (cardMaterial.opaqueMaterial) 1f else cardOpacity,
    ),
    topBar = normalizeAppearanceSurfaceOpacity(
        AppearanceSurfaceRole.TOP_BAR,
        if (cardBackgroundActive && !cardMaterial.opaqueMaterial) topBarOpacity else 1f,
    ),
    input = normalizeAppearanceSurfaceOpacity(AppearanceSurfaceRole.INPUT, inputOpacity),
    // Dock is part of the composer. Ignore the retired independent dock tint.
    dock = normalizeAppearanceSurfaceOpacity(AppearanceSurfaceRole.DOCK, inputOpacity),
)
