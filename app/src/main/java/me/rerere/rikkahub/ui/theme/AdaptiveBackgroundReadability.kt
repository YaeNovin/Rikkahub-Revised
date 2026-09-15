package me.rerere.rikkahub.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import me.rerere.rikkahub.data.datastore.TextColorMode
import me.rerere.rikkahub.data.model.GradientBackgroundCustomColors
import me.rerere.rikkahub.data.model.GradientBackgroundPreset

@Immutable
data class BackgroundReadability(
    val foreground: Color,
    val backgrounds: List<Color>,
    val rawBackgrounds: List<Color> = backgrounds,
    val seedArgb: Int? = null,
)

/** Select text for existing pixels. Readability must never alter wallpaper or surface colors. */
internal fun adaptiveBackgroundReadability(samples: List<Color>, dark: Boolean): BackgroundReadability {
    val colors = samples.ifEmpty { listOf(if (dark) Color.Black else Color.White) }
    return BackgroundReadability(readableForegroundColor(colors), colors)
}

/** Samples the original vertical theme overlay without introducing any new render layer. */
internal fun backgroundSamplesWithOverlay(samples: List<Color>, base: Color, topAlpha: Float, bottomAlpha: Float): List<Color> =
    samples.mapIndexed { index, color ->
        val progress = (index + .5f) / samples.size.coerceAtLeast(1)
        base.copy(alpha = (topAlpha + (bottomAlpha - topAlpha) * progress).coerceIn(0f, 1f)).compositeOver(color)
    }

internal data class BackgroundSampleKey(val source: String?, val opacity: Float, val base: Color)

/** Mode changes retry failed extraction, but successful samples are reused without network IO.
 * A source change creates a fresh holder immediately, never a frame using the prior wallpaper.
 */
@Composable
internal fun rememberReadabilitySamples(key: BackgroundSampleKey, retryKey: Any, extract: suspend () -> List<Color>?): List<Color>? {
    val samples = remember(key) { mutableStateOf<List<Color>?>(null) }
    val currentExtract by rememberUpdatedState(extract)
    LaunchedEffect(key, retryKey) {
        if (samples.value != null || key.source.isNullOrBlank() || key.opacity <= 0f) return@LaunchedEffect
        try { samples.value = currentExtract()?.takeIf { it.isNotEmpty() } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { samples.value = null }
    }
    return samples.value
}

@Composable
fun rememberBackgroundReadability(background: String?, backgroundOpacity: Float, useGradientBackground: Boolean,
    gradientFollowTheme: Boolean = false, gradientPreset: GradientBackgroundPreset = GradientBackgroundPreset.CLASSIC,
    gradientCustomColors: GradientBackgroundCustomColors = GradientBackgroundCustomColors(), gradientIntensity: Float = 1f,
    gradientVignette: Float = 0f, overlayTopAlpha: Float = .32f, overlayBottomAlpha: Float = .52f): BackgroundReadability {
    val context = LocalContext.current
    val scheme = LocalBaseThemeColorScheme.current ?: MaterialTheme.colorScheme
    val dark = LocalDarkMode.current
    val mode = LocalTextColorMode.current
    val wallpaperSeed = LocalWallpaperTextSeed.current.argb
    val fallback = remember(scheme.background, dark) {
        adaptiveBackgroundReadability(listOf(scheme.background.copy(alpha = 1f)), dark)
    }
    if (useGradientBackground) {
        // Compute the inexpensive palette first: ColorScheme may be mutated in place by
        // MaterialTheme. Its identity alone is not a valid remember key for color roles.
        val palette = createGradientBackgroundPalette(scheme, dark, gradientFollowTheme, gradientPreset, gradientCustomColors)
        return remember(palette, scheme.background, scheme.onBackground, backgroundOpacity, gradientIntensity, gradientVignette, mode, wallpaperSeed) {
            val samples = gradientReadabilitySamples(palette,
                backgroundOpacity, gradientIntensity, scheme.background.copy(alpha = 1f))
            val allSamples = samples + samples.map { Color.Black.copy(alpha = gradientVignette.coerceIn(0f, 1f) * .28f).compositeOver(it) }
            val appSeed = if (mode == TextColorMode.APP_BACKGROUND && backgroundOpacity > 0f) backgroundTextSeed(samples) else null
            val seed = textPaletteSeed(mode, appSeed, wallpaperSeed)
            BackgroundReadability(backgroundTextForeground(mode, allSamples, seed, scheme.onBackground,
                backgroundOpacity > 0f), allSamples, seedArgb = seed)
        }
    }
    val sampleKey = BackgroundSampleKey(background, backgroundOpacity, scheme.background.copy(alpha = 1f))
    val rawSamples = rememberReadabilitySamples(sampleKey, mode) {
        extractBackgroundSamples(context, requireNotNull(background), backgroundOpacity, sampleKey.base)
    }
    val result = remember(rawSamples, sampleKey.base, dark, overlayTopAlpha, overlayBottomAlpha, fallback) {
        rawSamples?.let {
            adaptiveBackgroundReadability(backgroundSamplesWithOverlay(it, sampleKey.base, overlayTopAlpha, overlayBottomAlpha), dark).copy(rawBackgrounds = it)
        } ?: fallback
    }
    return remember(result, mode, wallpaperSeed, background, backgroundOpacity, scheme.onBackground) {
        val appSeed = if (mode == TextColorMode.APP_BACKGROUND && !background.isNullOrBlank() && backgroundOpacity > 0f) {
            backgroundTextSeed(result.rawBackgrounds)
        } else null
        val seed = textPaletteSeed(mode, appSeed, wallpaperSeed)
        result.copy(foreground = backgroundTextForeground(mode, result.backgrounds, seed, scheme.onBackground,
            !background.isNullOrBlank() && backgroundOpacity > 0f), seedArgb = seed)
    }
}
