package me.rerere.rikkahub.ui.components.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.datastore.BackgroundSurfaceStyle
import me.rerere.rikkahub.data.datastore.MAX_NAVIGATION_GLASS_BLUR_RADIUS
import me.rerere.rikkahub.data.datastore.MIN_NAVIGATION_GLASS_BLUR_RADIUS
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import me.rerere.rikkahub.ui.theme.BackgroundReadabilityTheme
import me.rerere.rikkahub.ui.theme.LocalBackgroundBaseColorScheme
import me.rerere.rikkahub.ui.context.LocalSettings

@Immutable
data class AppearanceBackgroundSpec(
    val background: String?,
    val opacity: Float,
    val blurRadius: Float,
    val useGradientBackground: Boolean = false,
    val foreground: Color = Color.Unspecified,
)

val LocalAppearanceBackground = compositionLocalOf<AppearanceBackgroundSpec?> { null }

@Composable
fun IsolatedAppearanceSurface(
    style: BackgroundSurfaceStyle,
    surfaceOpacity: Float,
    blurRadius: Float,
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.extraSmall,
    content: @Composable BoxScope.() -> Unit,
) {
    val backgroundSpec = LocalAppearanceBackground.current
    val capabilities = LocalAdvancedAppearanceCapabilities.current
    val supportedStyle = capabilities.effectiveSurfaceStyle(style)
    val renderedScheme = MaterialTheme.colorScheme
    val canRenderBackground = supportedStyle != BackgroundSurfaceStyle.OPAQUE &&
        backgroundSpec != null &&
        (!backgroundSpec.background.isNullOrBlank() || backgroundSpec.useGradientBackground)
    val effectiveStyle = if (canRenderBackground) supportedStyle else BackgroundSurfaceStyle.OPAQUE
    val baseScheme = resolveIsolatedSurfaceColorScheme(
        style = effectiveStyle,
        renderedScheme = renderedScheme,
        backgroundBaseScheme = LocalBackgroundBaseColorScheme.current,
    )
    val safeOpacity = isolatedSurfaceTintAlpha(surfaceOpacity)
    val backgroundForeground = backgroundSpec?.foreground?.takeIf { it != Color.Unspecified }
        ?: baseScheme.onSurface
    val contentColor = if (effectiveStyle == BackgroundSurfaceStyle.OPAQUE) {
        baseScheme.onSurface
    } else {
        backgroundForeground
    }
    val contentScheme = remember(baseScheme, contentColor, effectiveStyle) {
        if (effectiveStyle == BackgroundSurfaceStyle.OPAQUE) {
            baseScheme
        } else {
            baseScheme.copy(
                onSurface = contentColor,
                onSurfaceVariant = contentColor.copy(alpha = 0.78f),
                onBackground = contentColor,
            )
        }
    }
    val backingColor = isolatedSurfaceBackingColor(baseScheme.surfaceContainerLow)
    val styledModifier = modifier
        .then(
            if (effectiveStyle == BackgroundSurfaceStyle.LIQUID_GLASS) {
                Modifier.shadow(elevation = 8.dp, shape = shape, clip = false)
            } else {
                Modifier
            }
        )
        .clip(shape)
        .then(
            if (effectiveStyle == BackgroundSurfaceStyle.LIQUID_GLASS) {
                Modifier.border(liquidGlassBorder(strength = 0.46f), shape)
            } else {
                Modifier
            }
        )

    Box(modifier = styledModifier) {
        // The configured transparency applies to the redrawn background below,
        // never to the real page content behind this popup/dialog.
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(backingColor),
        )
        if (effectiveStyle != BackgroundSurfaceStyle.OPAQUE && backgroundSpec != null) {
            val effectiveBlur = when (effectiveStyle) {
                BackgroundSurfaceStyle.OPAQUE,
                BackgroundSurfaceStyle.TRANSLUCENT -> 0f
                BackgroundSurfaceStyle.FROSTED -> capabilities.limitLiveBlur(
                    blurRadius.coerceIn(
                        MIN_NAVIGATION_GLASS_BLUR_RADIUS,
                        MAX_NAVIGATION_GLASS_BLUR_RADIUS,
                    )
                )
                BackgroundSurfaceStyle.LIQUID_GLASS -> capabilities.limitLiveBlur(
                    liquidGlassBlurRadius(blurRadius)
                )
            }
            val tintAlpha = safeOpacity
            if (backgroundSpec.useGradientBackground) {
                val darkMode = LocalDarkMode.current
                val gradientColors = if (darkMode) {
                    listOf(Color(0xFF1B2A45), Color(0xFF0D1626), Color(0xFF080B12))
                } else {
                    listOf(Color(0xFFAFD0F2), Color(0xFFF1F7FD), Color.White)
                }
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(Brush.verticalGradient(gradientColors))
                        .background(baseScheme.background.copy(alpha = tintAlpha)),
                )
            } else {
                BlurredBackgroundImage(
                    background = backgroundSpec.background.orEmpty(),
                    opacity = backgroundSpec.opacity,
                    blurRadius = effectiveBlur,
                    overlayTopAlpha = tintAlpha,
                    overlayBottomAlpha = (tintAlpha + 0.10f).coerceAtMost(1f),
                    modifier = Modifier.matchParentSize(),
                )
            }
        }
        if (effectiveStyle == BackgroundSurfaceStyle.LIQUID_GLASS) {
            LiquidGlassSurfaceLayers(
                modifier = Modifier.matchParentSize(),
                strength = 0.92f,
            )
        }
        MaterialTheme(
            colorScheme = contentScheme,
            typography = MaterialTheme.typography,
            shapes = MaterialTheme.shapes,
        ) {
            BackgroundReadabilityTheme(
                active = effectiveStyle != BackgroundSurfaceStyle.OPAQUE,
                foreground = contentColor,
            ) {
                CompositionLocalProvider(
                    androidx.compose.material3.LocalContentColor provides contentColor,
                ) {
                    content()
                }
            }
        }
    }
}

