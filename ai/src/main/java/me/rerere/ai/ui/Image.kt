package me.rerere.ai.ui

import kotlinx.serialization.Serializable

@Serializable
data class ImageGenerationItem(
    val data: String = "",
    val mimeType: String,
    val seed: Long? = null,
    val partial: Boolean = false,
    val partialImageIndex: Int? = null,
    val temporaryFilePath: String? = null,
)

@Serializable
enum class ImageGenSize(val value: String) {
    AUTO("auto"),
    SQUARE_1024("1024x1024"),
    LANDSCAPE_1536("1536x1024"),
    PORTRAIT_1536("1024x1536"),
    SQUARE_2048("2048x2048"),
    LANDSCAPE_2048_WIDE("2048x1152"),
    PORTRAIT_2048_WIDE("1152x2048"),
    LANDSCAPE_2048_CLASSIC("2048x1536"),
    PORTRAIT_2048_CLASSIC("1536x2048"),
    LANDSCAPE_2304("2304x1536"),
    PORTRAIT_2304("1536x2304"),
    LANDSCAPE_2560("2560x1440"),
    PORTRAIT_2560("1440x2560"),
    LANDSCAPE_3824("3824x2144"),
    PORTRAIT_3824("2144x3824"),
    SQUARE_256("256x256"),
    SQUARE_512("512x512"),
    LANDSCAPE_1792("1792x1024"),
    PORTRAIT_1792("1024x1792"),
}
