package me.rerere.rikkahub.data.ai

import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestLoggingInterceptorTest {
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
}
