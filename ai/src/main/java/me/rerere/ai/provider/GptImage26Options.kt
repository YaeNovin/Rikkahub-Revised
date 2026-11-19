package me.rerere.ai.provider

import kotlinx.serialization.Serializable

@Serializable
data class GptImage26Options(
    val complexity: String? = null,
    val frameCount: Int? = null,
    val frameDurationMs: Int? = null,
    val svgModel: String? = null,
    val svgReasoningEffort: String? = null,
    val version: String? = null,
)

fun ImageGenerationConstraints.forOutputFormat(format: String?): ImageGenerationConstraints = when {
    !supportsGptImage26Options -> this
    format == "gif" -> copy(
        supportedSizes = linkedSetOf("auto", "512x512"), supportsCustomSize = false,
        customSizeMinPixels = null, supportedBackgroundValues = setOf("auto", "opaque"),
        supportsOutputCompression = false, supportsPartialImages = false,
    )
    format == "svg" -> copy(maxOutputImages = 1, supportsOutputCompression = false, supportsPartialImages = false)
    else -> this
}
