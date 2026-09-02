package me.rerere.search

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class AnySearchServiceTest {
    @Test
    fun searchClientDoesNotInheritLongModelStreamingTimeout() {
        val streamingClient = OkHttpClient.Builder()
            .readTimeout(10, TimeUnit.MINUTES)
            .build()

        SearchService.init(streamingClient)

        assertEquals(45_000, SearchService.httpClient.readTimeoutMillis)
        assertEquals(60_000, SearchService.httpClient.callTimeoutMillis)
    }

    @Test
    fun buildSearchRequestClampsResultSizeAndOmitsBlankOptions() {
        val options = SearchServiceOptions.AnySearchOptions(
            zone = "  ",
            language = "",
        )

        val tooSmall = buildAnySearchRequest("kotlin", 0, options)
        val tooLarge = buildAnySearchRequest("kotlin", 50, options)

        assertEquals(1, tooSmall.getValue("max_results").jsonPrimitive.int)
        assertEquals(10, tooLarge.getValue("max_results").jsonPrimitive.int)
        assertEquals("json", tooSmall.getValue("format").jsonPrimitive.content)
        assertFalse("zone" in tooSmall)
        assertFalse("language" in tooSmall)
    }

    @Test
    fun buildSearchRequestTrimsZoneAndLanguage() {
        val request = buildAnySearchRequest(
            query = "kotlin",
            resultSize = 5,
            options = SearchServiceOptions.AnySearchOptions(
                zone = " intl ",
                language = " en ",
            )
        )

        assertEquals("intl", request.getValue("zone").jsonPrimitive.content)
        assertEquals("en", request.getValue("language").jsonPrimitive.content)
    }

    @Test
    fun searchResponsePrefersContentAndFallsBackToSnippet() {
        val result = parseAnySearchSearchResponse(
            """
            {
              "code": 0,
              "message": "success",
              "request_id": "req-1",
              "data": {
                "results": [
                  {"title":"First","url":"https://example.com/1","content":"full","snippet":"short"},
                  {"title":"Second","url":"https://example.com/2","snippet":"fallback"},
                  {"title":"","url":"https://example.com/3","content":"","snippet":"blank fallback"},
                  {"title":"Ignored","url":"","content":"no link"}
                ],
                "metadata": {"total_results": 4}
              }
            }
            """.trimIndent()
        )

        assertEquals(3, result.items.size)
        assertEquals("full", result.items[0].text)
        assertEquals("fallback", result.items[1].text)
        assertEquals("(Untitled)", result.items[2].title)
        assertEquals("blank fallback", result.items[2].text)
    }

    @Test
    fun apiErrorIncludesMessageAndRequestId() {
        val error = assertThrows(IllegalStateException::class.java) {
            parseAnySearchSearchResponse(
                """
                {
                  "code": -1,
                  "message": "Invalid zone",
                  "request_id": "req-error",
                  "error_code": "INVALID_ZONE"
                }
                """.trimIndent()
            )
        }

        assertTrue(error.message.orEmpty().contains("Invalid zone"))
        assertTrue(error.message.orEmpty().contains("INVALID_ZONE"))
        assertTrue(error.message.orEmpty().contains("req-error"))
    }

    @Test
    fun extractResponseMapsContentAndTitle() {
        val result = parseAnySearchExtractResponse(
            responseBody = """
                {
                  "code": 0,
                  "message": "success",
                  "request_id": "req-2",
                  "data": {
                    "url": "https://example.com/final",
                    "title": "Example",
                    "content": "Extracted page"
                  }
                }
            """.trimIndent(),
            requestedUrl = "https://example.com/original"
        )

        assertEquals(1, result.urls.size)
        assertEquals("https://example.com/final", result.urls.single().url)
        assertEquals("Extracted page", result.urls.single().content)
        assertEquals("Example", result.urls.single().metadata?.title)
    }

    @Test
    fun optionsAreRegisteredAndSerializable() {
        val options = SearchServiceOptions.AnySearchOptions(
            apiKey = "key",
            zone = "cn",
            language = "zh-CN",
        )

        assertSame(AnySearchService, SearchService.getService(options))
        assertEquals("AnySearch", options.displayName)

        val encoded = Json.encodeToString(SearchServiceOptions.serializer(), options)
        val decoded = Json.decodeFromString(SearchServiceOptions.serializer(), encoded)

        assertTrue(encoded.contains("\"type\":\"anysearch\""))
        assertEquals(options, decoded)
    }
}
