@file:Suppress("unused")

package me.rerere.rikkahub.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import me.rerere.common.android.appTempFolder
import java.io.File
import kotlin.math.roundToInt
import com.drew.imaging.ImageMetadataReader
import com.drew.imaging.png.PngChunkType
import com.drew.metadata.png.PngDirectory
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer

/**
 * 图片处理工具类
 * 提供图片压缩、旋转修正、二维码解析等功能
 */
object ImageUtils {

    private const val LARGE_IMAGE_BYTES = 10L * 1024L * 1024L
    private const val MAX_AVATAR_IMPORT_BYTES = 32L * 1024 * 1024
    private const val MAX_AVATAR_CROP_DIMENSION = 4096

    /** uCrop's native writer reopens the source file when the user confirms the crop. */
    internal fun prepareAvatarCropSource(context: Context, uri: Uri, target: File) {
        val source = File.createTempFile("avatar_import_", ".img", context.appTempFolder)
        try {
            val input = requireNotNull(context.contentResolver.openInputStream(uri)) {
                "Failed to open selected image"
            }
            input.use {
                source.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var copied = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        copied += count
                        require(copied <= MAX_AVATAR_IMPORT_BYTES) { "Selected image is too large" }
                        output.write(buffer, 0, count)
                    }
                }
            }

