package me.rerere.rikkahub.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import me.rerere.rikkahub.data.datastore.BackgroundSurfaceStyle
import me.rerere.rikkahub.ui.components.ui.AppearanceBackgroundSpec
import me.rerere.rikkahub.ui.components.ui.LocalAppearanceBackground
import me.rerere.rikkahub.ui.components.ui.hasMicaBackground
import me.rerere.rikkahub.ui.context.LocalGlobalBackgroundActive
import me.rerere.rikkahub.ui.context.LocalPageSurfaceStyle
import me.rerere.rikkahub.ui.context.LocalSettings

internal val LocalMicaPalette = compositionLocalOf<MicaPalette?> { null }

internal fun micaColorScheme(original: androidx.compose.material3.ColorScheme, palette: MicaPalette,
    backgroundVisible: Boolean = false, surfaceOpacity: Float = .68f): androidx.compose.material3.ColorScheme {
    val alpha = me.rerere.rikkahub.ui.components.ui.micaSurfaceOpacity(surfaceOpacity, backgroundVisible)
    val card = palette.card.copy(alpha = alpha)
    val raised = palette.raisedCard.copy(alpha = alpha)
    return original.copy(
    background = if (backgroundVisible) Color.Transparent else palette.baseTop,
    surface = card, surfaceDim = palette.baseBottom.copy(alpha = alpha),
    surfaceBright = raised, surfaceContainerLowest = palette.baseBottom.copy(alpha = alpha),
    surfaceContainerLow = card, surfaceContainer = card,
    surfaceContainerHigh = raised, surfaceContainerHighest = raised,
    surfaceVariant = card, onBackground = palette.foreground,
    onSurface = palette.foreground, onSurfaceVariant = palette.secondaryForeground,
    outlineVariant = palette.border,
)
}

@Composable
internal fun rememberMicaPalette(): MicaPalette {
    LocalMicaPalette.current?.let { return it }
    val theme = LocalBaseThemeColorScheme.current ?: MaterialTheme.colorScheme
    val spec = LocalAppearanceBackground.current
    val sourceAvailable = spec != null && spec.opacity > 0f && (!spec.background.isNullOrBlank() || spec.useGradientBackground)
    val samples = spec?.readability?.rawBackgrounds?.takeIf { sourceAvailable }.orEmpty()
    val dark = LocalDarkMode.current
    val strength = LocalSettings.current.advancedAppearanceSetting.micaTintStrength
    return remember(theme.primary, theme.onSurface, theme.onSurfaceVariant, dark, samples, strength) {
        micaPalette(theme, dark, samples, strength)
    }
}

/** Retain wallpaper and its readability samples; only surface roles acquire Mica tint. */
@Composable
internal fun MicaPageTheme(active: Boolean = true, content: @Composable () -> Unit) {
    val palette = if (active) rememberMicaPalette() else null
    val original = LocalBaseThemeColorScheme.current ?: MaterialTheme.colorScheme
    val spec = LocalAppearanceBackground.current ?: AppearanceBackgroundSpec(null, 0f, 0f)
    val visible = spec.hasMicaBackground()
    val seed = currentTextPaletteSeed()
    val recolor = LocalTextColorMode.current != me.rerere.rikkahub.data.datastore.TextColorMode.THEME && seed != null
    val foreground = when {
        palette == null -> androidx.compose.material3.LocalContentColor.current
        visible && spec.foreground != Color.Unspecified -> spec.foreground
        recolor -> textForegroundFor(listOf(palette.baseTop, palette.baseBottom), seed, palette.foreground)
        else -> palette.foreground
    }
    val opacity = if (active) me.rerere.rikkahub.ui.components.ui.micaSurfaceOpacity(
        LocalSettings.current.advancedAppearanceSetting.pageSurfaceOpacity, visible) else 1f
    // Bare-page text and tinted cards have different effective backgrounds.
    // Do not reuse a wallpaper's white foreground on a pale Mica card.
    val scheme = if (palette == null) MaterialTheme.colorScheme else {
        val cardSamples = spec.readability?.backgrounds.orEmpty().flatMap { sample ->
            listOf(palette.card, palette.raisedCard).map { it.copy(alpha = opacity).compositeOver(sample) }
        }.ifEmpty { listOf(palette.card, palette.raisedCard) }
        micaColorScheme(original, palette, visible, opacity).copy(
            onBackground = foreground,
            onSurface = if (recolor) textForegroundFor(cardSamples, seed) else contrastingTextColor(palette.foreground, cardSamples),
            onSurfaceVariant = if (recolor) textForegroundFor(cardSamples, seed) else contrastingTextColor(palette.secondaryForeground, cardSamples))
    }
    val readability = if (visible || palette == null) spec.readability else
        BackgroundReadability(foreground, listOf(palette.baseTop, palette.baseBottom),
            rawBackgrounds = spec.readability?.rawBackgrounds.orEmpty(), seedArgb = seed)
    MaterialTheme(colorScheme = scheme) {
        CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides foreground,
            LocalMicaPalette provides (palette ?: LocalMicaPalette.current),
            LocalBaseThemeColorScheme provides if (active) original else LocalBaseThemeColorScheme.current,
            LocalBackgroundBaseColorScheme provides if (active) original else LocalBackgroundBaseColorScheme.current,
            LocalGlobalBackgroundActive provides (active || LocalGlobalBackgroundActive.current),
            LocalChatBackgroundForeground provides if (active) foreground else LocalChatBackgroundForeground.current,
            LocalAppearanceBackground provides if (active) spec.copy(foreground = foreground, readability = readability)
                else LocalAppearanceBackground.current,
        ) { content() }
    }
}

@Composable
internal fun ChatMaterialTheme(active: Boolean, foreground: Color, content: @Composable () -> Unit) {
    if (LocalPageSurfaceStyle.current == BackgroundSurfaceStyle.MICA) {
        // The assistant may have a different image from the global page.
        CompositionLocalProvider(LocalMicaPalette provides null) { MicaPageTheme(content = content) }
    }
    else BackgroundReadabilityTheme(active, foreground, content)
}
