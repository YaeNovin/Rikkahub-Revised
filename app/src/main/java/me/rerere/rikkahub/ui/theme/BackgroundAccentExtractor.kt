package me.rerere.rikkahub.ui.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import android.net.Uri
import androidx.compose.ui.graphics.Color
import androidx.core.net.toFile
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.material3.analyzeImageColors
import me.rerere.material3.centerImageCrop

private const val PALETTE_BITMAP_TARGET_SIZE = 384
private const val PALETTE_CONNECT_TIMEOUT_MILLIS = 10_000
private const val PALETTE_READ_TIMEOUT_MILLIS = 15_000
private const val FOREGROUND_SAMPLE_COLUMNS = 12
private const val FOREGROUND_SAMPLE_ROWS = 20

internal data class BackgroundBitmapAnalysis(val rawSamples: List<Color>, val seedArgb: Int?)
private data class BitmapAnalysisKey(val source: String, val modified: Long, val size: Long, val aspect: Int?)
private val bitmapAnalysisMutex = Mutex()
private val bitmapAnalysisCache = object : LinkedHashMap<BitmapAnalysisKey, BackgroundBitmapAnalysis>(8, .75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<BitmapAnalysisKey, BackgroundBitmapAnalysis>?): Boolean = size > 8
}

internal fun cachedBackgroundBitmapAnalysis(source: String?, aspectRatio: Float?): BackgroundBitmapAnalysis? {
    val aspect = aspectRatio?.takeIf { it.isFinite() && it > 0 }?.let { (it * 1000).toInt() }
    return synchronized(bitmapAnalysisCache) {
        bitmapAnalysisCache.entries.lastOrNull { it.key.source == source && it.key.aspect == aspect }?.value
    }
}