            val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(source)) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
                    val size = info.size
                    val longestEdge = maxOf(size.width, size.height)
                    if (longestEdge > MAX_AVATAR_CROP_DIMENSION) {
                        val scale = MAX_AVATAR_CROP_DIMENSION.toDouble() / longestEdge
                        decoder.setTargetSize(
                            (size.width * scale).roundToInt().coerceAtLeast(1),
                            (size.height * scale).roundToInt().coerceAtLeast(1),
                        )
                    }
                }
            } else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(source.path, bounds)
                val longestEdge = maxOf(bounds.outWidth, bounds.outHeight)
                var sampleSize = 1
                while (longestEdge > sampleSize.toLong() * MAX_AVATAR_CROP_DIMENSION) {
                    sampleSize *= 2
                }
                val options = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
                }
                val decoded = requireNotNull(BitmapFactory.decodeFile(source.path, options)) {
                    "Selected image could not be decoded"
                }
                correctImageOrientation(context, Uri.fromFile(source), decoded)
            }

            try {
                target.outputStream().use { output ->
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                        "Failed to prepare image for cropping"
                    }
                }
            } finally {
                bitmap.recycle()
            }
        } catch (error: Throwable) {
            target.delete()
            throw error
        } finally {
            source.delete()
        }
    }

    /**
     * Re-encodes a large static image before it enters the chat upload directory.
     * Sampling happens during decode, so a camera-sized image never has to be
     * materialized at its original resolution.
     */
    fun optimizeLargeImage(
        context: Context,
        uri: Uri,
        target: File,
        sourceSizeBytes: Long?,
        maxDimension: Int = 4096,
        maxPixels: Long = 12_000_000L,
        quality: Int = 86,
    ): Boolean {
        if (sourceSizeBytes == null || sourceSizeBytes < LARGE_IMAGE_BYTES) return false
        if (context.contentResolver.getType(uri)?.lowercase() == "image/gif") return false

        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching false

            var sampleSize = 1
            while (
                (bounds.outWidth / sampleSize) > maxDimension ||
                    (bounds.outHeight / sampleSize) > maxDimension ||
                    (bounds.outWidth.toLong() / sampleSize) * (bounds.outHeight.toLong() / sampleSize) > maxPixels
            ) {
                sampleSize *= 2
            }
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, decodeOptions)
            } ?: return@runCatching false
            val oriented = correctImageOrientation(context, uri, decoded)
            try {
                target.parentFile?.mkdirs()
                target.outputStream().use { output ->
                    oriented.compress(Bitmap.CompressFormat.JPEG, quality, output)
                }
                true
            } finally {
                if (oriented !== decoded) oriented.recycle()
                decoded.recycle()
            }
        }.onFailure {
            Log.w("ImageUtils", "Failed to optimize large image $uri", it)
            target.delete()
        }.getOrDefault(false)
    }

    /**
     * 优化的图片加载方法，避免OOM
     * 1. 先获取图片尺寸
     * 2. 计算合适的采样率
     * 3. 加载压缩后的图片
     * 4. 处理图片旋转
     *
     * @param context Android上下文
     * @param uri 图片URI
     * @param maxSize 最大尺寸限制，默认1024px
     * @return 压缩后的Bitmap，失败返回null
     */
    fun loadOptimizedBitmap(
        context: Context,
        uri: Uri,
        maxSize: Int = 1024,
        preferredConfig: Bitmap.Config = Bitmap.Config.RGB_565,
    ): Bitmap? {
        return runCatching {
            // 第一步：获取图片的原始尺寸，不加载到内存
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }

            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                BitmapFactory.decodeStream(inputStream, null, options)
            }

            // 计算合适的采样率
            val sampleSize = calculateInSampleSize(options, maxSize, maxSize)

            // 第二步：使用采样率加载压缩后的图片
            val loadOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = preferredConfig
            }

            val bitmap = context.contentResolver.openInputStream(uri)?.use { inputStream ->
                BitmapFactory.decodeStream(inputStream, null, loadOptions)
            }

            // 第三步：处理图片旋转（如果需要）
            bitmap?.let { correctImageOrientation(context, uri, it) }
        }.onFailure {
            it.printStackTrace()
        }.getOrNull()
    }

    /**
     * 计算合适的采样率
     *
     * @param options BitmapFactory.Options包含原始图片尺寸信息
     * @param reqWidth 目标宽度
     * @param reqHeight 目标高度
     * @return 采样率（2的幂）
     */
    fun calculateInSampleSize(
        options: BitmapFactory.Options,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2

            // 计算最大的inSampleSize值，该值是2的幂，并且保持高度和宽度都大于请求的高度和宽度
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }

        return inSampleSize
    }

    /**
     * 修正图片旋转
     * 根据EXIF信息自动旋转图片到正确方向
     *
     * @param context Android上下文
     * @param uri 图片URI
     * @param bitmap 原始bitmap
     * @return 旋转后的bitmap
     */
    fun correctImageOrientation(context: Context, uri: Uri, bitmap: Bitmap): Bitmap {
        return runCatching {
            val exif = context.contentResolver.openInputStream(uri)?.use { ExifInterface(it) }
            val rotation = exif?.rotationDegrees ?: 0
            val flipped = exif?.isFlipped == true
            if (rotation == 0 && !flipped) return bitmap

            val matrix = Matrix()
            if (flipped) matrix.setScale(-1f, 1f)
            matrix.postRotate(rotation.toFloat())

            val rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotatedBitmap != bitmap) {
                bitmap.recycle() // 回收原始bitmap
            }
            rotatedBitmap
        }.onFailure {
            it.printStackTrace()
        }.getOrDefault(bitmap)
    }

    /**
     * 判断 uri 指向的图片是否为 HEIF/HEIC 格式
     * 优先使用 ContentResolver 的 MIME 类型，回退到读取文件头魔数嗅探
     */
    fun isHeifImage(context: Context, uri: Uri): Boolean {
        context.contentResolver.getType(uri)?.lowercase()?.let { mime ->
            if (mime.contains("heic") || mime.contains("heif")) return true
        }
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val header = ByteArray(12)
                val read = input.read(header)
                if (read < 12) return@use false
                if (header.copyOfRange(4, 8).toString(Charsets.US_ASCII) != "ftyp") return@use false
                header.copyOfRange(8, 12).toString(Charsets.US_ASCII) in HEIF_BRANDS
            } ?: false
        }.getOrDefault(false)
    }

    /**
     * 将 HEIF/HEIC 图片解码后重编码为 JPEG 写入 [target]
     * 用于规避部分组件（如 UCrop）对 HEIF（尤其 HDR HEIF）解码兼容性不佳的问题
     *
     * @return 转换成功返回 true，失败（如系统无法解码 HEIF）返回 false
     */
    fun convertHeifToJpeg(
        context: Context,
        uri: Uri,
        target: File,
        maxSize: Int = 4096,
        quality: Int = 95,
    ): Boolean = runCatching {
        val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, boundsOptions)
        }
        val loadOptions = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(boundsOptions, maxSize, maxSize)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, loadOptions)
        } ?: return@runCatching false
        // 将 EXIF 旋转烘焙进像素，输出的 JPEG 不再带方向信息，避免下游二次旋转
        val oriented = correctImageOrientation(context, uri, decoded)
        try {
            target.outputStream().use { output ->
                oriented.compress(Bitmap.CompressFormat.JPEG, quality, output)
            }
            true
        } finally {
            recycleBitmapSafely(oriented)
        }
    }.onFailure {
        it.printStackTrace()
    }.getOrDefault(false)

    private val HEIF_BRANDS = setOf(
        "heic", "heix", "heim", "heis",
        "hevc", "hevx", "hevm", "hevs",
        "mif1", "msf1", "heif",
    )

    /**
     * 从图片中解析二维码
     *
     * @param bitmap 要解析的图片
     * @return 二维码内容，解析失败返回null
     */
    fun decodeQRCodeFromBitmap(bitmap: Bitmap): String? {
        return runCatching {
            val width = bitmap.width
            val height = bitmap.height
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

            val source = RGBLuminanceSource(width, height, pixels)
            val binaryBitmap = BinaryBitmap(HybridBinarizer(source))

            val reader = MultiFormatReader()
            val result = reader.decode(binaryBitmap)

            result.text
        }.onFailure {
            it.printStackTrace()
        }.getOrNull()
    }

    /**
     * 从URI加载图片并解析二维码（组合方法）
     *
     * @param context Android上下文
     * @param uri 图片URI
     * @param maxSize 最大尺寸限制，默认1024px
     * @return 二维码内容，解析失败返回null
     */
    fun decodeQRCodeFromUri(
        context: Context,
        uri: Uri,
        maxSize: Int = 1024
    ): String? {
        val bitmap = loadOptimizedBitmap(context, uri, maxSize) ?: return null
        return try {
            decodeQRCodeFromBitmap(bitmap)
        } finally {
            bitmap.recycle() // 确保释放内存
        }
    }

    /**
     * 安全地回收Bitmap内存
     *
     * @param bitmap 要回收的bitmap
     */
    fun recycleBitmapSafely(bitmap: Bitmap?) {
        bitmap?.let {
            if (!it.isRecycled) {
                it.recycle()
            }
        }
    }

    /**
     * 获取图片的基本信息（不加载到内存）
     *
     * @param context Android上下文
     * @param uri 图片URI
     * @return ImageInfo包含宽度、高度、MIME类型等信息
     */
    fun getImageInfo(context: Context, uri: Uri): ImageInfo? {
        return runCatching {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }

            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                BitmapFactory.decodeStream(inputStream, null, options)
            }

            if (options.outWidth > 0 && options.outHeight > 0) {
                ImageInfo(
                    width = options.outWidth,
                    height = options.outHeight,
                    mimeType = options.outMimeType
                )
            } else null
        }.onFailure {
            it.printStackTrace()
        }.getOrNull()
    }

    /**
     * 获取酒馆角色卡中的角色元数据（如果存在）
     *
     * @param context Android上下文
     * @param uri 图片URI
     * @return Result<String> 包含角色元数据的Result对象
     */
    fun getTavernCharacterMeta(context: Context, uri: Uri): Result<String> = runCatching {
        val metadata = context.contentResolver.openInputStream(uri)?.use { ImageMetadataReader.readMetadata(it) }
        if (metadata == null) error("Metadata is null, please check if the image is a character card")
        if (!metadata.containsDirectoryOfType(PngDirectory::class.java)) error("No PNG directory found, please check if the image is a character card")

        val pngDirectory = metadata.getDirectoriesOfType(PngDirectory::class.java)
            .firstOrNull { directory ->
                directory.pngChunkType == PngChunkType.tEXt
                    && directory.getString(PngDirectory.TAG_TEXTUAL_DATA).startsWith("[chara:")
            } ?: error("No tEXt chunk found, please check if the image is a character card")

        val value = pngDirectory.getString(PngDirectory.TAG_TEXTUAL_DATA)

        val regex = Regex("""\[chara:\s*(.+?)]""")
        return Result.success(regex.find(value)?.groupValues?.get(1) ?: error("No character data found"))
    }

    data class ImageInfo(
        val width: Int,
        val height: Int,
        val mimeType: String?
    )
}
