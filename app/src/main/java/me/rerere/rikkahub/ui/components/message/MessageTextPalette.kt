package me.rerere.rikkahub.ui.components.message

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import me.rerere.rikkahub.data.datastore.TextColorMode
import me.rerere.rikkahub.ui.theme.LocalTextColorMode
import me.rerere.rikkahub.ui.theme.TextContrastSurface
import me.rerere.rikkahub.ui.theme.currentTextPaletteSeed
import me.rerere.rikkahub.ui.theme.textForegroundFor

internal fun messageTextPalette(mode: TextColorMode, container: Color, underlays: List<Color>,
    seed: Int?, themeForeground: Color): TextContrastSurface {
    val backgrounds = underlays.map { container.compositeOver(it) }
    return TextContrastSurface(container, if (mode == TextColorMode.THEME) themeForeground
        else textForegroundFor(backgrounds, seed, themeForeground), backgrounds)
}

@Composable
internal fun rememberMessageTextPalette(container: Color, underlays: List<Color>, themeForeground: Color): TextContrastSurface {
    val mode = LocalTextColorMode.current
    val seed = currentTextPaletteSeed()
    return remember(container, underlays, themeForeground, mode, seed) {
        messageTextPalette(mode, container, underlays, seed, themeForeground)
    }
}
