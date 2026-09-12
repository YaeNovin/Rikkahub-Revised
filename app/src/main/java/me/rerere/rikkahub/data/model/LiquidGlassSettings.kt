package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable

@Serializable
enum class LiquidGlassPreset { SOFT, STANDARD, CLEAR, CUSTOM }

@Serializable
enum class LiquidGlassRenderer { AUTO, STANDARD, AGSL }

@Serializable
data class LiquidGlassParameters(
    val highlight: Float = .38f,
    val tint: Float = .05f,
    val edgeWidth: Float = 1.5f,
    val refraction: Float = 6f,
    val innerShadow: Float = .1f,
)

@Serializable
data class LiquidGlassSettings(
    val preset: LiquidGlassPreset = LiquidGlassPreset.STANDARD,
    val custom: LiquidGlassParameters = LiquidGlassParameters(),
    val renderer: LiquidGlassRenderer = LiquidGlassRenderer.AUTO,
    val adaptivePerformance: Boolean = true,
    val applyToComposer: Boolean = false,
)

internal fun LiquidGlassSettings.parameters(): LiquidGlassParameters = when (preset) {
    LiquidGlassPreset.SOFT -> LiquidGlassParameters(.20f, .035f, 1f, 3f, .06f)
    LiquidGlassPreset.STANDARD -> LiquidGlassParameters()
    LiquidGlassPreset.CLEAR -> LiquidGlassParameters(.55f, .015f, 2f, 9f, .08f)
    LiquidGlassPreset.CUSTOM -> custom
}.normalized()

internal fun LiquidGlassParameters.normalized(): LiquidGlassParameters {
    fun safe(value: Float, minimum: Float, maximum: Float, fallback: Float) = if (value.isFinite()) value.coerceIn(minimum, maximum) else fallback
    return copy(highlight = safe(highlight, 0f, .8f, .38f), tint = safe(tint, 0f, .2f, .05f),
        edgeWidth = safe(edgeWidth, .5f, 4f, 1.5f), refraction = safe(refraction, 0f, 16f, 6f), innerShadow = safe(innerShadow, 0f, .25f, .1f))
}

internal fun LiquidGlassSettings.edit(transform: (LiquidGlassParameters) -> LiquidGlassParameters): LiquidGlassSettings =
    copy(preset = LiquidGlassPreset.CUSTOM, custom = transform(parameters()).normalized())
