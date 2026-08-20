package me.rerere.ai.provider.providers.openai

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import me.rerere.ai.ui.ImageGenerationItem
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.Reader
import java.util.Base64

internal suspend fun parseOpenAIImageResponse(
    reader: Reader,
    defaultFormat: String,
    maxChars: Long,
    createBase64File: suspend (InputStream) -> File,
    resolveUrl: suspend (String) -> ImageGenerationItem,
    emitItem: suspend (ImageGenerationItem) -> Unit,
) {
    val cursor = JsonCursor(reader, maxChars)
    val pendingImages = mutableListOf<PendingImage>()
    var responseFormat = defaultFormat
    var hasData = false

    try {
        cursor.beginObject()
        var firstProperty = true
        while (true) {
            val name = cursor.nextObjectName(firstProperty) ?: break
            firstProperty = false
            when (name) {
                "output_format" -> responseFormat = cursor.readNullableString() ?: responseFormat
                "data" -> {
                    hasData = true
                    parseImageArray(cursor, createBase64File, pendingImages)
                }
                else -> cursor.skipValue()
            }
        }
        check(hasData) { "No data in image response" }

        pendingImages.forEach { pending ->
            currentCoroutineContext().ensureActive()
            val item = if (pending.temporaryFile != null) {
                val temporaryFile = checkNotNull(pending.temporaryFile)
                pending.temporaryFile = null
                ImageGenerationItem(
                    mimeType = pending.mimeType?.toImageMimeType()
                        ?: (pending.outputFormat ?: responseFormat).toImageMimeType(),
                    seed = pending.seed,
                    temporaryFilePath = temporaryFile.absolutePath,
                )
            } else {
                resolveUrl(checkNotNull(pending.url) { "No b64_json or url in image response" }).let { item ->
                    if (pending.seed == null) item else item.copy(seed = pending.seed)
                }
            }

            try {
                emitItem(item)
            } catch (e: Throwable) {
                item.temporaryFilePath?.let(::File)?.delete()
                throw e
            }
        }
    } finally {
        pendingImages.forEach { pending ->
            pending.temporaryFile?.delete()
        }
    }
}

private suspend fun parseImageArray(
    cursor: JsonCursor,
    createBase64File: suspend (InputStream) -> File,
    output: MutableList<PendingImage>,
) {
    cursor.beginArray()
    var firstItem = true
    while (cursor.hasNextArrayItem(firstItem)) {
        firstItem = false
        output += parseImageItem(cursor, createBase64File)
    }
}

private suspend fun parseImageItem(
    cursor: JsonCursor,
    createBase64File: suspend (InputStream) -> File,
): PendingImage {
    var temporaryFile: File? = null
    var imageUrl: String? = null
    var outputFormat: String? = null
    var mimeType: String? = null
    var seed: Long? = null

    try {
        cursor.beginObject()
        var firstProperty = true
        while (true) {
            val name = cursor.nextObjectName(firstProperty) ?: break
            firstProperty = false
            when (name) {
                "b64_json" -> {
                    temporaryFile?.delete()
                    temporaryFile = if (cursor.tryReadNull()) {
                        null
                    } else {
                        cursor.openBase64Stream().use { input ->
                            createBase64File(input)
                        }
                    }
                }
                "url" -> imageUrl = cursor.readNullableString()
                "output_format" -> outputFormat = cursor.readNullableString()
                "mime_type" -> mimeType = cursor.readNullableString()
                "seed" -> seed = cursor.readNullableLong()
                else -> cursor.skipValue()
            }
        }
        check(temporaryFile != null || imageUrl != null) { "No b64_json or url in image response" }
        return PendingImage(
            temporaryFile = temporaryFile,
            url = imageUrl,
            outputFormat = outputFormat,
            mimeType = mimeType,
            seed = seed,
        )
    } catch (e: Throwable) {
        temporaryFile?.delete()
        throw e
    }
}

private data class PendingImage(
    var temporaryFile: File?,
    val url: String?,
    val outputFormat: String?,
    val mimeType: String?,
    val seed: Long?,
)

