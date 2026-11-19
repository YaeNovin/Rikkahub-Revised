package me.rerere.rikkahub.ui.theme

import androidx.compose.ui.graphics.Color
import me.rerere.highlight.HighlightTextColorPalette

/** Keep Highlight's original Atom One colours, rather than independently recolouring
 * every syntax role. The already-composited code surface can differ from the app theme
 * (for example a dark wallpaper in light mode, or an opaque light message bubble).
 * A tied/mixed sample set keeps the app's theme; a single bright accent must not turn
 * a predominantly dark code block into a light-palette block. */
internal fun codeHighlightPalette(darkTheme: Boolean, backgrounds: List<Color>): HighlightTextColorPalette {
    val samples = backgrounds.filter { it != Color.Unspecified && it.alpha > 0f }
    var darkVotes = 0
    var lightVotes = 0
    samples.forEach { background ->
        val darkContrast = wcagContrastRatio(AtomOneDarkPalette.fallback, background)
        val lightContrast = wcagContrastRatio(AtomOneLightPalette.fallback, background)
        when {
            darkContrast > lightContrast -> darkVotes++
            lightContrast > darkContrast -> lightVotes++
        }
    }
    val dark = when {
        darkVotes > lightVotes -> true
        lightVotes > darkVotes -> false
        else -> darkTheme
    }
    val palette = if (dark) AtomOneDarkPalette else AtomOneLightPalette
    val commentBackgrounds = samples.ifEmpty { listOf(if (dark) Color(0xFF282C34) else Color(0xFFFAFAFA)) }
    // Comments (and the line-number gutter sharing that colour) are the only
    // adjusted role. No surface/backplate is added and no chromatic token is changed.
    return palette.copy(comment = contrastingTextColor(palette.comment, commentBackgrounds))
}

// Atom One Dark Theme Colors
val AtomOneDarkPalette = HighlightTextColorPalette(
    keyword = Color(0xFFc678dd),    // 紫色
    string = Color(0xFF98c379),     // 绿色
    number = Color(0xFFd19a66),     // 橙色
    comment = Color(0xFF5c6370),    // 灰色
    function = Color(0xFF61afef),   // 蓝色
    operator = Color(0xFF56b6c2),   // 青色
    punctuation = Color(0xFFabb2bf), // 浅灰色
    className = Color(0xFFe5c07b),  // 黄色
    property = Color(0xFFe06c75),   // 红色
    boolean = Color(0xFFd19a66),    // 橙色
    variable = Color(0xFFe06c75),   // 红色
    tag = Color(0xFFe06c75),        // 红色
    attrName = Color(0xFFd19a66),   // 橙色
    attrValue = Color(0xFF98c379),  // 绿色
    fallback = Color(0xFFabb2bf)    // 浅灰色
)

// Atom One Light Theme Colors
val AtomOneLightPalette = HighlightTextColorPalette(
    keyword = Color(0xFFa626a4),    // 紫色
    string = Color(0xFF50a14f),     // 绿色
    number = Color(0xFFc18401),     // 橙色
    comment = Color(0xFF9ca0a4),    // 灰色
    function = Color(0xFF4078f2),   // 蓝色
    operator = Color(0xFF0184bc),   // 青色
    punctuation = Color(0xFF383a42), // 深灰色
    className = Color(0xFFc18401),  // 黄色
    property = Color(0xFFe45649),   // 红色
    boolean = Color(0xFFc18401),    // 橙色
    variable = Color(0xFFe45649),   // 红色
    tag = Color(0xFFe45649),        // 红色
    attrName = Color(0xFFc18401),   // 橙色
    attrValue = Color(0xFF50a14f),  // 绿色
    fallback = Color(0xFF383a42)    // 深灰色
)
