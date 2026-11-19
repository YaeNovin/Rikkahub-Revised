package me.rerere.rikkahub.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import me.rerere.material3.hctColorFromArgb
import me.rerere.material3.hctColorFromComponents

private data class QuoteAccentKey(val seed: Int, val backgrounds: List<Color>, val foreground: Color)
private val quoteAccentCache = object : LinkedHashMap<QuoteAccentKey, Color>(64, .75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<QuoteAccentKey, Color>?) = size > 64
}

internal fun chatQuoteSeed(paletteSeed: Int?, foreground: Color, themeAccent: Color): Color =
    paletteSeed?.takeIf { hctColorFromArgb(it).chroma >= 5.0 }?.let(::Color)
        ?: foreground.takeIf { hctColorFromArgb(it.toArgb()).chroma >= 5.0 }
        ?: themeAccent

/** Keep the selected text hue; increase chroma without falling back to pure black/white. */
internal fun chatQuoteAccent(seed: Color, backgrounds: List<Color>, foreground: Color = Color.Unspecified): Color {
    val samples = backgrounds.ifEmpty { listOf(Color.White) }
    val key = QuoteAccentKey(seed.toArgb(), samples.toList(), foreground)
    synchronized(quoteAccentCache) { quoteAccentCache[key]?.let { return it } }
    val hct = hctColorFromArgb(seed.toArgb())
    val chroma = (hct.chroma * 1.6).coerceIn(96.0, 120.0)
    val luminances = samples.map(::wcagRelativeLuminance).sorted()
    data class Candidate(val color: Color, val chroma: Double, val tone: Int, val contrast: List<Float>, val medianContrast: Float)
    val candidates = (20..90).map { tone ->
        val argb = hctColorFromComponents(hct.hue, chroma, tone.toDouble()).argb
        val color = Color(argb)
        val luminance = wcagRelativeLuminance(color)
        Candidate(color, hctColorFromArgb(argb).chroma, tone, luminances.map { contrastFromLuminances(luminance, it) },
            contrastFromLuminances(luminance, luminances[luminances.size / 2]))
    }.filter { it.chroma >= 12.0 }
    // Requiring 7:1 on every pixel of a photograph pushed emphasis to a
    // near-black tone. Preserve normal-text contrast on uniform surfaces and
    // maximize actual RGB gamut chroma, rather than unattainable HCT chroma.
    val normal = candidates.filter { candidate -> candidate.contrast.all { it >= WCAG_NORMAL_TEXT_MIN_CONTRAST } }
    val nearlyUniform = luminances.last() - luminances.first() < .08f
    val vividNormal = normal.filter { it.chroma >= 30.0 && it.tone >= 35 }
    val pool = if (nearlyUniform && normal.isNotEmpty()) normal else if (vividNormal.isNotEmpty()) vividNormal else {
        // On mixed photographs, do not let a washed-out near-white candidate
        // win solely because it covers a few more samples than a vivid one.
        val vivid = candidates.filter { it.chroma >= 30.0 && it.tone >= 35 }.ifEmpty { candidates }
        val coverage = vivid.maxOf { candidate -> candidate.contrast.count { it >= WCAG_NORMAL_TEXT_MIN_CONTRAST } }
        val minimumCoverage = kotlin.math.ceil(coverage * .9).toInt()
        val covered = vivid.filter { candidate ->
            candidate.contrast.count { it >= WCAG_NORMAL_TEXT_MIN_CONTRAST } >= minimumCoverage
        }
        // Mixed photos cannot guarantee contrast on every detail. Retain at
        // least 90% of the best vivid coverage and prefer the median brightness.
        covered.filter { it.medianContrast >= WCAG_NORMAL_TEXT_MIN_CONTRAST }.ifEmpty { covered }
    }
    fun separation(color: Color): Float = if (foreground == Color.Unspecified) 0f else
        kotlin.math.sqrt((color.red - foreground.red) * (color.red - foreground.red) +
            (color.green - foreground.green) * (color.green - foreground.green) +
            (color.blue - foreground.blue) * (color.blue - foreground.blue))
    val result = pool.maxBy { it.chroma + separation(it.color) * 12.0 }.color
    synchronized(quoteAccentCache) { quoteAccentCache[key] = result }
    return result
}
