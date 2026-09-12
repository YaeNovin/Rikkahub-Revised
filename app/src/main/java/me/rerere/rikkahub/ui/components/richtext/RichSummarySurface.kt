package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.Card
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.ui.components.ui.tintedSurfaceForeground
import me.rerere.rikkahub.ui.context.LocalGlobalBackgroundActive
import me.rerere.rikkahub.ui.theme.BackgroundReadabilityTheme
import me.rerere.rikkahub.ui.theme.LocalBackgroundBaseColorScheme
import me.rerere.rikkahub.ui.theme.LocalChatBackgroundForeground
import me.rerere.rikkahub.ui.theme.readableForegroundColor
import me.rerere.rikkahub.ui.theme.wcagContrastRatio

internal fun summarySurfaceForeground(container: Color, backing: Color, inherited: Color, backgroundActive: Boolean): Color {
    if (backgroundActive) {
        if (container.alpha == 0f) return inherited
        val extremes = listOf(container.compositeOver(Color.Black), container.compositeOver(Color.White))
        fun minimumContrast(color: Color) = extremes.minOf { wcagContrastRatio(color, it) }
        val candidate = listOf(Color.Black, Color.White).maxBy(::minimumContrast)
        // A dark translucent card must not retain black wallpaper text simply
        // because no color can guarantee 4.5:1 over every possible photograph.
        if (minimumContrast(candidate) >= 3f &&
            (inherited == Color.Unspecified || minimumContrast(candidate) > minimumContrast(inherited))
        ) return candidate
        return tintedSurfaceForeground(container, container.alpha, inherited)
    }
    val painted = container.compositeOver(backing.copy(alpha = 1f))
    return inherited.takeIf { it != Color.Unspecified && wcagContrastRatio(it, painted) >= 4.5f }
        ?: readableForegroundColor(painted)
}

/** Reasoning and tool summaries use the same surface settings as rich content. */
@Composable
internal fun RichSummarySurface(
    modifier: Modifier = Modifier,
    cardColors: CardColors? = null,
    content: @Composable () -> Unit,
) {
    val rich = richContentColors()
    val scheme = MaterialTheme.colorScheme
    val base = LocalBackgroundBaseColorScheme.current ?: scheme
    val container = cardColors?.containerColor ?: rich.container
    val seed = me.rerere.rikkahub.ui.theme.currentTextPaletteSeed()
    val samples = me.rerere.rikkahub.ui.components.ui.LocalAppearanceBackground.current?.readability?.backgrounds
    val backgroundActive = LocalGlobalBackgroundActive.current
    val textMode = me.rerere.rikkahub.ui.theme.LocalTextColorMode.current
    val foreground = remember(container, base.background, scheme.onSurface, seed, samples, backgroundActive, textMode) {
        if (textMode == me.rerere.rikkahub.data.datastore.TextColorMode.THEME) scheme.onSurface
        else if (seed != null) tintedSurfaceForeground(container, container.alpha, scheme.onSurface,
            samples ?: listOf(base.background.copy(alpha = 1f)), seed)
        else summarySurfaceForeground(container, base.background, scheme.onSurface, backgroundActive)
    }
    val style = LocalTextStyle.current.copy(color = foreground)
    BackgroundReadabilityTheme(active = true, foreground = foreground) {
        CompositionLocalProvider(
            LocalContentColor provides foreground,
            LocalChatBackgroundForeground provides foreground,
        ) {
            ProvideTextStyle(style) {
                Card(
                    modifier = modifier,
                    colors = CardDefaults.cardColors(containerColor = container, contentColor = foreground),
                    border = BorderStroke(1.dp, rich.border),
                    shape = MaterialTheme.shapes.large,
                ) { content() }
            }
        }
    }
}
