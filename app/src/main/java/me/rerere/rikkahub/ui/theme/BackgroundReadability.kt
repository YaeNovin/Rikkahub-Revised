package me.rerere.rikkahub.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import kotlinx.coroutines.CancellationException
import me.rerere.rikkahub.data.model.GradientBackgroundPreset
import me.rerere.rikkahub.data.model.GradientBackgroundCustomColors


val LocalChatBackgroundForeground = compositionLocalOf { Color.Unspecified }

internal val LocalBackgroundBaseColorScheme = compositionLocalOf<ColorScheme?> { null }

@Composable
fun rememberChatBackgroundForeground(
    background: String?,
    backgroundOpacity: Float,
    useGradientBackground: Boolean,
    gradientFollowTheme: Boolean = false,
    gradientPreset: GradientBackgroundPreset = GradientBackgroundPreset.CLASSIC,
    gradientCustomColors: GradientBackgroundCustomColors = GradientBackgroundCustomColors(),
    gradientIntensity: Float = 1f,
    gradientVignette: Float = 0f,
): Color = rememberBackgroundReadability(
    background = background,
    backgroundOpacity = backgroundOpacity,
    useGradientBackground = useGradientBackground,
    gradientFollowTheme = gradientFollowTheme,
    gradientPreset = gradientPreset,
    gradientCustomColors = gradientCustomColors,
    gradientIntensity = gradientIntensity,
    gradientVignette = gradientVignette,
).foreground

internal suspend fun safeBackgroundForegroundExtraction(
    fallback: Color,
    extract: suspend () -> Color?,
): Color = try {
    extract() ?: fallback
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    fallback
}


internal fun ColorScheme.withReadableForeground(foreground: Color): ColorScheme {
    val opaqueForeground = foreground.copy(alpha = 1f)
    return copy(
        onBackground = opaqueForeground,
        onSurface = opaqueForeground,
        onSurfaceVariant = opaqueForeground,
        // Outlines and component accents belong to the selected application theme.
    )
}

@Composable
fun BackgroundReadabilityTheme(
    active: Boolean,
    foreground: Color,
    content: @Composable () -> Unit,
) {
    val baseScheme = MaterialTheme.colorScheme
    val applyForeground = active && foreground != Color.Unspecified &&
        LocalTextColorMode.current != me.rerere.rikkahub.data.datastore.TextColorMode.THEME
    // MaterialTheme can update ColorScheme in place. Copy live roles rather than
    // retaining a scheme keyed only by object identity when the foreground is unchanged.
    val readableScheme = if (applyForeground) baseScheme.withReadableForeground(foreground) else baseScheme
    // Keep one composition path across mode changes: removing these wrappers also
    // removes descendants' remembered scroll and navigation state.
    CompositionLocalProvider(
        LocalBackgroundBaseColorScheme provides if (applyForeground) (LocalBaseThemeColorScheme.current ?: baseScheme)
            else LocalBackgroundBaseColorScheme.current,
        androidx.compose.material3.LocalContentColor provides if (applyForeground) foreground
            else androidx.compose.material3.LocalContentColor.current,
        LocalChatBackgroundForeground provides if (applyForeground) foreground else LocalChatBackgroundForeground.current,
    ) {
        MaterialTheme(
            colorScheme = readableScheme,
            typography = MaterialTheme.typography,
            shapes = MaterialTheme.shapes,
        ) {
            val style = androidx.compose.material3.LocalTextStyle.current
            androidx.compose.material3.ProvideTextStyle(
                style.copy(shadow = null), content)
        }
    }
}