internal fun isolatedSurfaceTintAlpha(surfaceOpacity: Float): Float =
    surfaceOpacity.coerceIn(0f, 1f)

internal fun isolatedSurfaceBackingColor(surfaceColor: Color): Color =
    surfaceColor.copy(alpha = 1f)

internal fun resolveIsolatedSurfaceColorScheme(
    style: BackgroundSurfaceStyle,
    renderedScheme: ColorScheme,
    backgroundBaseScheme: ColorScheme?,
): ColorScheme = if (style == BackgroundSurfaceStyle.OPAQUE) {
    backgroundBaseScheme ?: renderedScheme
} else {
    renderedScheme
}

@Composable
fun IsolatedOverlaySurface(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.extraSmall,
    content: @Composable BoxScope.() -> Unit,
) {
    val appearance = LocalSettings.current.advancedAppearanceSetting
    IsolatedAppearanceSurface(
        style = appearance.overlaySurfaceStyle,
        surfaceOpacity = appearance.overlaySurfaceOpacity,
        blurRadius = when (appearance.overlaySurfaceStyle) {
            BackgroundSurfaceStyle.LIQUID_GLASS -> appearance.overlayLiquidGlassBlurRadius
            else -> appearance.overlaySurfaceBlurRadius
        },
        modifier = modifier,
        shape = shape,
        content = content,
    )
}

/** A lightweight choice surface drawn inside an already isolated menu/dialog. */
@Composable
fun AppearanceOptionSurface(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    shape: Shape = MaterialTheme.shapes.medium,
    content: @Composable BoxScope.() -> Unit,
) {
    val foreground = MaterialTheme.colorScheme.onSurface
    val interactiveModifier = if (onClick != null) {
        Modifier.clickable(onClick = onClick)
    } else {
        Modifier
    }
    Box(
        modifier = modifier
            .clip(shape)
            .background(foreground.copy(alpha = 0.08f))
            .border(
                width = 1.dp,
                color = foreground.copy(alpha = 0.22f),
                shape = shape,
            )
            .then(interactiveModifier),
    ) {
        CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides foreground,
        ) {
            content()
        }
    }
}
