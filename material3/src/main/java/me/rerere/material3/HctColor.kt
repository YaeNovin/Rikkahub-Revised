package me.rerere.material3

import hct.Hct

/** A stable public representation of a Material Color Utilities HCT color. */
data class HctColorValue(
    val hue: Double,
    val chroma: Double,
    val tone: Double,
    val argb: Int,
)

fun hctColorFromArgb(argb: Int): HctColorValue = Hct.fromInt(argb).toValue()

fun hctColorFromComponents(
    hue: Double,
    chroma: Double,
    tone: Double,
): HctColorValue {
    require(hue.isFinite()) { "hue must be finite" }
    require(chroma.isFinite()) { "chroma must be finite" }
    require(tone.isFinite()) { "tone must be finite" }

    return Hct.from(
        hue = hue.coerceIn(0.0, 360.0),
        chroma = chroma.coerceIn(0.0, 150.0),
        tone = tone.coerceIn(0.0, 100.0),
    ).toValue()
}

private fun Hct.toValue() = HctColorValue(
    hue = hue,
    chroma = chroma,
    tone = tone,
    argb = toInt(),
)
