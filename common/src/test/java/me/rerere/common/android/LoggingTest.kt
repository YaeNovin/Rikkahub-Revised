package me.rerere.common.android

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LoggingTest {
    @Test fun `model metrics retain body and HTTP status and survive later close`() {
        Logging.clear()
        try {
            val entry = LogEntry.ProviderRequestLog(provider = "test", model = "model", channel = "CHAT", operation = "STREAM_TEXT",
                parameters = mapOf("request_id" to "request-1"), responseCode = 200, durationMs = 20, durationPhase = "headers")
            Logging.logProviderRequest(entry)
            Logging.updateResponseBody(entry.id, "answer")
            Logging.completeModelRequest("request-1", 12345678, 987654321)
            Logging.updateRequestTiming(entry.id, 5, "closed")
            val actual = Logging.getProviderRequestLogs().single()
            assertEquals(12.345678, actual.firstTokenMs!!, 0.000001)
            assertEquals(987L, actual.durationMs)
            assertEquals("model_complete", actual.durationPhase)
            assertEquals(200, actual.responseCode)
            assertEquals("answer", actual.responseBody)
        } finally { Logging.clear() }
    }

    @Test fun `older request without precision or timing fields remains readable`() {
        val entry = LogEntry.ProviderRequestLog(provider = "test", model = "model", channel = "CHAT", operation = "STREAM_TEXT")
        val encoded = Json.parseToJsonElement(json.encodeToString(entry)).jsonObject
        val legacy = JsonObject(encoded.filterKeys { it !in setOf("preciseTimestamp", "firstTokenMs", "durationPhase", "endpointOrigin") })
        val decoded = json.decodeFromString<LogEntry.ProviderRequestLog>(legacy.toString())
        assertNull(decoded.firstTokenMs)
        assertNull(decoded.preciseTimestamp)
        assertEquals("model", decoded.model)
    }
    private val json = Json { encodeDefaults = true }

    @Test
    fun `software failures are retained but user cancellation is not an error`() {
        Logging.clear()
        try {
            Logging.logSoftwareError("FileIO", "Write", IllegalStateException("disk full"))
            Logging.logSoftwareError("FileIO", "Write", java.util.concurrent.CancellationException("stopped"))
            val logs = Logging.getRecentLogs().filterIsInstance<LogEntry.ErrorLog>()
            assertEquals(1, logs.size)
            assertEquals("FileIO", logs.single().tag)
            assertEquals("disk full", logs.single().reason)
        } finally {
            Logging.clear()
        }
    }

    @Test
    fun `extracts nested provider message from Google error JSON`() {
        val details = """
            me.rerere.ai.provider.ProviderRequestException: Failed to generate image: 400 {"error":{"code":400,"message":"图片尺寸不受支持","status":"INVALID_ARGUMENT"}}
                at me.rerere.ai.provider.providers.google.GoogleProvider.generateImage(GoogleProvider.kt:1)
        """.trimIndent()

        assertEquals("图片尺寸不受支持", extractErrorReason(details, "图片供应商请求失败（HTTP 400）"))
    }

    @Test
    fun `extracts escaped OpenAI provider message`() {
        val details = """
            me.rerere.ai.provider.ProviderRequestException: Failed to edit image: HTTP 400: {"error":{"message":"Invalid value for \"size\": expected 1024x1024","type":"invalid_request_error"}}
                at me.rerere.ai.provider.providers.openai.OpenAIProvider.editImage(OpenAIProvider.kt:1)
        """.trimIndent()

        assertEquals(
            "Invalid value for \"size\": expected 1024x1024",
            extractErrorReason(details, "Image provider request failed (HTTP 400)"),
        )
    }

    @Test
    fun `falls back to deepest exception message`() {
        val details = """
            java.lang.IllegalStateException: Operation failed
                at example.First.call(First.kt:1)
            Caused by: java.lang.IllegalArgumentException: Model was not found
                at example.Second.call(Second.kt:2)
        """.trimIndent()

        assertEquals("Model was not found", extractErrorReason(details, "Operation failed"))
    }

    @Test
    fun `does not repeat a generic summary as the reason`() {
        assertNull(extractErrorReason("Request failed", "Request failed"))
    }

    @Test
    fun `old persisted error log without reason remains readable`() {
        val current = LogEntry.ErrorLog(
            name = "Image generation",
            summary = "Provider request failed",
            details = "ProviderRequestException: Invalid image size",
            reason = "Invalid image size",
        )
        val legacyJson = JsonObject(
            Json.parseToJsonElement(json.encodeToString(current))
                .jsonObject
                .filterKeys { it != "reason" }
        ).toString()

        val restored = json.decodeFromString<LogEntry.ErrorLog>(legacyJson)

        assertEquals(current.name, restored.name)
        assertEquals(current.details, restored.details)
        assertNull(restored.reason)
    }
}
