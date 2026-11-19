package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import me.rerere.rikkahub.ui.components.ui.LocalAppearanceBackground
import me.rerere.rikkahub.ui.context.LocalGlobalBackgroundActive
import me.rerere.rikkahub.ui.theme.LocalBaseThemeColorScheme
import me.rerere.rikkahub.ui.theme.TextContrastSurface
import me.rerere.rikkahub.ui.theme.contrastingTextColor
import me.rerere.rikkahub.ui.theme.textContrastSurface

internal val LocalTextSurfaceBackgrounds = compositionLocalOf<List<Color>?> { null }

@Composable
internal fun rememberReadableTextSurface(
    container: Color,
    foreground: Color,
    underlays: List<Color>? = null,
): TextContrastSurface {
    val base = (LocalBaseThemeColorScheme.current ?: MaterialTheme.colorScheme).surface.copy(alpha = 1f)
    val inherited = underlays ?: LocalTextSurfaceBackgrounds.current
    val sampled = if (LocalGlobalBackgroundActive.current) LocalAppearanceBackground.current?.readability?.backgrounds else null
    // Reuse the existing background samples now that no protective backing is drawn.
    // Synthetic black/white extremes would discard the actual image brightness and
    // select the same fallback even for a dark wallpaper. Use the theme until samples arrive.
    val backgrounds = inherited?.takeIf { it.isNotEmpty() }
        ?: sampled?.takeIf { it.isNotEmpty() } ?: listOf(base)
    return remember(container, base, foreground, backgrounds) {
        textContrastSurface(container, base, foreground, backgrounds)
    }
}

/** Preserve all annotations, links and offsets. Correct resolved runs, not overlapping
 * spans independently (a color span may sit inside a separate inline-code background span). */
internal fun AnnotatedString.withReadableSpans(backgrounds: List<Color>, foreground: Color): AnnotatedString {
    if (isEmpty()) return this
    val spans = spanStyles.withIndex().filter { it.value.start < it.value.end }
    val starts = spans.groupBy { it.value.start }
    val ends = spans.groupBy { it.value.end }
    val boundaries = (listOf(0, length) + starts.keys + ends.keys).distinct().sorted()
    val active = java.util.TreeMap<Int, SpanStyle>()
    val colors = mutableMapOf<Pair<Color, Color>, Color>()
    return AnnotatedString.Builder(this).apply {
        boundaries.zipWithNext().forEach { (start, end) ->
            ends[start]?.forEach { active.remove(it.index) }
            starts[start]?.forEach { active[it.index] = it.value.item }
            var merged = SpanStyle(color = foreground)
            active.values.forEach { merged = merged.merge(it) }
            val corrected = colors.getOrPut(merged.color to merged.background) {
                val painted = if (merged.background == Color.Unspecified) backgrounds
                    else backgrounds.map { merged.background.compositeOver(it) }
                contrastingTextColor(merged.color, painted)
            }
            addStyle(SpanStyle(color = corrected), start, end)
        }
    }.toAnnotatedString()
}
