package me.rerere.material3

import quantize.QuantizerCelebi
import score.Score

data class ImageColorAnalysis(val seedArgb: Int?, val colorCount: Int, val opaquePixelCount: Int)

/** Uses the bundled, native Kotlin MCU Wu + Wsmeans quantizer and Material scoring. */
fun analyzeImageColors(pixels: IntArray, maxColors: Int = 128): ImageColorAnalysis {
    require(maxColors in 1..256)
    val opaque = pixels.filter { (it ushr 24) >= 128 }.map { it or (0xFF shl 24) }.toIntArray()
    if (opaque.isEmpty()) return ImageColorAnalysis(null, 0, 0)
    val population = QuantizerCelebi.quantize(opaque, maxColors)
    val seed = representativeThemeColor(population)
    return ImageColorAnalysis(seed, population.size, opaque.size)
}

/** Prefer a supported, usable accent, avoiding tiny bright specks and near-black clusters.
 * Ranking stays in MCU's perceptual HCT space; grayscale sources stay grayscale. */
fun representativeThemeColor(population: Map<Int, Int>): Int? {
    val valid = population.filter { (argb, count) -> (argb ushr 24) == 255 && count > 0 }
    val dominant = valid.maxByOrNull { it.value }?.key ?: return null
    val total = valid.values.sumOf { it.toLong() }
    val chromatic = valid.filter { (argb, _) ->
        val hct = hctColorFromArgb(argb)
        hct.chroma >= 5 && hct.tone in 10.0..95.0
    }
    // A real color family is often split into many <2% quantized shades. Measure
    // support across neighboring hues before rejecting isolated specks.
    val hues = chromatic.mapValues { hctColorFromArgb(it.key).hue }
    val suitable = chromatic.filter { (argb, _) ->
        val hue = hues.getValue(argb)
        chromatic.entries.sumOf { (other, count) ->
            val difference = kotlin.math.abs(hue - hues.getValue(other))
            if (minOf(difference, 360 - difference) <= 15) count.toLong() else 0L
        }.toDouble() / total >= .02
    }
    return if (suitable.isEmpty()) dominant else Score.score(suitable, 1, dominant, true).first()
}