/** Decode and analyze each source once. Theme, opacity and text-mode changes reuse the result. */
internal suspend fun extractBackgroundBitmapAnalysis(context: Context, source: String,
    forceRefresh: Boolean = false, aspectRatio: Float? = null): BackgroundBitmapAnalysis? {
    val uri = Uri.parse(source)
    val local = if (uri.scheme == "file") uri.toFile() else if (uri.scheme == null) File(source) else null
    val aspect = aspectRatio?.takeIf { it.isFinite() && it > 0 }?.let { (it * 1000).toInt() }
    val key = BitmapAnalysisKey(source, local?.lastModified() ?: 0, local?.length() ?: 0, aspect)
    return bitmapAnalysisMutex.withLock {
        if (!forceRefresh) synchronized(bitmapAnalysisCache) { bitmapAnalysisCache[key] }?.let { return@withLock it }
        val disk = local?.takeIf { it.isFile }?.let {
            BackgroundAnalysisDiskCache(File(context.cacheDir, "background-palettes-v3"))
        }
        val diskKey = "${key.source}\n${key.modified}\n${key.size}\n${key.aspect}"
        if (!forceRefresh) withContext(Dispatchers.IO) { disk?.read(diskKey) }?.let {
            synchronized(bitmapAnalysisCache) { bitmapAnalysisCache[key] = it }
            return@withLock it
        }
        val decoded = withContext(Dispatchers.IO) { decodeSampledBitmap(context, source) } ?: return@withLock null
        val crop = centerImageCrop(decoded.width, decoded.height, aspectRatio)
        val bitmap = Bitmap.createBitmap(decoded, crop.left, crop.top, crop.width, crop.height)
        if (bitmap !== decoded) decoded.recycle()
        try {
            val result = withContext(Dispatchers.Default) {
                val samples = buildList {
                    repeat(FOREGROUND_SAMPLE_ROWS) { row -> repeat(FOREGROUND_SAMPLE_COLUMNS) { column ->
                        val x = ((column + .5f) * bitmap.width / FOREGROUND_SAMPLE_COLUMNS).toInt().coerceIn(0, bitmap.width - 1)
                        val y = ((row + .5f) * bitmap.height / FOREGROUND_SAMPLE_ROWS).toInt().coerceIn(0, bitmap.height - 1)
                        add(Color(bitmap.getPixel(x, y)))
                    } }
                }
                val scale = (128f / maxOf(bitmap.width, bitmap.height)).coerceAtMost(1f)
                val scaled = if (scale < 1f) Bitmap.createScaledBitmap(bitmap,
                    (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true) else bitmap
                val seed = try {
                    val pixels = IntArray(scaled.width * scaled.height)
                    scaled.getPixels(pixels, 0, scaled.width, 0, 0, scaled.width, scaled.height)
                    analyzeImageColors(pixels).seedArgb
                } finally { if (scaled !== bitmap) scaled.recycle() }
                BackgroundBitmapAnalysis(samples, seed)
            }
            synchronized(bitmapAnalysisCache) { bitmapAnalysisCache[key] = result }
            withContext(Dispatchers.IO) { disk?.write(diskKey, result) }
            result
        } finally { bitmap.recycle() }
    }
}

suspend fun extractBackgroundAccent(context: Context, source: String): Long? =
    extractBackgroundBitmapAnalysis(context, source)?.seedArgb?.toLong()?.and(0xFFFF_FFFFL)

suspend fun extractBackgroundForeground(context: Context, source: String, imageOpacity: Float,
    overlayColor: Color, overlayAlpha: Float): Color? = extractBackgroundBitmapAnalysis(context, source)?.let { image ->
        readableForegroundColor(image.rawSamples.map { compositeChatBackgroundColor(it, imageOpacity, overlayColor, overlayAlpha) })
    }

internal suspend fun extractBackgroundSamples(context: Context, source: String, opacity: Float, base: Color): List<Color>? =
    extractBackgroundBitmapAnalysis(context, source)?.rawSamples?.map { compositeChatBackgroundColor(it, opacity, base, 0f) }

private fun decodeSampledBitmap(context: Context, source: String): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    openImageStream(context, source)?.use { stream ->
        BitmapFactory.decodeStream(stream, null, bounds)
    }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sampleSize = 1
    while (
        bounds.outWidth / sampleSize > PALETTE_BITMAP_TARGET_SIZE * 2 ||
        bounds.outHeight / sampleSize > PALETTE_BITMAP_TARGET_SIZE * 2
    ) {
        sampleSize *= 2
    }

    val options = BitmapFactory.Options().apply {
        inSampleSize = sampleSize
        inPreferredConfig = Bitmap.Config.ARGB_8888
        inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
    }
    val decoded = openImageStream(context, source)?.use { stream ->
        BitmapFactory.decodeStream(stream, null, options)
    } ?: return null
    val orientation = runCatching {
        openImageStream(context, source)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }
    }.getOrNull()
    val matrix = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
        else -> return decoded
    }
    return Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true).also {
        if (it !== decoded) decoded.recycle()
    }
}

private fun openImageStream(context: Context, source: String): InputStream? {
    val uri = Uri.parse(source)
    return when (uri.scheme?.lowercase()) {
        "http", "https" -> openNetworkStream(source)
        "file" -> FileInputStream(uri.toFile())
        "content", "android.resource" -> context.contentResolver.openInputStream(uri)
        null -> File(source).takeIf(File::isFile)?.inputStream()
        else -> context.contentResolver.openInputStream(uri)
    }
}

private fun openNetworkStream(source: String): InputStream {
    val connection = (URL(source).openConnection() as HttpURLConnection).apply {
        connectTimeout = PALETTE_CONNECT_TIMEOUT_MILLIS
        readTimeout = PALETTE_READ_TIMEOUT_MILLIS
        instanceFollowRedirects = true
        connect()
    }
    val responseCode = connection.responseCode
    if (responseCode !in 200..299) {
        connection.disconnect()
        error("Background image request failed with HTTP $responseCode")
    }
    return object : FilterInputStream(connection.inputStream) {
        override fun close() {
            try {
                super.close()
            } finally {
                connection.disconnect()
            }
        }
    }
}
