package me.rerere.ai.provider

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelContextWindowTest {
    @Test
    fun `parses and formats compact K and M context capacities`() {
        assertEquals(128_000, parseContextWindowTokens("128K"))
        assertEquals(1_000_000, parseContextWindowTokens("1000K"))
        assertEquals(1_000_000, parseContextWindowTokens("1m"))
        assertEquals("128K", formatContextWindowTokens(128_000))
        assertEquals("1M", formatContextWindowTokens(1_000_000))
        assertEquals(null, parseContextWindowTokens("1.5M"))
    }

    @Test
    fun `extracts common direct and nested context capacity fields`() {
        val values = listOf(
            "{\"inputTokenLimit\":1048576}" to 1_048_576,
            "{\"context_length\":\"128000\"}" to 128_000,
            "{\"context_window_tokens\":32768}" to 32_768,
            "{\"limits\":{\"max_input_tokens\":200000}}" to 200_000,
            "{\"architecture\":{\"max_context_length\":65536}}" to 65_536,
        )

        values.forEach { (body, expected) ->
            val model = Json.parseToJsonElement(body).jsonObject
            assertEquals(expected, model.contextWindowTokensOrNull())
        }
    }

    @Test
    fun `ignores malformed nonpositive and implausibly large capacities`() {
        val values = listOf(
            "{\"context_length\":0}",
            "{\"context_length\":-1}",
            "{\"context_length\":\"not-a-number\"}",
            "{\"context_length\":10000001}",
        )

        values.forEach { body ->
            val model = Json.parseToJsonElement(body).jsonObject
            assertEquals(null, model.contextWindowTokensOrNull())
        }
    }
}
