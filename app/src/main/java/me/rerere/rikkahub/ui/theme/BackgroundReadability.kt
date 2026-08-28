package me.rerere.rikkahub.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import kotlinx.coroutines.CancellationException

private const val CHAT_BACKGROUND_OVERLAY_TOP_ALPHA = 0.32f
private const val READABILITY_HALO_ALPHA = 1f
private const val READABILITY_HALO_BLUR_RADIUS = 4.5f

val LocalChatBackgroundForeground = compositionLocalOf { Color.Unspecified }

internal val LocalBackgroundBaseColorScheme = compositionLocalOf<ColorScheme?> { null }

@Composable
fun rememberChatBackgroundForeground(
    background: String?,
    backgroundOpacity: Float,
    useGradientBackground: Boolean,
): Color {
    val context = LocalContext.current
    val darkMode = LocalDarkMode.current
    val baseColor = MaterialTheme.colorScheme.background
    val fallback = MaterialTheme.colorScheme.onSurface
    val foreground by produceState(
        initialValue = fallback,
        background,
        backgroundOpacity,
        useGradientBackground,
        darkMode,
        baseColor,
        fallback,
    ) {
        value = when {
            useGradientBackground -> readableForegroundColor(
                if (darkMode) Color(0xFF1B2A45) else Color(0xFFAFD0F2)
            )

            !background.isNullOrBlank() -> safeBackgroundForegroundExtraction(fallback) {
                extractBackgroundForeground(
                    context = context,
                    source = background,
                    imageOpacity = backgroundOpacity,
                    overlayColor = baseColor,
                    overlayAlpha = CHAT_BACKGROUND_OVERLAY_TOP_ALPHA,
                )
            }

            else -> fallback
        }
    }
    return foreground
}

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
        outline = opaqueForeground.copy(alpha = 0.82f),
        outlineVariant = opaqueForeground.copy(alpha = 0.62f),
    )
}

@Composable
fun BackgroundReadabilityTheme(
    active: Boolean,
    foreground: Color,
    content: @Composable () -> Unit,
) {
    if (!active || foreground == Color.Unspecified) {
        content()
        return
    }

    val baseScheme = LocalBackgroundBaseColorScheme.current ?: MaterialTheme.colorScheme
    val readableScheme = remember(baseScheme, foreground) {
        baseScheme.withReadableForeground(foreground)
    }
    CompositionLocalProvider(LocalBackgroundBaseColorScheme provides baseScheme) {
        MaterialTheme(
            colorScheme = readableScheme,
            typography = MaterialTheme.typography,
            shapes = MaterialTheme.shapes,
            content = content,
        )
    }
}
