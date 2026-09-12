package me.rerere.rikkahub.ui.components.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import me.rerere.rikkahub.data.datastore.BackgroundSurfaceStyle
import me.rerere.rikkahub.data.datastore.MAX_GLOBAL_BACKGROUND_BLUR_RADIUS
import me.rerere.rikkahub.data.datastore.MIN_GLOBAL_BACKGROUND_BLUR_RADIUS
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.isGlobalBackgroundActive

@Composable
fun GlobalAppBackground(
    settings: Settings,
    modifier: Modifier = Modifier,
    captureOnly: Boolean = false,
) {
    if (!settings.isGlobalBackgroundActive()) return
    val appearance = settings.advancedAppearanceSetting
    val capabilities = LocalAdvancedAppearanceCapabilities.current
    val pageSurfaceStyle = capabilities.effectiveSurfaceStyle(appearance.pageSurfaceStyle)
    val background = appearance.globalBackground ?: return
    if (pageSurfaceStyle == BackgroundSurfaceStyle.OPAQUE && !captureOnly) return
    val pageBlurRadius = when (pageSurfaceStyle) {
        BackgroundSurfaceStyle.OPAQUE,
        BackgroundSurfaceStyle.TRANSLUCENT -> 0f
        BackgroundSurfaceStyle.FROSTED -> capabilities.limitBackgroundBlur(
            appearance.globalBackgroundBlurRadius.coerceIn(
                MIN_GLOBAL_BACKGROUND_BLUR_RADIUS,
                MAX_GLOBAL_BACKGROUND_BLUR_RADIUS,
            )
        )
        BackgroundSurfaceStyle.LIQUID_GLASS -> capabilities.limitLiveBlur(
            liquidGlassBlurRadius(appearance.pageLiquidGlassBlurRadius)
        )
    }
    Box(modifier = modifier.glassBackdropSource(LocalGlassBackdrop.current, visible = !captureOnly)) {
        BlurredBackgroundImage(
            background = background,
            opacity = appearance.globalBackgroundOpacity,
            blurRadius = pageBlurRadius,
            // Keep the same readability budget used by chat backgrounds. A
            // weaker global scrim made icons and secondary text disappear on
            // high-contrast images even though the foreground was selected
            // correctly.
            overlayTopAlpha = 0.28f,
            overlayBottomAlpha = 0.42f,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
fun GlobalGlassTheme(
    active: Boolean,
    surfaceOpacity: Float = 0.68f,
    style: BackgroundSurfaceStyle = BackgroundSurfaceStyle.TRANSLUCENT,
    content: @Composable () -> Unit,
) {
    if (!active || style == BackgroundSurfaceStyle.OPAQUE) {
        content()
        return
    }

    val baseScheme = MaterialTheme.colorScheme
    val safeSurfaceOpacity = normalizedPageSurfaceOpacity(surfaceOpacity)
    val glassScheme = remember(baseScheme, safeSurfaceOpacity, style) {
        val pageContainerLowAlpha = if (style == BackgroundSurfaceStyle.LIQUID_GLASS) {
            (safeSurfaceOpacity + 0.08f).coerceAtMost(0.92f)
        } else {
            1f
        }
        baseScheme.copy(
            background = baseScheme.background.copy(alpha = 0.06f),
            surface = baseScheme.surface.copy(alpha = safeSurfaceOpacity),
            surfaceDim = baseScheme.surfaceDim.copy(alpha = (safeSurfaceOpacity + 0.06f).coerceAtMost(1f)),
            surfaceBright = baseScheme.surfaceBright.copy(alpha = safeSurfaceOpacity),
            surfaceContainerLowest = baseScheme.surfaceContainerLowest.copy(
                alpha = (safeSurfaceOpacity - 0.20f).coerceAtLeast(0.24f)
            ),
            // Appearance-aware overlays paint their isolated background explicitly.
            // Liquid glass keeps this token translucent so its optical layer remains visible.
            surfaceContainerLow = baseScheme.surfaceContainerLow.copy(alpha = pageContainerLowAlpha),
            surfaceContainer = baseScheme.surfaceContainer.copy(alpha = safeSurfaceOpacity),
            surfaceContainerHigh = baseScheme.surfaceContainerHigh.copy(
                alpha = (safeSurfaceOpacity + 0.04f).coerceAtMost(1f)
            ),
            surfaceContainerHighest = baseScheme.surfaceContainerHighest.copy(
                alpha = (safeSurfaceOpacity + 0.08f).coerceAtMost(1f)
            ),
            surfaceVariant = baseScheme.surfaceVariant.copy(alpha = safeSurfaceOpacity),
            outline = baseScheme.outline.copy(alpha = 0.46f),
            outlineVariant = baseScheme.outlineVariant.copy(alpha = 0.30f),
        )
    }
    val typography = MaterialTheme.typography
    val shapes = MaterialTheme.shapes
    MaterialTheme(
        colorScheme = glassScheme,
        typography = typography,
        shapes = shapes,
    ) {
        val foreground = LocalAppearanceBackground.current?.foreground
            ?.takeIf { it != Color.Unspecified }
            ?: glassScheme.onSurface
        me.rerere.rikkahub.ui.theme.BackgroundReadabilityTheme(
            active = true,
            foreground = foreground,
            content = content,
        )
    }
}

internal fun normalizedPageSurfaceOpacity(surfaceOpacity: Float): Float =
    finiteAppearanceValue(surfaceOpacity, 0.35f, 1f, 1f)

@Composable
fun unifiedOverlayContainerColor(): androidx.compose.ui.graphics.Color =
    MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 1f)

@Composable
fun unifiedOverlayScrimColor(): androidx.compose.ui.graphics.Color =
    MaterialTheme.colorScheme.scrim.copy(alpha = 0.56f)

@Composable
fun BlurredBackgroundImage(
    background: String,
    opacity: Float,
    blurRadius: Float,
    overlayTopAlpha: Float,
    overlayBottomAlpha: Float,
    modifier: Modifier = Modifier,
    onImageLoaded: ((Boolean) -> Unit)? = null,
) {
    val backgroundColor = (me.rerere.rikkahub.ui.theme.LocalBaseThemeColorScheme.current ?: MaterialTheme.colorScheme).background.copy(alpha = 1f)
    val safeBlurRadius = LocalAdvancedAppearanceCapabilities.current.limitBackgroundBlur(
        blurRadius.coerceIn(0f, MAX_GLOBAL_BACKGROUND_BLUR_RADIUS)
    )
    Box(modifier = modifier.clipToBounds()) {
        AsyncImage(
            model = background,
            onLoading = { onImageLoaded?.invoke(false) },
            onError = { onImageLoaded?.invoke(false) },
            onSuccess = { onImageLoaded?.invoke(true) },
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (safeBlurRadius > 0f) {
                        Modifier.blur(
                            radius = safeBlurRadius.dp,
                            edgeTreatment = BlurredEdgeTreatment.Rectangle,
                        )
                    } else {
                        Modifier
                    }
                )
                .alpha(finiteAppearanceValue(opacity, 0f, 1f, 1f)),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            backgroundColor.copy(alpha = overlayTopAlpha.coerceIn(0f, 1f)),
                            backgroundColor.copy(alpha = overlayBottomAlpha.coerceIn(0f, 1f)),
                        )
                    )
                )
        )
    }
}
