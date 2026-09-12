package me.rerere.rikkahub.ui.components.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape

/** Page surfaces sample the background source via Haze, never replay a wallpaper layer. */
@Composable
internal fun InlineBackdropBlur(blurRadius: Float, shape: Shape, modifier: Modifier = Modifier, glass: Boolean = true,
    sourceOverride: dev.chrisbanes.haze.HazeState? = null) {
    if (LocalInsideGlassSurface.current) return
    GlassBackdropLayer(blurRadius = blurRadius, shape = shape, glass = glass, modifier = modifier, sourceOverride = sourceOverride)
}