private class JsonCursor(
    private val reader: Reader,
    private val maxChars: Long,
) {
    private val buffer = CharArray(JSON_BUFFER_CHARS)
    private var bufferPosition = 0
    private var bufferLimit = 0
    private var totalChars = 0L
    private var peeked = NO_PEEKED_CHAR

    fun beginObject() = expect('{')

    fun beginArray() = expect('[')

    fun nextObjectName(firstProperty: Boolean): String? {
        skipWhitespace()
        if (peekChar() == '}'.code) {
            readChar()
            return null
        }
        if (!firstProperty) expect(',')
        val name = readString()
        expect(':')
        return name
    }

    fun hasNextArrayItem(firstItem: Boolean): Boolean {
        skipWhitespace()
        if (peekChar() == ']'.code) {
            readChar()
            return false
        }
        if (!firstItem) expect(',')
        return true
    }

    fun readNullableString(): String? {
        if (tryReadNull()) return null
        return readString()
    }

    fun readNullableLong(): Long? {
        if (tryReadNull()) return null
        skipWhitespace()
        return if (peekChar() == '"'.code) {
            readString().toLongOrNull()
        } else {
            readNumberString().toLongOrNull()
        }
    }

    fun tryReadNull(): Boolean {
        skipWhitespace()
        if (peekChar() != 'n'.code) return false
        readLiteral("null")
        return true
    }

    fun openBase64Stream(): InputStream {
        skipWhitespace()
        expectRaw('"')
        return ChunkedBase64InputStream(JsonStringInputStream(this))
    }

    fun skipValue(depth: Int = 0) {
        require(depth <= MAX_JSON_DEPTH) { "Image response is nested too deeply" }
        skipWhitespace()
        when (peekChar()) {
            '"'.code -> skipString()
            '{'.code -> {
                beginObject()
                var firstProperty = true
                while (true) {
                    nextObjectName(firstProperty) ?: break
                    firstProperty = false
                    skipValue(depth + 1)
                }
            }
            '['.code -> {
                beginArray()
                var firstItem = true
                while (hasNextArrayItem(firstItem)) {
                    firstItem = false
                    skipValue(depth + 1)
                }
            }
            't'.code -> readLiteral("true")
            'f'.code -> readLiteral("false")
            'n'.code -> readLiteral("null")
            '-'.code, in '0'.code..'9'.code -> readNumberString()
            else -> throw IOException("Invalid JSON value in image response")
        }
    }

    fun readJsonStringByte(): Int {
        val value = readChar()
        if (value < 0) throw IOException("Unterminated JSON string in image response")
        if (value == '"'.code) return -1
        val decoded = if (value == '\\'.code) readEscapedChar() else value
        if (decoded < 0x20 || decoded > 0x7f) {
            throw IOException("Image response contains invalid base64 data")
        }
        return decoded
    }

    private fun readString(): String {
        skipWhitespace()
        expectRaw('"')
        val result = StringBuilder()
        while (true) {
            val value = readChar()
            when {
                value < 0 -> throw IOException("Unterminated JSON string in image response")
                value == '"'.code -> return result.toString()
                value == '\\'.code -> result.append(readEscapedChar().toChar())
                value < 0x20 -> throw IOException("Invalid control character in image response")
                else -> result.append(value.toChar())
            }
            if (result.length > MAX_METADATA_STRING_CHARS) {
                throw IOException("Image response metadata is too large")
            }
        }
    }

    private fun skipString() {
        expectRaw('"')
        while (true) {
            val value = readChar()
            when {
                value < 0 -> throw IOException("Unterminated JSON string in image response")
                value == '"'.code -> return
                value == '\\'.code -> readEscapedChar()
                value < 0x20 -> throw IOException("Invalid control character in image response")
            }
        }
    }

    private fun readEscapedChar(): Int = when (val escaped = readChar()) {
        '"'.code, '\\'.code, '/'.code -> escaped
        'b'.code -> '\b'.code
        'f'.code -> '\u000c'.code
        'n'.code -> '\n'.code
        'r'.code -> '\r'.code
        't'.code -> '\t'.code
        'u'.code -> readUnicodeEscape()
        else -> throw IOException("Invalid JSON escape in image response")
    }

    private fun readUnicodeEscape(): Int {
        var value = 0
        repeat(4) {
            val digit = readChar()
            val hex = when (digit) {
                in '0'.code..'9'.code -> digit - '0'.code
                in 'a'.code..'f'.code -> digit - 'a'.code + 10
                in 'A'.code..'F'.code -> digit - 'A'.code + 10
                else -> throw IOException("Invalid Unicode escape in image response")
            }
            value = value * 16 + hex
        }
        return value
    }

    private fun readNumberString(): String {
        val result = StringBuilder()
        while (true) {
            val value = peekChar()
            if (value < 0 || value.toChar().isWhitespace() || value == ','.code || value == ']'.code || value == '}'.code) {
                break
            }
            if (value != '-'.code && value != '+'.code && value != '.'.code && value != 'e'.code &&
                value != 'E'.code && value !in '0'.code..'9'.code
            ) {
                throw IOException("Invalid number in image response")
            }
            result.append(readChar().toChar())
            if (result.length > MAX_NUMBER_CHARS) throw IOException("JSON number is too large")
        }
        if (result.isEmpty()) throw IOException("Invalid number in image response")
        return result.toString()
    }

    private fun readLiteral(expected: String) {
        expected.forEach { expectedChar ->
            if (readChar() != expectedChar.code) {
                throw IOException("Invalid JSON literal in image response")
            }
        }
    }

    private fun expect(expected: Char) {
        skipWhitespace()
        expectRaw(expected)
    }

    private fun expectRaw(expected: Char) {
        if (readChar() != expected.code) {
            throw IOException("Expected '$expected' in image response")
        }
    }

    private fun skipWhitespace() {
        while (peekChar() >= 0 && peekChar().toChar().isWhitespace()) {
            readChar()
        }
    }

    private fun peekChar(): Int {
        if (peeked == NO_PEEKED_CHAR) peeked = readRawChar()
        return peeked
    }

    private fun readChar(): Int {
        if (peeked != NO_PEEKED_CHAR) {
            return peeked.also { peeked = NO_PEEKED_CHAR }
        }
        return readRawChar()
    }

    private fun readRawChar(): Int {
        if (bufferPosition >= bufferLimit) {
            bufferLimit = reader.read(buffer)
            bufferPosition = 0
            if (bufferLimit < 0) return -1
            totalChars += bufferLimit
            if (totalChars > maxChars) throw IOException("Image response is too large")
        }
        return buffer[bufferPosition++].code
    }
}

