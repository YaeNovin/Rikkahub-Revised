package me.rerere.ai.util

import android.media.ExifInterface
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Base64
import android.util.Base64OutputStream
import android.util.LruCache
import androidx.core.net.toUri
import me.rerere.ai.ui.UIMessagePart
import java.io.ByteArrayOutputStream
import java.io.File

private val supportedTypes = setOf(
    "image/jpeg",
    "image/png",
    "image/gif",
    "image/webp",
)

private const val IMAGE_CACHE_MAX_ENTRY_CHARS = 8 * 1024 * 1024
private const val IMAGE_CACHE_MAX_CHARS = 24 * 1024 * 1024

/**
 * Chat history is sent again for every follow-up message. Keep the compressed
 * representation of ordinary local images so those follow-ups do not decode
 * and compress the same file repeatedly. The cache is deliberately bounded;
 * very large payloads are left uncached to avoid trading latency for OOM risk.
 */
private val encodedImageCache = object : LruCache<String, EncodedImage>(IMAGE_CACHE_MAX_CHARS) {
    override fun sizeOf(key: String, value: EncodedImage): Int = value.base64.length
}

data class EncodedImage(
    val base64: String,
    val mimeType: String
)

data class EncodedAudio(
    val base64: String,
    val mimeType: String,
)

data class EncodedVideo(
    val base64: String,
    val mimeType: String,
)

internal enum class ExifTransformType {
    NONE,
    FLIP_HORIZONTAL,
    ROTATE_180,
    FLIP_VERTICAL,
    TRANSPOSE,
    ROTATE_90,
    TRANSVERSE,
    ROTATE_270,
}

internal fun mapExifOrientationToTransform(orientation: Int): ExifTransformType = when (orientation) {
    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> ExifTransformType.FLIP_HORIZONTAL
    ExifInterface.ORIENTATION_ROTATE_180 -> ExifTransformType.ROTATE_180
    ExifInterface.ORIENTATION_FLIP_VERTICAL -> ExifTransformType.FLIP_VERTICAL
    ExifInterface.ORIENTATION_TRANSPOSE -> ExifTransformType.TRANSPOSE
    ExifInterface.ORIENTATION_ROTATE_90 -> ExifTransformType.ROTATE_90
    ExifInterface.ORIENTATION_TRANSVERSE -> ExifTransformType.TRANSVERSE
    ExifInterface.ORIENTATION_ROTATE_270 -> ExifTransformType.ROTATE_270
    ExifInterface.ORIENTATION_NORMAL,
    ExifInterface.ORIENTATION_UNDEFINED
    -> ExifTransformType.NONE

    else -> ExifTransformType.NONE
}

fun UIMessagePart.Image.encodeBase64(withPrefix: Boolean = true): Result<EncodedImage> = runCatching {
    when {
        this.url.startsWith("file://") -> {
            val filePath =
                this.url.toUri().path ?: throw IllegalArgumentException("Invalid file URI: ${this.url}")
            val file = File(filePath)
            if (!file.exists()) {
                throw IllegalArgumentException("File does not exist: ${this.url}")
            }
            val mimeType = file.guessMimeType().getOrThrow()
            val cacheKey = file.imageEncodingCacheKey(mimeType)
            val cached = synchronized(encodedImageCache) {
                encodedImageCache.get(cacheKey)
            }
            val encodedImage = cached ?: file.compressAndEncode(mimeType).let { (encoded, outputMimeType) ->
                EncodedImage(base64 = encoded, mimeType = outputMimeType).also { result ->
                    if (mimeType != "image/gif" && result.base64.length <= IMAGE_CACHE_MAX_ENTRY_CHARS) {
                        synchronized(encodedImageCache) {
                            encodedImageCache.put(cacheKey, result)
                        }
                    }
                }
            }
            EncodedImage(
                base64 = if (withPrefix) {
                    "data:${encodedImage.mimeType};base64,${encodedImage.base64}"
                } else {
                    encodedImage.base64
                },
                mimeType = encodedImage.mimeType,
            )
        }

        this.url.startsWith("data:") -> {
            // 从 data URL 提取 mime type
            val mimeType = url.substringAfter("data:").substringBefore(";")
            EncodedImage(base64 = url, mimeType = mimeType)
        }
        this.url.startsWith("http") -> {
            // HTTP URL 无法确定 mime type，默认使用 image/png
            EncodedImage(base64 = url, mimeType = "image/png")
        }
        else -> throw IllegalArgumentException("Unsupported URL format: $url")
    }
}

