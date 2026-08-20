package me.rerere.ai.provider.providers.openai

import kotlinx.coroutines.runBlocking
import me.rerere.ai.ui.ImageGenerationItem
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.io.StringReader
import java.nio.file.Files
import java.util.Base64

class OpenAIImageResponseParserTest {
    @Test
    fun `base64 is decoded in chunks and late response format is applied`() = runBlocking {
        val directory = Files.createTempDirectory("openai-image-parser-test").toFile()
        try {
            val expected = ByteArray(200_000) { index -> ((index * 31 + 7) and 0xff).toByte() }
            val encoded = Base64.getEncoder().encodeToString(expected).replace("/", "\\/")
            val json = """
                {
                  "metadata": {"nested": [1, true, null, "ignored"]},
                  "data": [{"b64_json": "$encoded", "seed": 73421}],
                  "output_format": "webp"
                }
            """.trimIndent()
            val emitted = mutableListOf<ImageGenerationItem>()

            parseOpenAIImageResponse(
                reader = StringReader(json),
                defaultFormat = "png",
                maxChars = json.length.toLong(),
                createBase64File = { input -> input.writeToTemporaryFile(directory) },
                resolveUrl = { error("URL resolution was not expected") },
                emitItem = { emitted += it },
            )

            assertEquals(1, emitted.size)
            assertEquals("image/webp", emitted.single().mimeType)
            assertEquals(73421L, emitted.single().seed)
            assertArrayEquals(expected, File(checkNotNull(emitted.single().temporaryFilePath)).readBytes())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `xAI per image MIME type overrides the base64 fallback format`() = runBlocking {
        val directory = Files.createTempDirectory("xai-image-parser-mime-test").toFile()
        try {
            val json = """{"data":[{"b64_json":"aW1hZ2U=","mime_type":"image/webp"}]}"""
            val emitted = mutableListOf<ImageGenerationItem>()

            parseOpenAIImageResponse(
                reader = StringReader(json),
                defaultFormat = "jpeg",
                maxChars = json.length.toLong(),
                createBase64File = { input -> input.writeToTemporaryFile(directory) },
                resolveUrl = { error("URL resolution was not expected") },
                emitItem = { emitted += it },
            )

            assertEquals("image/webp", emitted.single().mimeType)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `temporary base64 file is deleted when collection fails`() = runBlocking {
        val directory = Files.createTempDirectory("openai-image-parser-cleanup-test").toFile()
        var temporaryFile: File? = null
        try {
            val json = """{"data":[{"b64_json":"aW1hZ2U="}]}"""
            val failure = runCatching {
                parseOpenAIImageResponse(
                    reader = StringReader(json),
                    defaultFormat = "png",
                    maxChars = json.length.toLong(),
                    createBase64File = { input ->
                        input.writeToTemporaryFile(directory).also { temporaryFile = it }
                    },
                    resolveUrl = { error("URL resolution was not expected") },
                    emitItem = { error("collector stopped") },
                )
            }.exceptionOrNull()

            assertNotNull(failure)
            assertFalse(checkNotNull(temporaryFile).exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun InputStream.writeToTemporaryFile(directory: File): File {
        val file = File.createTempFile("decoded-", ".image", directory)
        file.outputStream().use { output -> copyTo(output) }
        return file
    }
}
