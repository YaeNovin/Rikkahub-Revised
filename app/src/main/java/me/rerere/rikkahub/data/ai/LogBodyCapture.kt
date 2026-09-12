package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.*
import okhttp3.ResponseBody
import okio.*

internal const val LOG_BODY_LIMIT = 256L * 1024
private val credentialField = Regex("(?i)^(authorization|proxyauthorization|.*apikey|.*accesskey|.*secret.*|.*password.*|accesstoken|refreshtoken|token|key|cookie|setcookie|signature|credential)$")
private val credentialText = Regex("(?i)(\\b(?:bearer|basic)\\s+)[a-z0-9._~+/=-]+")
private val namedCredential = Regex("""(?i)((?:api[_-]?key|access[_-]?token|refresh[_-]?token|secret[_-]?key|password|token|signature)\s*[=:]\s*["']?)[^\s"'&,}]+""")

internal fun detailedLogBody(body: String, secrets: List<String> = emptyList()): String {
    fun scrub(text: String): String {
        var value = text
        secrets.filter { it.length >= 4 }.sortedByDescending { it.length }.forEach { value = value.replace(it, "[REDACTED]") }
        return value.replace(credentialText) { it.groupValues[1] + "[REDACTED]" }
            .replace(namedCredential) { it.groupValues[1] + "[REDACTED]" }
    }
    fun redact(element: JsonElement, depth: Int = 0): JsonElement {
        if (depth > 40) return JsonPrimitive("[nested content omitted]")
        return when (element) {
            is JsonObject -> JsonObject(element.mapValues { (key, value) ->
                if (credentialField.matches(key.replace("-", "").replace("_", ""))) JsonPrimitive("[REDACTED]") else redact(value, depth + 1)
            })
            is JsonArray -> JsonArray(element.map { redact(it, depth + 1) })
            is JsonPrimitive -> if (!element.isString) element else {
                val nested = runCatching { Json.parseToJsonElement(element.content) }.getOrNull()
                JsonPrimitive(if (nested is JsonObject || nested is JsonArray) redact(nested, depth + 1).toString() else scrub(element.content))
            }
        }
    }
    val json = runCatching { Json.parseToJsonElement(body) }.getOrNull()
    if (json != null) return redact(json).toString()
    return body.lineSequence().joinToString("\n") { line ->
        if (line.startsWith("data:")) {
            val data = line.removePrefix("data:").trimStart()
            val event = runCatching { Json.parseToJsonElement(data) }.getOrNull()
            "data: " + if (event != null) redact(event).toString() else scrub(data)
        } else scrub(line)
    }
}

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
