package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.MediaInputSupport
import me.rerere.ai.provider.RESPONSES_MEDIA_MAX_INLINE_BYTES
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.util.Base64

/** Request-local, bounded raw-file encoding. Never transcribes, extracts frames or caches media. */
internal fun prepareResponsesMedia(
    messages: List<UIMessage>,
    support: MediaInputSupport,
    checkCancelled: () -> Unit = {},
): Map<UIMessagePart, JsonObject> {
    return describeResponsesMedia(messages, support, checkCancelled).associate { (part, source) ->
        checkCancelled()
        val encoded = source.encode(checkCancelled)
        part to buildJsonObject {
            if (part is UIMessagePart.Audio) {
                put("type", "input_audio")
                put("input_audio", buildJsonObject {
                    put("data", encoded)
                    put("format", source.format)
                })
            } else {
                put("type", "input_video")
                put("video_url", "data:${source.mime};base64,$encoded")
            }
        }
    }
}

/** Cheap preflight for imported files; no binary reads or base64 encoding. */
fun validateResponsesMediaAttachments(parts: List<UIMessagePart>, support: MediaInputSupport) {
    describeResponsesMedia(listOf(UIMessage(role = MessageRole.USER, parts = parts)), support) {}
}

private fun describeResponsesMedia(
    messages: List<UIMessage>,
    support: MediaInputSupport,
    checkCancelled: () -> Unit,
): List<Pair<UIMessagePart, ResponsesMediaSource>> {
    val sources = messages.flatMap { message ->
        message.parts.mapNotNull { part ->
            val audio = part is UIMessagePart.Audio
            val url = when (part) {
                is UIMessagePart.Audio -> part.url
                is UIMessagePart.Video -> part.url
                else -> return@mapNotNull null
            }
            require(support.responsesExtensions && if (audio) support.audio else support.video) {
                "当前模型或 Responses 端点未启用音视频输入。请在供应商设置中启用 Gemini 音视频兼容扩展，或切换为 Google 协议。"
            }
            require(message.role == MessageRole.USER) { "Responses 音视频附件仅支持用户消息。" }
            checkCancelled()
            val source = ResponsesMediaSource.open(url, audio, part.metadata?.get("mime_type")?.jsonPrimitive?.contentOrNull)
            require(!audio || support.audioFormats == null || source.format in support.audioFormats) {
                "该 Responses 端点支持的音频格式：${support.audioFormats?.joinToString("、") { it.uppercase() }}。请提供匹配的原文件，或切换为 Google 协议；应用不会将文件改名伪装成其他格式。"
            }
            part to source
        }
    }
    // Validate the complete batch before reading any local file or building base64 strings.
    val budget = support.maxInlineBytes ?: RESPONSES_MEDIA_MAX_INLINE_BYTES
    require(sources.sumOf { it.second.size } <= budget) {
        "本次音视频原文件合计超过 14 MiB。请缩短文件，或切换为支持大文件上传的协议；应用不会自动转码或抽帧。"
    }
    return sources
}

