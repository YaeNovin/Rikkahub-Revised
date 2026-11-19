package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import me.rerere.rikkahub.ui.theme.chatQuoteAccent

internal data class DecoratedChatText(val text: AnnotatedString, val inlineContent: Map<String, InlineTextContent>)

/** Add annotations over the existing HEX literal. Native selection/copy keeps exactly #RRGGBB. */
internal fun AnnotatedString.withHexInlineAnnotations(swatches: List<ChatHexSwatch>): AnnotatedString {
    if (swatches.isEmpty()) return this
    return AnnotatedString.Builder(this).apply {
        swatches.forEach { swatch ->
            val literal = text.substring(swatch.start, swatch.end)
            val marker = buildAnnotatedString { appendInlineContent(hexInlineId(swatch), literal) }
                .getStringAnnotations(0, literal.length).single()
            addStringAnnotation(marker.tag, marker.item, swatch.start, swatch.end)
        }
    }.toAnnotatedString()
}

private fun hexInlineId(swatch: ChatHexSwatch) = "rikkahub.hex:${swatch.start}:${swatch.argb}"

/** Sweep resolved runs once, so quotes spanning bold/link/colored HTML keep their other styles. */
internal fun AnnotatedString.withChatQuoteHighlights(
    quotes: List<ChatTextRange>, backgrounds: List<Color>, seed: Color, baseWeight: FontWeight?,
): AnnotatedString {
    if (quotes.isEmpty()) return this
    val spans = spanStyles.withIndex().filter { it.value.start < it.value.end }
    val starts = spans.groupBy { it.value.start }
    val ends = spans.groupBy { it.value.end }
    val boundaries = (starts.keys + ends.keys + quotes.flatMap { listOf(it.start, it.end) }).distinct().sorted()
    val active = java.util.TreeMap<Int, SpanStyle>()
    val accents = mutableMapOf<Pair<Color, Color>, Color>()
    var quoteIndex = 0
    return AnnotatedString.Builder(this).apply {
        boundaries.zipWithNext().forEach { (start, end) ->
            ends[start]?.forEach { active.remove(it.index) }
            starts[start]?.forEach { active[it.index] = it.value.item }
            while (quoteIndex < quotes.size && quotes[quoteIndex].end <= start) quoteIndex++
            val quote = quotes.getOrNull(quoteIndex) ?: return@forEach
            if (start < quote.start || end > quote.end) return@forEach
            var resolved = SpanStyle(fontWeight = baseWeight)
            active.values.forEach { resolved = resolved.merge(it) }
            val accent = accents.getOrPut(resolved.background to resolved.color) {
                val painted = if (resolved.background == Color.Unspecified) backgrounds
                    else backgrounds.map { resolved.background.compositeOver(it) }
                chatQuoteAccent(seed, painted, resolved.color)
            }
            addStyle(SpanStyle(color = accent, fontWeight = FontWeight(maxOf(600, resolved.fontWeight?.weight ?: 400))), start, end)
        }
    }.toAnnotatedString()
}

@Composable
internal fun rememberHexInlineContent(
    text: AnnotatedString,
    swatches: List<ChatHexSwatch>,
    existing: Map<String, InlineTextContent>,
    style: TextStyle,
): DecoratedChatText {
    if (swatches.isEmpty()) return DecoratedChatText(text, existing)
    val measurer = rememberTextMeasurer(cacheSize = 16)
    val density = LocalDensity.current
    val labels = remember(text, swatches) { swatches.map { text.subSequence(it.start, it.end) } }
    // Read the resolver on each composition: an asynchronously loaded custom font can
    // invalidate a cached TextLayoutResult even when the text and style are unchanged.
    val layouts = labels.map { measurer.measure(it, style = style, softWrap = false, maxLines = 1) }
    return remember(text, swatches, existing, style, layouts, density) {
        val additions = swatches.mapIndexed { index, swatch ->
            val label = labels[index]
            var resolved = style.toSpanStyle()
            label.spanStyles.filter { it.start == 0 }.forEach { resolved = resolved.merge(it.item) }
            val baseSize = style.fontSize.takeIf { it.isSpecified } ?: 16.sp
            val fontSize = when {
                !resolved.fontSize.isSpecified -> baseSize
                resolved.fontSize.isEm -> baseSize * resolved.fontSize.value
                else -> resolved.fontSize
            }
            val measured = layouts[index]
            val side = with(density) { fontSize.toPx() }
            val gap = side * .2f
            val height = maxOf(side, measured.size.height.toFloat())
            val width = side + gap + measured.size.width
            val border = density.density
            val outline = (resolved.color.takeIf { it != Color.Unspecified } ?: Color.Gray).copy(alpha = .45f)
            hexInlineId(swatch) to InlineTextContent(
                Placeholder(with(density) { width.toSp() }, with(density) { height.toSp() }, PlaceholderVerticalAlign.TextCenter),
            ) {
                // Canvas has no separate selectable/accessibility text: the parent retains the literal.
                Canvas(Modifier.fillMaxSize()) {
                    drawRect(Color(swatch.argb), Offset(0f, (size.height - side) / 2f), Size(side, side))
                    drawRect(outline, Offset(border / 2, (size.height - side + border) / 2),
                        Size((side - border).coerceAtLeast(0f), (side - border).coerceAtLeast(0f)), style = Stroke(border))
                    drawText(measured, topLeft = Offset(side + gap, (size.height - measured.size.height) / 2f))
                }
            }
        }.toMap()
        DecoratedChatText(text.withHexInlineAnnotations(swatches), existing + additions)
    }
}
