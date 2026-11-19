package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.*
import okhttp3.ResponseBody
import okio.*

internal const val LOG_BODY_LIMIT = 256L * 1024

/** Measures consumption, not just response headers. Never reads ahead or buffers binary bodies. */
internal fun ResponseBody.observeLogCompletion(onComplete: (String, String?) -> Unit): ResponseBody {
    val original = this
    var finished = false
    fun finish(phase: String, error: String? = null) {
        if (!finished) { finished = true; runCatching { onComplete(phase, error) } }
    }
    val observed = object : ForwardingSource(source()) {
        override fun read(sink: Buffer, byteCount: Long): Long = try {
            super.read(sink, byteCount).also { if (it == -1L) finish("body_complete") }
        } catch (e: Exception) {
            finish("failed", e.message ?: e.javaClass.simpleName)
            throw e
        }
        override fun close() {
            try { super.close() } finally { finish("closed") }
        }
    }.buffer()
    return object : ResponseBody() {
        override fun contentType() = original.contentType()
        override fun contentLength() = original.contentLength()
        override fun source() = observed
    }
}
internal fun detailedLogBody(body: String, secrets: List<String> = emptyList()): String =
    me.rerere.common.android.DiagnosticSanitizer.body(body, secrets)

internal fun decodeLogBytes(bytes: ByteArray, encoding: String?, charset: java.nio.charset.Charset = Charsets.UTF_8): String {
    val input = Buffer().write(bytes)
    val gzipMagic = bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()
    val decoded = if (gzipMagic) {
        GzipSource(input).buffer().use { source ->
            source.request(LOG_BODY_LIMIT + 1)
            if (source.buffer.size > LOG_BODY_LIMIT) return "[body omitted: decompressed size limit exceeded]"
            source.readByteArray()
        }
    } else {
        if (!encoding.isNullOrBlank() && encoding.lowercase() !in setOf("gzip", "identity")) return "[body omitted: unsupported encoding $encoding]"
        bytes
    }
    return charset.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
        .decode(java.nio.ByteBuffer.wrap(decoded)).toString()
}

internal fun ResponseBody.captureForLog(encoding: String?, onComplete: (String) -> Unit): ResponseBody {
    val original = this
    val collected = Buffer()
    var overflow = false
    var completed = false
    fun finish() {
        if (completed) return
        completed = true
        val value = if (overflow) "[body omitted: size limit exceeded]" else runCatching {
            decodeLogBytes(collected.readByteArray(), encoding, original.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8)
        }.getOrDefault("[body omitted: incomplete, binary or invalid encoding]")
        runCatching { onComplete(value) }
    }
    val source = object : ForwardingSource(original.source()) {
        override fun read(sink: Buffer, byteCount: Long): Long {
            val before = sink.size
            return try {
                val read = super.read(sink, byteCount)
                if (read > 0) {
                    val copy = minOf(read, LOG_BODY_LIMIT - collected.size)
                    if (copy > 0) sink.copyTo(collected, before, copy)
                    if (copy < read) overflow = true
                } else if (read == -1L) finish()
                read
            } catch (e: Exception) { finish(); throw e }
        }
        override fun close() { try { super.close() } finally { finish() } }
    }.buffer()
    return object : ResponseBody() {
        override fun contentType() = original.contentType()
        override fun contentLength() = original.contentLength()
        override fun source() = source
    }
}