fun UIMessagePart.Video.encodeBase64(withPrefix: Boolean = true): Result<EncodedVideo> = runCatching {
    when {
        this.url.startsWith("file://") -> {
            val filePath =
                this.url.toUri().path ?: throw IllegalArgumentException("Invalid file URI: ${this.url}")
            val file = File(filePath)
            if (!file.exists()) {
                throw IllegalArgumentException("File does not exist: ${this.url}")
            }
            val encoded = file.encodeToBase64Streaming()
            val mimeType = file.videoMimeType()
            EncodedVideo(
                base64 = if (withPrefix) "data:$mimeType;base64,$encoded" else encoded,
                mimeType = mimeType,
            )
        }

        else -> throw IllegalArgumentException("Unsupported URL format: $url")
    }
}

private fun File.videoMimeType(): String = when (extension.lowercase()) {
    "mp4", "m4v" -> "video/mp4"
    "webm" -> "video/webm"
    "3gp", "3gpp" -> "video/3gpp"
    "mov" -> "video/quicktime"
    "mkv" -> "video/x-matroska"
    else -> "video/mp4"
}

fun UIMessagePart.Audio.encodeBase64(withPrefix: Boolean = true): Result<EncodedAudio> = runCatching {
    when {
        this.url.startsWith("data:") -> {
            val rawMimeType = url.substringAfter("data:").substringBefore(';')
            val mimeType = normalizeAudioMimeType(rawMimeType)
                ?: throw IllegalArgumentException("Unsupported audio MIME type: $rawMimeType")
            val encoded = url.substringAfter(";base64,", missingDelimiterValue = "")
            if (encoded.isBlank()) throw IllegalArgumentException("Audio data URL has no base64 payload")
            EncodedAudio(
                base64 = if (withPrefix) "data:$mimeType;base64,$encoded" else encoded,
                mimeType = mimeType,
            )
        }

        this.url.startsWith("file://") || File(this.url).isAbsolute -> {
            val file = if (this.url.startsWith("file://")) {
                val filePath = this.url.toUri().path
                    ?: throw IllegalArgumentException("Invalid file URI: ${this.url}")
                File(filePath)
            } else {
                File(this.url)
            }
            if (!file.exists()) {
                throw IllegalArgumentException("File does not exist: ${this.url}")
            }
            val encoded = file.encodeToBase64Streaming()
            val mimeType = file.audioMimeType()
            EncodedAudio(
                base64 = if (withPrefix) "data:$mimeType;base64,$encoded" else encoded,
                mimeType = mimeType,
            )
        }

        else -> throw IllegalArgumentException("Unsupported URL format: $url")
    }
}

internal fun normalizeAudioMimeType(mimeType: String): String? = when (
    mimeType.substringBefore(';').trim().lowercase()
) {
    "audio/mpeg", "audio/mp3", "audio/x-mp3" -> "audio/mpeg"
    "audio/mp4", "audio/x-m4a", "audio/m4a" -> "audio/mp4"
    "audio/wav", "audio/x-wav", "audio/wave" -> "audio/wav"
    "audio/ogg", "application/ogg" -> "audio/ogg"
    "audio/flac", "audio/x-flac" -> "audio/flac"
    "audio/aac", "audio/x-aac" -> "audio/aac"
    "audio/amr" -> "audio/amr"
    "audio/3gpp", "audio/3gp" -> "audio/3gpp"
    "audio/webm" -> "audio/webm"
    else -> null
}

