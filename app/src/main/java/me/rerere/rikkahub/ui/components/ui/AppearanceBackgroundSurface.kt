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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.datastore.BackgroundSurfaceStyle
import me.rerere.rikkahub.data.datastore.GradientRendererMode
import me.rerere.rikkahub.data.model.GradientBackgroundPreset
import me.rerere.rikkahub.data.model.GradientBackgroundCustomColors
import me.rerere.rikkahub.data.datastore.MAX_NAVIGATION_GLASS_BLUR_RADIUS
import me.rerere.rikkahub.data.datastore.MIN_NAVIGATION_GLASS_BLUR_RADIUS
import me.rerere.rikkahub.ui.theme.BackgroundReadabilityTheme
import me.rerere.rikkahub.ui.theme.LocalBackgroundBaseColorScheme
import me.rerere.rikkahub.ui.context.LocalSettings

@Immutable
data class AppearanceBackgroundSpec(
    val background: String?,
    val opacity: Float,
    val blurRadius: Float,
    val useGradientBackground: Boolean = false,
    val gradientAnimation: Boolean = true,
    val gradientSpeed: Float = 1f,
    val gradientFollowTheme: Boolean = false,
    val gradientPreset: GradientBackgroundPreset = GradientBackgroundPreset.CLASSIC,
    val gradientCustomColors: GradientBackgroundCustomColors = GradientBackgroundCustomColors(),
    val gradientIntensity: Float = 1f,
    val gradientMotionScale: Float = 1f,
    val gradientBlobCount: Int = 4,
    val gradientSoftness: Float = 1f,
    val gradientAngle: Float = 0f,
    val gradientVignette: Float = 0f,
    val gradientPerformanceEffectsEnabled: Boolean = true,
    val gradientRendererMode: GradientRendererMode = GradientRendererMode.AUTO,
    val gradientInteractionInProgress: Boolean = false,
    val respectSystemReducedMotion: Boolean = true,
    val foreground: Color = Color.Unspecified,
    val readability: me.rerere.rikkahub.ui.theme.BackgroundReadability? = null,
)

val LocalAppearanceBackground = compositionLocalOf<AppearanceBackgroundSpec?> { null }

enum class IsolatedBackgroundRendering { SHARED_PAGE, INDEPENDENT }

internal fun shouldUseSharedSurfaceBackground(mode: IsolatedBackgroundRendering, sourceAvailable: Boolean): Boolean =
    mode == IsolatedBackgroundRendering.SHARED_PAGE && sourceAvailable

internal fun shouldDrawIndependentSurfaceBackground(mode: IsolatedBackgroundRendering): Boolean =
    mode == IsolatedBackgroundRendering.INDEPENDENT

internal fun isolatedSurfaceForeground(
    backgroundReady: Boolean,
    backingColor: Color,
    tintColor: Color,
    tintAlpha: Float,
    themeForeground: Color,
    imageForeground: Color,
): Color = if (backgroundReady) {
    tintedSurfaceForeground(tintColor, tintAlpha, imageForeground)
} else {
    themeForeground.takeIf { me.rerere.rikkahub.ui.theme.wcagContrastRatio(it, backingColor) >= 4.5f }
        ?: me.rerere.rikkahub.ui.theme.readableForegroundColor(backingColor)
}

