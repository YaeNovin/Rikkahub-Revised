package me.rerere.rikkahub.data.ai

import me.rerere.ai.provider.ProviderRequestChannel
import me.rerere.ai.provider.ProviderRequestDiagnostics
import me.rerere.ai.provider.ProviderRequestOperation
import me.rerere.common.android.LogEntry
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestLoggingInterceptorTest {
    private val diagnostics = ProviderRequestDiagnostics(
        provider = "OpenAI",
        model = "gpt-test",
        channel = ProviderRequestChannel.OPENAI_API,
        operation = ProviderRequestOperation.TEXT_GENERATION,
        parameters = mapOf("temperature" to "0.7"),
    )

    @Test
    fun `redacts sensitive request headers and URL query parameters`() {
        val headers = Headers.Builder()
            .add("x-goog-api-key", "secret-google-key")
            .add("Authorization", "Bearer secret-token")
            .add("X-Custom-Credential", "custom-secret")
            .add("Content-Type", "application/json")
            .build()
            .toSafeMap()
        val url = "https://example.com/generate?key=secret-query&alt=sse"
            .toHttpUrl()
            .toSafeLogUrl()

        assertEquals("[REDACTED]", headers["x-goog-api-key"])
        assertEquals("[REDACTED]", headers["Authorization"])
        assertEquals("application/json", headers["Content-Type"])
        assertEquals("[REDACTED]", headers["X-Custom-Credential"])
        assertFalse(url.contains("secret-query"))
        assertTrue(url.contains("key=%5BREDACTED%5D"))
        assertTrue(url.contains("alt=sse"))
    }

    @Test
    fun `sanitizes prompts schemas attachments and stop sequences while retaining parameters`() {
        val sanitized = sanitizeRequestBody(
            """
            {
              "generationConfig": {
                "seed": 42,
                "imageSize": "4K",
                "customPrivateField": "PRIVATE_CUSTOM_VALUE",
                "responseJsonSchema": {"type":"object"},
                "stopSequences": ["PRIVATE_STOP"]
              },
              "contents": [{"parts": [
                {"text":"private prompt"},
                {"inlineData":{"mimeType":"image/png","data":"PRIVATE_BASE64"}},
                {"fileData":{"fileUri":"https://private.example/file"}}
              ]}]
            }
            """.trimIndent()
        )

        assertTrue(sanitized.contains("\"seed\":42"))
        assertTrue(sanitized.contains("\"imageSize\":\"4K\""))
        assertTrue(sanitized.contains("[TEXT omitted: 14 characters]"))
        assertTrue(sanitized.contains("[SCHEMA omitted]"))
        assertTrue(sanitized.contains("[1 sequences omitted]"))
        assertTrue(sanitized.contains("[BINARY omitted: 14 characters]"))
        assertTrue(sanitized.contains("[URI omitted]"))
        assertFalse(sanitized.contains("private prompt"))
        assertFalse(sanitized.contains("PRIVATE_BASE64"))
        assertFalse(sanitized.contains("PRIVATE_STOP"))
        assertFalse(sanitized.contains("private.example"))
        assertFalse(sanitized.contains("PRIVATE_CUSTOM_VALUE"))
    }

    @Test
    fun `provider request with HTTP recording produces one unified provider log`() {
        val entry = buildUnifiedRequestLog(
            diagnostics = diagnostics,
            recordHttpRequest = true,
            url = "https://api.example.com/v1/responses",
            method = "POST",
            requestHeaders = mapOf("Authorization" to "[REDACTED]"),
            requestBody = "{\"model\":\"gpt-test\"}",
            responseCode = 400,
            responseHeaders = mapOf("x-request-id" to "request-1"),
            durationMs = 123,
        )

        assertTrue(entry is LogEntry.ProviderRequestLog)
        entry as LogEntry.ProviderRequestLog
        assertEquals("OpenAI", entry.provider)
        assertEquals("POST", entry.method)
        assertEquals("https://api.example.com/v1/responses", entry.url)
        assertEquals("[REDACTED]", entry.requestHeaders["Authorization"])
        assertEquals("{\"model\":\"gpt-test\"}", entry.requestBody)
        assertEquals("request-1", entry.responseHeaders["x-request-id"])
        assertEquals(400, entry.responseCode)
    }

    @Test
    fun `provider summary remains available without optional HTTP recording`() {
        val entry = buildUnifiedRequestLog(
            diagnostics = diagnostics,
            recordHttpRequest = false,
            responseCode = 200,
            durationMs = 50,
        ) as LogEntry.ProviderRequestLog

        assertEquals("gpt-test", entry.model)
        assertNull(entry.url)
        assertNull(entry.method)
        assertTrue(entry.requestHeaders.isEmpty())
        assertNull(entry.requestBody)
        assertTrue(entry.responseHeaders.isEmpty())
    }

    @Test
    fun `ordinary HTTP request remains a request log`() {
        val entry = buildUnifiedRequestLog(
            diagnostics = null,
            recordHttpRequest = true,
            url = "https://example.com/health",
            method = "GET",
            responseCode = 204,
        )

        assertTrue(entry is LogEntry.RequestLog)
        entry as LogEntry.RequestLog
        assertEquals("GET", entry.method)
        assertEquals(204, entry.responseCode)
    }

    @Test
    fun `extracts nested Google and Anthropic provider error messages`() {
        assertEquals(
            "The requested image size is unsupported.",
            extractLoggedResponseError(
                """{"error":{"code":400,"message":"The requested image size is unsupported.","status":"INVALID_ARGUMENT"}}"""
            ),
        )
        assertEquals(
            "max_tokens must be greater than thinking budget",
            extractLoggedResponseError(
                """{"type":"error","error":{"type":"invalid_request_error","message":"max_tokens must be greater than thinking budget"}}"""
            ),
        )
    }

    @Test
    fun `redacts credentials from provider error messages`() {
        val error = extractLoggedResponseError(
            """{"message":"key=secret-query Authorization: Bearer secret-bearer api_key='secret-json'"}"""
        ).orEmpty()

        assertFalse(error.contains("secret-query"))
        assertFalse(error.contains("secret-bearer"))
        assertFalse(error.contains("secret-json"))
        assertTrue(error.contains("[REDACTED]"))
    }

    @Test
    fun `limits provider error reason length`() {
        val error = extractLoggedResponseError("x".repeat(4_096))

        assertEquals(2_048, error?.length)
    }
}