private fun File.audioMimeType(): String = audioMimeTypeFromExtension()
    ?: sniffAudioMimeType()
    ?: throw IllegalArgumentException("Unsupported audio file type: .$extension")

private fun File.audioMimeTypeFromExtension(): String? = when (extension.lowercase()) {
    "mp3", "mp2", "mpga" -> "audio/mpeg"
    "m4a", "mp4", "aacp" -> "audio/mp4"
    "wav", "wave" -> "audio/wav"
    "ogg", "oga", "opus" -> "audio/ogg"
    "flac" -> "audio/flac"
    "aac" -> "audio/aac"
    "amr" -> "audio/amr"
    "3gp", "3gpp" -> "audio/3gpp"
    "webm" -> "audio/webm"
    else -> null
}

private fun File.sniffAudioMimeType(): String? = runCatching {
    val header = ByteArray(16)
    val read = inputStream().use { it.read(header) }
    when {
        read >= 12 && header.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF" &&
            header.copyOfRange(8, 12).toString(Charsets.US_ASCII) == "WAVE" -> "audio/wav"
        read >= 4 && header.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "OggS" -> "audio/ogg"
        read >= 4 && header.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "fLaC" -> "audio/flac"
        read >= 5 && header.copyOfRange(0, 5).toString(Charsets.US_ASCII) == "#!AMR" -> "audio/amr"
        read >= 12 && header.copyOfRange(4, 8).toString(Charsets.US_ASCII) == "ftyp" -> "audio/mp4"
        read >= 3 && header.copyOfRange(0, 3).toString(Charsets.US_ASCII) == "ID3" -> "audio/mpeg"
        else -> null
    }
}.getOrNull()

private fun File.compressAndEncode(
    mimeType: String,
    maxDimension: Int = 10_000,
    maxPixels: Long = 16_000_000L,
    quality: Int = 85
): Pair<String, String> {
    // GIF 保持原样（可能是动图）
    if (mimeType == "image/gif") {
        return Pair(encodeToBase64Streaming(), mimeType)
    }

    // 读取图片尺寸
    val options = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
    }
    BitmapFactory.decodeFile(absolutePath, options)

    options.inSampleSize = calculateImageInSampleSize(
        width = options.outWidth,
        height = options.outHeight,
        maxDimension = maxDimension,
        maxPixels = maxPixels
    )
    options.inJustDecodeBounds = false

    val bitmap = BitmapFactory.decodeFile(absolutePath, options)
        ?: throw IllegalArgumentException("Failed to decode image: $absolutePath")
    val normalizedBitmap = normalizeByExif(bitmap)

    return try {
        val byteArrayOutputStream = ByteArrayOutputStream()
        // 强制使用 JPEG 格式，因为很多提供商不支持 webp
        Base64OutputStream(byteArrayOutputStream, Base64.NO_WRAP).use { base64Stream ->
            normalizedBitmap.compress(Bitmap.CompressFormat.JPEG, quality, base64Stream)
        }
        Pair(byteArrayOutputStream.toString(Charsets.ISO_8859_1.name()), "image/jpeg")
    } finally {
        if (normalizedBitmap !== bitmap) {
            normalizedBitmap.recycle()
        }
        bitmap.recycle()
    }
}

private fun File.imageEncodingCacheKey(sourceMimeType: String): String =
    "${absolutePath}|${length()}|${lastModified()}|$sourceMimeType"

private fun File.normalizeByExif(bitmap: Bitmap): Bitmap {
    val orientation = runCatching {
        ExifInterface(absolutePath).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL
        )
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    val transform = mapExifOrientationToTransform(orientation)
    return applyExifTransform(bitmap, transform)
}

