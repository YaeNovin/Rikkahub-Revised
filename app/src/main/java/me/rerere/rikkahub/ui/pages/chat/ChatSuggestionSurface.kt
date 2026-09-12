package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.ui.components.richtext.richContentColors
import me.rerere.rikkahub.ui.components.ui.LocalAppearanceBackground
import me.rerere.rikkahub.ui.components.ui.rememberTintedSurfaceForeground
import me.rerere.rikkahub.ui.context.LocalGlobalBackgroundActive
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.theme.BackgroundReadabilityTheme
import me.rerere.rikkahub.ui.theme.LocalBaseThemeColorScheme
import me.rerere.rikkahub.ui.theme.currentTextPaletteSeed

/** Share the parent backdrop; never redraw a separate wallpaper inside a scrolling card. */
internal fun suggestionContainerColor(
    richContainer: Color, fallback: Color, backgroundActive: Boolean, effectsEnabled: Boolean, opacity: Float,
): Color = when {
    !backgroundActive || !effectsEnabled -> fallback.copy(alpha = 1f)
    richContainer == Color.Unspecified || richContainer.alpha == 0f -> fallback.copy(
        alpha = opacity.takeIf(Float::isFinite)?.coerceIn(.2f, .9f) ?: .62f,
    )
    else -> richContainer
}

@Composable
internal fun ChatSuggestionSurface(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val base = LocalBaseThemeColorScheme.current ?: scheme
    val appearance = LocalSettings.current.advancedAppearanceSetting
    val rich = richContentColors()
    val container = suggestionContainerColor(rich.container, base.surfaceContainer,
        LocalGlobalBackgroundActive.current,
        appearance.enableRichContentPerformanceEffects && appearance.enableChatSuggestionSurface,
        appearance.chatSuggestionSurfaceOpacity)
    val samples = LocalAppearanceBackground.current?.readability?.backgrounds
        ?: remember(base.background) { listOf(base.background.copy(alpha = 1f)) }
    val foreground = rememberTintedSurfaceForeground(
        container, container.alpha, scheme.onSurface, samples, currentTextPaletteSeed(),
    )
    val border = BorderStroke(1.dp, rich.border.copy(
        alpha = appearance.chatSuggestionBorderOpacity.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: .72f,
    ))
    BackgroundReadabilityTheme(active = true, foreground = foreground) {
        if (onClick == null) {
            Surface(modifier = modifier, shape = MaterialTheme.shapes.large,
                color = container, contentColor = foreground, border = border, content = content)
        } else {
            Surface(onClick = onClick, enabled = enabled, modifier = modifier,
                shape = MaterialTheme.shapes.large, color = container, contentColor = foreground,
                border = border, content = content)
        }
    }
}
