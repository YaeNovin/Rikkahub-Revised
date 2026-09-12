package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import java.net.URI
import coil3.imageLoader
import me.rerere.rikkahub.utils.getActivity
import me.rerere.rikkahub.utils.exportImage
import coil3.network.HttpException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Only emit a category/HTTP status; exception messages can contain signed URLs. */
internal fun inlineImageFailureReason(error: Throwable): String {
    val causes = generateSequence(error) { it.cause?.takeUnless { cause -> cause === it } }.take(8).toList()
    causes.filterIsInstance<HttpException>().firstOrNull()?.let { return "HTTP ${it.response.code}" }
    return when {
        causes.any { it is UnknownHostException } -> "无法解析图片域名，请检查网络"
        causes.any { it is SocketTimeoutException || it is java.io.InterruptedIOException } -> "图片请求超时"
        causes.any { it is SSLException } -> "图片安全连接失败"
        causes.any { it is java.io.FileNotFoundException } -> "找不到图片文件"
        else -> "无法读取或解码图片"
    }
}

internal fun inlineImageFailureDetails(model: String?, error: Throwable): String {
    val host = runCatching { URI(model.orEmpty()).host }.getOrNull().orEmpty().ifBlank { "本地或内嵌图片" }
    val types = generateSequence(error) { it.cause?.takeUnless { cause -> cause === it } }.take(8)
        .joinToString(" → ") { it.javaClass.simpleName }
    return "来源：$host\n原因：${inlineImageFailureReason(error)}\n异常类型：$types"
}

internal fun isBadgeImage(url: String?): Boolean = runCatching {
    val uri = URI(url.orEmpty())
    val host = uri.host?.lowercase().orEmpty()
    val path = uri.path.orEmpty().lowercase()
    host == "img.shields.io" || host == "shields.io" || host == "badgen.net" ||
        ((host == "github.com" || host.endsWith(".github.com")) && (path.endsWith("/badge.svg") || path.contains("/badges/"))) ||
        (host in setOf("codecov.io", "coveralls.io", "badge.fury.io") && (path.contains("badge") || path.endsWith(".svg")))
}.getOrDefault(false)

internal fun isCompactBadgeImage(url: String?, intrinsic: Size): Boolean = isBadgeImage(url) ||
    (intrinsic.width.isFinite() && intrinsic.height.isFinite() && intrinsic.width in 32f..600f &&
        intrinsic.height in 12f..40f && intrinsic.width / intrinsic.height >= 2f)

internal fun inlineImageShape(url: String?, intrinsic: Size = Size.Unspecified) =
    if (isCompactBadgeImage(url, intrinsic)) RectangleShape else RoundedCornerShape(8.dp)

internal fun htmlImageDimension(value: String): Float? = value.trim().removeSuffix("px").toFloatOrNull()
    ?.takeIf { it.isFinite() && it > 0f && it <= 4096f }

internal fun requestedInlineImageSize(intrinsic: Size, width: Float?, height: Float?, maxWidth: Float, maxHeight: Float): Size {
    val ratio = if (intrinsic.width.isFinite() && intrinsic.height.isFinite() && intrinsic.width > 0f && intrinsic.height > 0f)
        intrinsic.width / intrinsic.height else 1f
    val size = when {
        width != null && height != null -> Size(width, height)
        width != null -> Size(width, width / ratio)
        height != null -> Size(height * ratio, height)
        else -> intrinsic
    }
    return inlineImageDisplaySizeDp(size, maxWidth, maxHeight)
}

internal fun safeImageLink(value: String?): String? = value?.takeIf {
    runCatching { URI(it).scheme?.lowercase() in setOf("https", "http") }.getOrDefault(false)
}

internal suspend fun saveBadgeImage(context: android.content.Context, url: String) {
    val activity = requireNotNull(context.getActivity()) { "Activity not found" }
    val bitmap = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val request = coil3.request.ImageRequest.Builder(context).data(url)
            .decoderFactory(IntrinsicSvgDecoderFactory)
            .memoryCacheKey("badge-export:v3:$url")
            .size(coil3.size.Size.ORIGINAL).build()
        val result = context.imageLoader.execute(request)
        if (result is coil3.request.ErrorResult) throw result.throwable
        val image = requireNotNull(result.image) { "徽章图像无法解码" }
        val size = inlineImageDisplaySizeDp(Size(image.width.toFloat(), image.height.toFloat()), 2048f, 1024f)
        // PNG badges may return Coil's cached bitmap from toBitmap. Export owns
        // and recycles only a copy, never the decoder/cache's image.
        requireNotNull(image.toInlineImageBitmap(size.width.toInt().coerceAtLeast(1), size.height.toInt().coerceAtLeast(1))
            .copy(android.graphics.Bitmap.Config.ARGB_8888, false))
    }
    try { context.exportImage(activity, bitmap, "badge_${System.currentTimeMillis()}.png") }
    finally { bitmap.recycle() }
}