@Composable
fun IsolatedAppearanceSurface(
    style: BackgroundSurfaceStyle,
    surfaceOpacity: Float,
    blurRadius: Float,
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.extraSmall,
    backgroundRendering: IsolatedBackgroundRendering = IsolatedBackgroundRendering.SHARED_PAGE,
    content: @Composable BoxScope.() -> Unit,
) {
    val backgroundSpec = LocalAppearanceBackground.current
    val capabilities = LocalAdvancedAppearanceCapabilities.current
    val supportedStyle = capabilities.effectiveSurfaceStyle(style)
    val renderedScheme = MaterialTheme.colorScheme
    val originalScheme = LocalBackgroundBaseColorScheme.current ?: renderedScheme
    val independentBackground = shouldDrawIndependentSurfaceBackground(backgroundRendering)
    var imageReady by remember(backgroundSpec?.background) { mutableStateOf(false) }
    val canRenderBackground = supportedStyle != BackgroundSurfaceStyle.OPAQUE &&
        backgroundSpec != null &&
        (!backgroundSpec.background.isNullOrBlank() || backgroundSpec.useGradientBackground)
    val effectiveStyle = if (canRenderBackground) supportedStyle else BackgroundSurfaceStyle.OPAQUE
    val baseScheme = if (backgroundRendering == IsolatedBackgroundRendering.INDEPENDENT) originalScheme else resolveIsolatedSurfaceColorScheme(
        style = effectiveStyle,
        renderedScheme = renderedScheme,
        backgroundBaseScheme = LocalBackgroundBaseColorScheme.current,
    )
    val safeOpacity = isolatedSurfaceTintAlpha(surfaceOpacity)
    val backgroundForeground = backgroundSpec?.foreground?.takeIf { it != Color.Unspecified }
        ?: baseScheme.onSurface
    val backingColor = isolatedSurfaceBackingColor(
        if (backgroundSpec == null ||
            (backgroundSpec.background.isNullOrBlank() && !backgroundSpec.useGradientBackground)
        ) baseScheme.background else baseScheme.surfaceContainerLow
    )
    val backgroundReady = effectiveStyle != BackgroundSurfaceStyle.OPAQUE &&
        (backgroundSpec?.opacity ?: 0f) > 0f &&
        (!independentBackground || backgroundSpec?.useGradientBackground == true || imageReady)
    val textSeed = me.rerere.rikkahub.ui.theme.currentTextPaletteSeed()
    val followThemeText = me.rerere.rikkahub.ui.theme.LocalTextColorMode.current ==
        me.rerere.rikkahub.data.datastore.TextColorMode.THEME
    val sampledForeground = remember(backgroundReady, backgroundSpec?.readability, independentBackground,
        backgroundSpec?.useGradientBackground, baseScheme.background, safeOpacity, textSeed, backgroundForeground) {
        if (backgroundReady && backgroundSpec?.readability != null) {
            val samples = if (independentBackground) backgroundSpec.readability.rawBackgrounds else backgroundSpec.readability.backgrounds
            val surfaceSamples = if (independentBackground && !backgroundSpec.useGradientBackground) {
                me.rerere.rikkahub.ui.theme.backgroundSamplesWithOverlay(samples, baseScheme.background, safeOpacity, (safeOpacity + .10f).coerceAtMost(1f))
            } else samples.map { baseScheme.background.copy(alpha = safeOpacity).compositeOver(it) }
            me.rerere.rikkahub.ui.theme.textForegroundFor(surfaceSamples, textSeed, backgroundForeground)
        } else null
    }
    val contentColor = remember(sampledForeground, textSeed, backgroundReady, backingColor, followThemeText,
        baseScheme.background, safeOpacity, originalScheme.onSurface, backgroundForeground) {
        if (followThemeText) originalScheme.onSurface else sampledForeground ?: textSeed?.takeIf { !backgroundReady }?.let {
            me.rerere.rikkahub.ui.theme.textForegroundFor(listOf(backingColor), it)
        } ?: isolatedSurfaceForeground(backgroundReady, backingColor, baseScheme.background,
            safeOpacity, originalScheme.onSurface, backgroundForeground)
    }
    val contentScheme = baseScheme.copy(
        onSurface = contentColor,
        onSurfaceVariant = contentColor.copy(alpha = 0.78f),
        onBackground = contentColor,
    )
    val styledModifier = modifier
        .then(LocalGlassBackdrop.current?.let { Modifier.trackGlassInteraction(it) } ?: Modifier)
        .then(
            if (effectiveStyle == BackgroundSurfaceStyle.LIQUID_GLASS) {
                Modifier.shadow(elevation = 8.dp, shape = shape, clip = false)
            } else {
                Modifier
            }
        )
        .clip(shape)

    Box(modifier = styledModifier) {
        // Only independent overlays need an opaque backing to hide page content.
        // Inline surfaces keep the page visible even before its source is ready.
        if (independentBackground || effectiveStyle == BackgroundSurfaceStyle.OPAQUE) Box(
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
            if (!independentBackground) {
                InlineBackdropBlur(
                    blurRadius = effectiveBlur,
                    shape = shape,
                    glass = effectiveStyle == BackgroundSurfaceStyle.LIQUID_GLASS,
                    modifier = Modifier.matchParentSize(),
                )
                Box(Modifier.matchParentSize().background(baseScheme.background.copy(alpha = tintAlpha)))
            } else if (backgroundSpec.useGradientBackground) {
                AnimatedGradientBackground(
                    spec = GradientBackgroundSpec(
                        opacity = backgroundSpec.opacity,
                        animationEnabled = backgroundSpec.gradientAnimation,
                        speed = backgroundSpec.gradientSpeed,
                        followTheme = backgroundSpec.gradientFollowTheme,
                        preset = backgroundSpec.gradientPreset,
                        customColors = backgroundSpec.gradientCustomColors,
                        intensity = backgroundSpec.gradientIntensity,
                        motionScale = backgroundSpec.gradientMotionScale,
                        blobCount = backgroundSpec.gradientBlobCount,
                        softness = backgroundSpec.gradientSoftness,
                        angle = backgroundSpec.gradientAngle,
                        vignette = backgroundSpec.gradientVignette,
                        performanceEffectsEnabled =
                            backgroundSpec.gradientPerformanceEffectsEnabled,
                        rendererMode = backgroundSpec.gradientRendererMode,
                        interactionInProgress = backgroundSpec.gradientInteractionInProgress,
                        respectSystemReducedMotion = backgroundSpec.respectSystemReducedMotion,
                    ),
                    baseColor = Color.Transparent,
                    modifier = Modifier
                        .matchParentSize()
                        .then(
                            if (effectiveBlur > 0f) {
                                Modifier.blur(
                                    radius = effectiveBlur.dp,
                                    edgeTreatment = BlurredEdgeTreatment.Rectangle,
                                )
                            } else {
                                Modifier
                            }
                        ),
                )
                Box(
                    modifier = Modifier
                        .matchParentSize()
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
                    onImageLoaded = { imageReady = it },
                )
            }
        }
        if (effectiveStyle == BackgroundSurfaceStyle.LIQUID_GLASS) {
            LiquidGlassSurfaceLayers(
                modifier = Modifier.matchParentSize(),
                strength = 0.92f,
                shape = shape,
            )
        }
        MaterialTheme(
            colorScheme = contentScheme,
            typography = MaterialTheme.typography,
            shapes = MaterialTheme.shapes,
        ) {
            CompositionLocalProvider(LocalBackgroundBaseColorScheme provides baseScheme) {
            BackgroundReadabilityTheme(
                active = effectiveStyle != BackgroundSurfaceStyle.OPAQUE,
                foreground = contentColor,
            ) {
                CompositionLocalProvider(
                    androidx.compose.material3.LocalContentColor provides contentColor,
                    LocalInsideGlassSurface provides true,
                    me.rerere.rikkahub.ui.context.LocalGlobalBackgroundActive provides (effectiveStyle != BackgroundSurfaceStyle.OPAQUE),
                    me.rerere.rikkahub.ui.context.LocalPageSurfaceStyle provides effectiveStyle,
                    LocalGlassBackdrop provides LocalGlassBackdrop.current.takeIf { !independentBackground && effectiveStyle != BackgroundSurfaceStyle.OPAQUE },
                    LocalGlobalBackgroundHazeState provides LocalGlobalBackgroundHazeState.current.takeUnless { independentBackground },
                    LocalAppearanceBackground provides backgroundSpec.takeIf { effectiveStyle != BackgroundSurfaceStyle.OPAQUE },
                    me.rerere.rikkahub.ui.theme.LocalChatBackgroundForeground provides contentColor,
                ) {
                    content()
                }
            }
            }
        }
    }
}

internal fun isolatedSurfaceTintAlpha(surfaceOpacity: Float): Float =
    finiteAppearanceValue(surfaceOpacity, 0f, 1f, 1f)

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
        backgroundRendering = IsolatedBackgroundRendering.INDEPENDENT,
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
