package me.rerere.rikkahub.ui.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.ui.graphics.Color
import androidx.core.net.toFile
import androidx.palette.graphics.Palette
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val PALETTE_BITMAP_TARGET_SIZE = 384
private const val PALETTE_CONNECT_TIMEOUT_MILLIS = 10_000
private const val PALETTE_READ_TIMEOUT_MILLIS = 15_000
private const val FOREGROUND_SAMPLE_COLUMNS = 12
private const val FOREGROUND_SAMPLE_ROWS = 20

suspend fun extractBackgroundAccent(
    context: Context,
    source: String,
): Long? = withContext(Dispatchers.IO) {
    val bitmap = decodeSampledBitmap(context, source) ?: return@withContext null
    try {
        val palette = Palette.from(bitmap)
            .maximumColorCount(24)
            .generate()
        val swatch = palette.vibrantSwatch
            ?: palette.lightVibrantSwatch
            ?: palette.darkVibrantSwatch
            ?: palette.mutedSwatch
            ?: palette.dominantSwatch
        swatch?.rgb?.toLong()?.and(0xFFFF_FFFFL)
    } finally {
        bitmap.recycle()
    }
}

suspend fun extractBackgroundForeground(
    context: Context,
    source: String,
    imageOpacity: Float,
    overlayColor: Color,
    overlayAlpha: Float,
): Color? = withContext(Dispatchers.IO) {
    val bitmap = decodeSampledBitmap(context, source) ?: return@withContext null
    try {
        val effectiveBackgrounds = buildList {
            repeat(FOREGROUND_SAMPLE_ROWS) { row ->
                val y = ((row + 0.5f) * bitmap.height / FOREGROUND_SAMPLE_ROWS)
                    .toInt()
                    .coerceIn(0, bitmap.height - 1)
                repeat(FOREGROUND_SAMPLE_COLUMNS) { column ->
                    val x = ((column + 0.5f) * bitmap.width / FOREGROUND_SAMPLE_COLUMNS)
                        .toInt()
                        .coerceIn(0, bitmap.width - 1)
                    add(
                        compositeChatBackgroundColor(
                            imageColor = Color(bitmap.getPixel(x, y)),
                            imageOpacity = imageOpacity,
                            overlayColor = overlayColor,
                            overlayAlpha = overlayAlpha,
                        )
                    )
                }
            }
        }
        readableForegroundColor(effectiveBackgrounds)
    } finally {
        bitmap.recycle()
    }
}

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
    }
    return openImageStream(context, source)?.use { stream ->
        BitmapFactory.decodeStream(stream, null, options)
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
