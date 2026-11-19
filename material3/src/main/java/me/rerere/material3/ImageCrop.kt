package me.rerere.material3

data class ImageCrop(val left: Int, val top: Int, val width: Int, val height: Int)

/** Mirrors centered ContentScale.Crop so color analysis sees the displayed pixels. */
fun centerImageCrop(width: Int, height: Int, aspectRatio: Float?): ImageCrop {
    require(width > 0 && height > 0)
    if (aspectRatio == null || !aspectRatio.isFinite() || aspectRatio <= 0) return ImageCrop(0, 0, width, height)
    val croppedWidth = minOf(width, (height * aspectRatio).toInt().coerceAtLeast(1))
    val croppedHeight = minOf(height, (width / aspectRatio).toInt().coerceAtLeast(1))
    return ImageCrop((width - croppedWidth) / 2, (height - croppedHeight) / 2, croppedWidth, croppedHeight)
}
