package me.rerere.rikkahub.ui.components.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import me.rerere.rikkahub.data.datastore.BackgroundSurfaceStyle
import me.rerere.rikkahub.ui.context.LocalGlobalBackgroundActive
import me.rerere.rikkahub.ui.context.LocalPageSurfaceStyle
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.theme.CustomColors

/** Same inline material policy as CardGroup: one tint, shared backdrop, no wallpaper replay. */
@Composable
internal fun AppearanceCard(
    modifier: Modifier = Modifier,
    outlined: Boolean = false,
    colors: CardColors = CustomColors.cardColorsOnSurfaceContainer,
    shape: Shape = MaterialTheme.shapes.medium,
    content: @Composable ColumnScope.() -> Unit,
) {
    val glass = LocalGlobalBackgroundActive.current && LocalPageSurfaceStyle.current == BackgroundSurfaceStyle.LIQUID_GLASS
    val inside = LocalInsideGlassSurface.current
    Box(modifier.fillMaxWidth().clip(shape)) {
        if (glass && !inside) InlineBackdropBlur(
            blurRadius = LocalSettings.current.advancedAppearanceSetting.pageLiquidGlassBlurRadius,
            shape = shape, modifier = Modifier.matchParentSize(),
        )
        if (glass) LiquidGlassSurfaceLayers(Modifier.matchParentSize(), strength = .5f, shape = shape, drawEdges = false)
        CompositionLocalProvider(LocalInsideGlassSurface provides (inside || glass)) {
            if (outlined) OutlinedCard(Modifier.fillMaxWidth(), shape = shape, colors = colors, content = content)
            else Card(Modifier.fillMaxWidth(), shape = shape, colors = colors, content = content)
        }
        if (glass) LiquidGlassSurfaceLayers(Modifier.matchParentSize(), strength = .5f, shape = shape, drawTint = false)
    }
}

internal fun appearanceTabContainer(backgroundActive: Boolean, defaultColor: Color): Color =
    if (backgroundActive) Color.Transparent else defaultColor

/** An overlay already draws its background; tabs must not paint a second opaque slab. */
@Composable
internal fun AppearancePrimaryTabRow(selectedTabIndex: Int, tabs: @Composable () -> Unit) {
    PrimaryTabRow(selectedTabIndex,
        containerColor = appearanceTabContainer(LocalGlobalBackgroundActive.current, MaterialTheme.colorScheme.surface),
        contentColor = LocalContentColor.current, tabs = tabs)
}
