package me.rerere.rikkahub.ui.components.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.ui.theme.MicaPalette
import me.rerere.rikkahub.ui.theme.rememberMicaPalette
import androidx.compose.ui.graphics.Shape
import androidx.compose.material3.MaterialTheme

@Composable
internal fun MicaBackdrop(modifier: Modifier = Modifier) {
    val palette = rememberMicaPalette()
    Box(modifier.background(Brush.verticalGradient(listOf(palette.baseTop, palette.baseBottom))))
}

internal fun AppearanceBackgroundSpec?.hasMicaBackground(): Boolean = this != null &&
    opacity > 0f && (!background.isNullOrBlank() || useGradientBackground)

internal fun micaSurfaceOpacity(opacity: Float, hasBackground: Boolean): Float =
    if (hasBackground) finiteAppearanceValue(opacity, 0f, 1f, .68f) else 1f

/** Mica supplies a tint, not a replacement wallpaper. Independent windows replay
 * the selected background; inline controls share it without drawing another image. */
@Composable
internal fun MicaMaterialSurface(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.medium,
    foundation: Boolean = false,
    opacity: Float = .68f,
    backgroundRendering: IsolatedBackgroundRendering = IsolatedBackgroundRendering.SHARED_PAGE,
    content: @Composable BoxScope.() -> Unit,
) {
    val palette = rememberMicaPalette()
    val inherited = LocalAppearanceBackground.current
    val alpha = micaSurfaceOpacity(opacity, inherited.hasMicaBackground())
    val scheme = me.rerere.rikkahub.ui.theme.micaColorScheme(MaterialTheme.colorScheme, palette)
        .copy(background = if (foundation) palette.baseTop else palette.card)
    Surface(modifier, shape = shape, color = Color.Transparent, contentColor = palette.foreground,
        border = BorderStroke(1.dp, palette.border.copy(alpha = .45f))) {
        CompositionLocalProvider(
            me.rerere.rikkahub.ui.theme.LocalMicaPalette provides palette,
            me.rerere.rikkahub.ui.theme.LocalBaseThemeColorScheme provides scheme,
            me.rerere.rikkahub.ui.theme.LocalBackgroundBaseColorScheme provides scheme,
        ) {
            MaterialTheme(colorScheme = scheme) {
                // Share the existing wallpaper/readability implementation. Mica
                // never enables blur, including when nested inside glass surfaces.
                IsolatedAppearanceSurface(
                    style = me.rerere.rikkahub.data.datastore.BackgroundSurfaceStyle.TRANSLUCENT,
                    surfaceOpacity = alpha, blurRadius = 0f, shape = shape,
                    backgroundRendering = backgroundRendering, effectsEnabled = false,
                ) {
                    CompositionLocalProvider(
                        me.rerere.rikkahub.ui.context.LocalPageSurfaceStyle provides me.rerere.rikkahub.data.datastore.BackgroundSurfaceStyle.MICA,
                        LocalGlassBackdrop provides null, LocalGlobalBackgroundHazeState provides null,
                    ) { content() }
                }
            }
        }
    }
}

/** One opaque foundation shared by the cards above it. No wallpaper replay or blur loop. */
@Composable
internal fun MicaBaseSurface(
    palette: MicaPalette,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    CompositionLocalProvider(LocalContentColor provides palette.foreground) {
        Box(
            modifier.clip(RoundedCornerShape(20.dp))
                .background(Brush.verticalGradient(listOf(palette.baseTop, palette.baseBottom))),
            content = content,
        )
    }
}

@Composable
internal fun MicaCard(
    palette: MicaPalette,
    modifier: Modifier = Modifier,
    raised: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = if (raised) palette.raisedCard else palette.card,
        contentColor = palette.foreground,
        border = BorderStroke(1.dp, palette.border.copy(alpha = .45f)),
        tonalElevation = 0.dp,
        shadowElevation = if (raised) 2.dp else 0.dp,
    ) {
        Column(content = content)
    }
}
