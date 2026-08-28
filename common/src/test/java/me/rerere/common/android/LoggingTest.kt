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
    private val json = Json { encodeDefaults = true }

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