private fun applyExifTransform(bitmap: Bitmap, transform: ExifTransformType): Bitmap {
    if (transform == ExifTransformType.NONE) return bitmap

    val matrix = Matrix()
    when (transform) {
        ExifTransformType.NONE -> return bitmap
        ExifTransformType.FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
        ExifTransformType.ROTATE_180 -> matrix.setRotate(180f)
        ExifTransformType.FLIP_VERTICAL -> matrix.setScale(1f, -1f)
        ExifTransformType.TRANSPOSE -> {
            matrix.setRotate(90f)
            matrix.postScale(-1f, 1f)
        }
        ExifTransformType.ROTATE_90 -> matrix.setRotate(90f)
        ExifTransformType.TRANSVERSE -> {
            matrix.setRotate(270f)
            matrix.postScale(-1f, 1f)
        }
        ExifTransformType.ROTATE_270 -> matrix.setRotate(270f)
    }

    return runCatching {
        Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }.getOrElse { bitmap }
}

private fun File.encodeToBase64Streaming(): String {
    val byteArrayOutputStream = ByteArrayOutputStream()
    Base64OutputStream(byteArrayOutputStream, Base64.NO_WRAP).use { base64Stream ->
        inputStream().use { input ->
            input.copyTo(base64Stream, bufferSize = 8 * 1024)
        }
    }
    return byteArrayOutputStream.toString(Charsets.ISO_8859_1.name())
}

internal fun calculateImageInSampleSize(
    width: Int,
    height: Int,
    maxDimension: Int,
    maxPixels: Long
): Int {
    if (width <= 0 || height <= 0) return 1

    var inSampleSize = 1
    while (
        (height / inSampleSize) > maxDimension ||
        (width / inSampleSize) > maxDimension ||
        (width.toLong() / inSampleSize) * (height.toLong() / inSampleSize) > maxPixels
    ) {
        inSampleSize *= 2
    }
    return inSampleSize
}

private fun File.guessMimeType(): Result<String> = runCatching {
    inputStream().use { input ->
        val bytes = ByteArray(16)
        val read = input.read(bytes)
        if (read < 12) error("File too short to determine MIME type")

        // 判断 HEIF/HEIC/AVIF 格式：ISO-BMFF 容器，"ftyp" box 位于字节 4..8，主品牌码位于 8..12
        // 新手机的 HDR HEIF 照片常用 heix/hevc/mif1/msf1 等品牌码，而非仅 heic，需全部识别
        if (bytes.copyOfRange(4, 8).toString(Charsets.US_ASCII) == "ftyp") {
            when (bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII)) {
                "heic", "heix", "heim", "heis",
                "hevc", "hevx", "hevm", "hevs",
                "mif1", "msf1", "heif",
                    -> return@runCatching "image/heic"

                "avif", "avis" -> return@runCatching "image/avif"
            }
        }

        // 判断 JPEG 格式：开头为 0xFF 0xD8
        if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) {
            return@runCatching "image/jpeg"
        }

        // 判断 PNG 格式：开头为 89 50 4E 47 0D 0A 1A 0A
        if (bytes.copyOfRange(0, 8).contentEquals(
                byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
            )
        ) {
            return@runCatching "image/png"
        }

        // 判断WebP格式：开头为 "RIFF" + 4字节长度 + "WEBP"
        if (bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF" && bytes.copyOfRange(8, 12)
                .toString(Charsets.US_ASCII) == "WEBP"
        ) {
            return@runCatching "image/webp"
        }

        // 判断 GIF 格式：开头为 "GIF89a" 或 "GIF87a"
        val header = bytes.copyOfRange(0, 6).toString(Charsets.US_ASCII)
        if (header == "GIF89a" || header == "GIF87a") {
            return@runCatching "image/gif"
        }

        error(
            "Failed to guess MIME type: $header, ${
                bytes.joinToString(",") {
                    it.toUByte().toString()
                }
            }"
        )
    }
}
