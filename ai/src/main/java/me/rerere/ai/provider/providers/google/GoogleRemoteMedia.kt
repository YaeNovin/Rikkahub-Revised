package me.rerere.ai.provider.providers.google

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.http.awaitAndUse
import okhttp3.Authenticator
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.ByteString.Companion.toByteString

private const val REMOTE_IMAGE_BUDGET = 20L * 1024 * 1024

/** GenerateContent inlineData needs bytes, never a remote URL. Keep downloads request-local. */
internal suspend fun prepareGoogleRemoteImages(client: OkHttpClient, messages: List<UIMessage>): List<UIMessage> = withContext(Dispatchers.IO) {
    val downloads = mutableMapOf<String, String>()
    var remaining = REMOTE_IMAGE_BUDGET
    val mediaClient = client.newBuilder().authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE).build()
    suspend fun part(value: UIMessagePart): UIMessagePart = when (value) {
        is UIMessagePart.Image -> if (value.url.startsWith("https://") || value.url.startsWith("http://")) {
            val data = downloads[value.url] ?: run {
                require(remaining > 0) { "远程图片总大小超过 20 MiB，请缩小图片或使用本地附件。" }
                // Deliberately omit provider keys, custom headers and provider request tags.
                mediaClient.newCall(Request.Builder().url(value.url).get().build()).awaitAndUse { response ->
                    require(response.isSuccessful) { "远程图片下载失败（HTTP ${response.code}），请重新选择附件。" }
                    val body = requireNotNull(response.body) { "远程图片为空。" }
                    require(body.contentLength() <= remaining) { "远程图片总大小超过 20 MiB。" }
                    val source = body.source()
                    source.request(remaining + 1)
                    val bytes = source.buffer.readByteArray()
                    require(bytes.isNotEmpty() && bytes.size <= remaining) { "远程图片为空或总大小超过 20 MiB。" }
                    val mime = body.contentType()?.let { "${it.type}/${it.subtype}" }
                    require(mime in setOf("image/png", "image/jpeg", "image/gif", "image/webp", "image/heic", "image/heif")) {
                        "远程图片的 Content-Type 不受支持，请下载后作为本地图片发送。"
                    }
                    remaining -= bytes.size
                    "data:$mime;base64,${bytes.toByteString().base64()}"
                }.also { downloads[value.url] = it }
            }
            value.copy(url = data)
        } else value
        is UIMessagePart.Tool -> value.copy(output = value.output.map { output -> part(output) })
        else -> value
    }
    messages.map { message -> message.copy(parts = message.parts.map { part(it) }) }
}