private data class ResponsesMediaSource(
    val mime: String,
    val format: String,
    val size: Long,
    val file: File? = null,
    val data: String? = null,
) {
    fun encode(checkCancelled: () -> Unit): String {
        data?.let { payload ->
            // Bound first, then validate base64. This validates the container bytes only.
            require(payload.length <= (RESPONSES_MEDIA_MAX_INLINE_BYTES + 2) / 3 * 4) {
                "音视频附件超过 14 MiB。"
            }
            require(runCatching { Base64.getDecoder().decode(payload).size.toLong() == size }.getOrDefault(false)) {
                "音视频附件的 Base64 数据无效，请重新选择文件。"
            }
            checkCancelled()
            return payload
        }
        val source = requireNotNull(file)
        val output = ByteArrayOutputStream()
        Base64.getEncoder().wrap(output).use { encoded ->
            source.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                var count = 0L
                while (true) {
                    checkCancelled()
                    val read = input.read(buffer)
                    if (read < 0) break
                    count += read
                    require(count <= size) { "音视频文件在读取期间发生变化，请重新选择文件。" }
                    encoded.write(buffer, 0, read)
                }
                require(count == size) { "音视频文件不完整，请重新选择文件。" }
            }
        }
        return output.toString(Charsets.US_ASCII.name())
    }

    companion object {
        fun open(url: String, audio: Boolean, mimeHint: String?): ResponsesMediaSource {
            if (url.startsWith("data:")) {
                val separator = url.indexOf(";base64,")
                require(separator > 5) { "音视频附件必须使用 Base64 data URL。" }
                require(url.length.toLong() - separator - 8 <= (RESPONSES_MEDIA_MAX_INLINE_BYTES + 2) / 3 * 4) {
                    "音视频附件超过 14 MiB。"
                }
                val (mime, format) = mediaType(url.substring(5, separator), audio)
                val payload = url.substring(separator + 8)
                require(payload.isNotEmpty() && payload.length % 4 == 0) { "音视频附件的 Base64 数据无效。" }
                val size = payload.length.toLong() / 4 * 3 - payload.takeLast(2).count { it == '=' }
                require(size > 0) { "音视频文件为空。" }
                return ResponsesMediaSource(mime, format, size, data = payload)
            }
            val file = when {
                url.startsWith("file:") -> runCatching { File(URI(url)) }.getOrNull()
                File(url).isAbsolute -> File(url)
                else -> null
            }
            require(file?.isFile == true && file.canRead()) {
                "无法读取音视频附件，请重新选择本地文件。本阶段不下载远程音视频。"
            }
            val declaredMime = mimeHint?.substringBefore(';')?.trim()?.takeUnless {
                it.contains('*') || it == "application/octet-stream"
            }
            val (mime, format) = mediaType(declaredMime ?: file.extension.lowercase(), audio)
            val size = file.length()
            require(size > 0) { "音视频文件为空。" }
            return ResponsesMediaSource(mime, format, size, file = file)
        }

        private fun mediaType(value: String, audio: Boolean): Pair<String, String> =
            if (audio) when (value.lowercase()) {
                "mp3", "mp2", "mpga", "audio/mp3", "audio/mpeg", "audio/x-mp3" -> "audio/mpeg" to "mp3"
                "wav", "wave", "audio/wav", "audio/x-wav", "audio/wave" -> "audio/wav" to "wav"
                "aiff", "aif", "audio/aiff", "audio/x-aiff" -> "audio/aiff" to "aiff"
                "aac", "audio/aac", "audio/x-aac" -> "audio/aac" to "aac"
                "m4a", "audio/m4a", "audio/x-m4a", "audio/mp4" -> "audio/mp4" to "m4a"
                "ogg", "oga", "audio/ogg", "application/ogg" -> "audio/ogg" to "ogg"
                "opus", "audio/opus" -> "audio/opus" to "opus"
                "flac", "audio/flac", "audio/x-flac" -> "audio/flac" to "flac"
                "webm", "audio/webm" -> "audio/webm" to "webm"
                else -> throw IllegalArgumentException("此音频格式未适配 Responses 兼容扩展，请改用 MP3、WAV、AAC、OGG、FLAC、AIFF、M4A、Opus 或 WebM 原文件。")
            } else when (value.lowercase()) {
                "mp4", "m4v", "video/mp4" -> "video/mp4" to "mp4"
                "mpeg", "mpg", "video/mpeg", "video/mpg" -> "video/mpeg" to "mpeg"
                "mov", "video/mov", "video/quicktime" -> "video/quicktime" to "mov"
                "avi", "video/avi", "video/x-msvideo" -> "video/x-msvideo" to "avi"
                "flv", "video/x-flv" -> "video/x-flv" to "flv"
                "webm", "video/webm" -> "video/webm" to "webm"
                "wmv", "video/wmv", "video/x-ms-wmv" -> "video/x-ms-wmv" to "wmv"
                "3gp", "3gpp", "video/3gpp" -> "video/3gpp" to "3gpp"
                else -> throw IllegalArgumentException("此视频格式未适配 Responses 兼容扩展，请改用 MP4、MPEG、MOV、AVI、FLV、WebM、WMV 或 3GPP 原文件。")
            }
    }
}