private class JsonStringInputStream(
    private val cursor: JsonCursor,
) : InputStream() {
    private var finished = false
    private var closed = false

    override fun read(): Int {
        if (closed || finished) return -1
        return cursor.readJsonStringByte().also { value ->
            if (value < 0) finished = true
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (offset < 0 || length < 0 || offset > buffer.size - length) throw IndexOutOfBoundsException()
        if (length == 0) return 0
        if (closed || finished) return -1

        var count = 0
        while (count < length) {
            val value = cursor.readJsonStringByte()
            if (value < 0) {
                finished = true
                break
            }
            buffer[offset + count] = value.toByte()
            count++
        }
        return if (count == 0 && finished) -1 else count
    }

    override fun close() {
        if (!closed && !finished) {
            val drainBuffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (read(drainBuffer) >= 0) {
                // Keep the JSON cursor aligned when a decoder stops at padding.
            }
        }
        closed = true
    }
}

private class ChunkedBase64InputStream(
    private val encodedSource: InputStream,
) : InputStream() {
    private val decoder = Base64.getDecoder()
    private val encodedBuffer = ByteArray(BASE64_ENCODED_CHUNK_BYTES)
    private val decodedBuffer = ByteArray(BASE64_DECODED_CHUNK_BYTES)
    private var decodedPosition = 0
    private var decodedLimit = 0
    private var finished = false
    private var closed = false

    override fun read(): Int {
        if (!ensureDecodedData()) return -1
        return decodedBuffer[decodedPosition++].toInt() and 0xff
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (offset < 0 || length < 0 || offset > buffer.size - length) throw IndexOutOfBoundsException()
        if (length == 0) return 0
        if (!ensureDecodedData()) return -1

        val count = minOf(length, decodedLimit - decodedPosition)
        decodedBuffer.copyInto(buffer, offset, decodedPosition, decodedPosition + count)
        decodedPosition += count
        return count
    }

    override fun close() {
        if (!closed) encodedSource.close()
        closed = true
    }

    private fun ensureDecodedData(): Boolean {
        if (closed) return false
        if (decodedPosition < decodedLimit) return true
        if (finished) return false

        var encodedCount = 0
        while (encodedCount < encodedBuffer.size) {
            val read = encodedSource.read(encodedBuffer, encodedCount, encodedBuffer.size - encodedCount)
            if (read < 0) {
                finished = true
                break
            }
            if (read > 0) encodedCount += read
        }
        if (encodedCount == 0) return false

        val source = if (encodedCount == encodedBuffer.size) encodedBuffer else encodedBuffer.copyOf(encodedCount)
        decodedPosition = 0
        decodedLimit = decoder.decode(source, decodedBuffer)
        return decodedLimit > 0
    }
}

internal fun String.toImageMimeType(): String = when (lowercase().substringBefore(';')) {
    "jpg", "jpeg", "image/jpg", "image/jpeg" -> "image/jpeg"
    "webp", "image/webp" -> "image/webp"
    else -> "image/png"
}

private const val JSON_BUFFER_CHARS = 16 * 1024
private const val BASE64_ENCODED_CHUNK_BYTES = 64 * 1024
private const val BASE64_DECODED_CHUNK_BYTES = BASE64_ENCODED_CHUNK_BYTES / 4 * 3
private const val MAX_METADATA_STRING_CHARS = 64 * 1024
private const val MAX_NUMBER_CHARS = 128
private const val MAX_JSON_DEPTH = 64
private const val NO_PEEKED_CHAR = -2
