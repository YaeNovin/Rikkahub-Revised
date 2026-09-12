package me.rerere.rikkahub.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import kotlinx.coroutines.CancellationException
import me.rerere.rikkahub.data.model.GradientBackgroundPreset
import me.rerere.rikkahub.data.model.GradientBackgroundCustomColors

private const val READABILITY_HALO_ALPHA = 1f
private const val READABILITY_HALO_BLUR_RADIUS = 4.5f

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

internal fun backgroundReadabilityHaloColor(foreground: Color): Color =
    readableForegroundColor(foreground.copy(alpha = 1f))

internal fun backgroundReadabilityShadow(foreground: Color): Shadow = Shadow(
    color = backgroundReadabilityHaloColor(foreground).copy(alpha = READABILITY_HALO_ALPHA),
    offset = Offset.Zero,
    blurRadius = READABILITY_HALO_BLUR_RADIUS,
)

internal fun TextStyle.withBackgroundReadability(foreground: Color): TextStyle =
    copy(shadow = backgroundReadabilityShadow(foreground))

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
    if (!active || foreground == Color.Unspecified ||
        LocalTextColorMode.current == me.rerere.rikkahub.data.datastore.TextColorMode.THEME) {
        content()
        return
    }

    val baseScheme = MaterialTheme.colorScheme
    val readableScheme = remember(baseScheme, foreground) {
        baseScheme.withReadableForeground(foreground)
    }
    CompositionLocalProvider(LocalBackgroundBaseColorScheme provides (LocalBaseThemeColorScheme.current ?: baseScheme),
        androidx.compose.material3.LocalContentColor provides foreground,
        LocalChatBackgroundForeground provides foreground) {
        MaterialTheme(
            colorScheme = readableScheme,
            typography = MaterialTheme.typography,
            shapes = MaterialTheme.shapes,
            content = content,
        )
    }
}
