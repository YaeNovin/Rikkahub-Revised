package me.rerere.rikkahub.data.ai

import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.GzipSink
import okio.buffer
import org.junit.Assert.*
import org.junit.Test

class LogBodyCaptureTest {
    private fun gzip(text: String): ByteArray {
        val buffer = Buffer()
        GzipSink(buffer).buffer().use { it.writeUtf8(text) }
        return buffer.readByteArray()
    }
    @Test fun `gzip logs decode Chinese without consuming application bytes`() {
        val text = """{"message":"你好，世界"}"""
        val bytes = gzip(text)
        var logged = ""
        val wrapped = bytes.toResponseBody().captureForLog("gzip") { logged = it }
        assertArrayEquals(bytes, wrapped.bytes())
        assertEquals(text, logged)
    }
    @Test fun `magic detection and already decompressed gzip are handled`() {
        assertEquals("中文", decodeLogBytes(gzip("中文"), null))
        assertEquals("中文", decodeLogBytes("中文".toByteArray(), "gzip"))
    }
    @Test fun `compressed expansion is bounded`() {
        assertTrue(decodeLogBytes(gzip("x".repeat(LOG_BODY_LIMIT.toInt() + 1)), "gzip").contains("limit exceeded"))
    }
    @Test fun `details preserve prompts and tools but remove credentials including nested JSON`() {
        val input = """{"prompt":"你好 test-secret","tools":[{"name":"search","arguments":"{\"api_key\":\"nested-secret\",\"query\":\"天气\"}"}],"secret_key":"private"}"""
        val log = detailedLogBody(input, listOf("test-secret"))
        assertTrue(log.contains("你好")); assertTrue(log.contains("天气")); assertTrue(log.contains("search"))
        assertFalse(log.contains("test-secret")); assertFalse(log.contains("nested-secret")); assertFalse(log.contains("private"))
    }
    @Test fun `SSE is captured without rewriting stream and masks each JSON event`() {
        val stream = "data: {\"delta\":{\"content\":\"回复\"},\"api_key\":\"secret-value\"}\n\ndata: [DONE]\n"
        var result = ""
        val wrapped = stream.toResponseBody().captureForLog(null) { result = detailedLogBody(it) }
        assertEquals(stream, wrapped.string())
        assertTrue(result.contains("回复")); assertFalse(result.contains("secret-value"))
    }
}
