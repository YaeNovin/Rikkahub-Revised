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
            "{\"contextWindow\":\"128K\"}" to 128_000,
            "{\"context_window_tokens\":32768}" to 32_768,
            "{\"limits\":{\"max_input_tokens\":200000}}" to 200_000,
            "{\"architecture\":{\"max_context_length\":65536}}" to 65_536,
            "{\"top_provider\":{\"context_length\":131072}}" to 131_072,
            "{\"Model_Info\":{\"MAX_MODEL_LEN\":262144}}" to 262_144,
            "{\"metadata\":{\"maxPositionEmbeddings\":32768}}" to 32_768,
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

    @Test
    fun `uses protocol fallback only when discovery metadata is absent`() {
        val empty = Json.parseToJsonElement("{}").jsonObject
        val explicit = Json.parseToJsonElement("{\"context_length\":64000}").jsonObject

        assertEquals(
            400_000,
            empty.contextWindowTokensOrNull("gpt-5", ModelDiscoveryProtocol.OPENAI),
        )
        assertEquals(
            1_050_000,
            empty.contextWindowTokensOrNull("gpt-5.6", ModelDiscoveryProtocol.OPENAI),
        )
        assertEquals(
            400_000,
            empty.contextWindowTokensOrNull("gpt-5.4-mini", ModelDiscoveryProtocol.OPENAI),
        )
        assertEquals(
            1_048_576,
            empty.contextWindowTokensOrNull("gemini-2.5-flash", ModelDiscoveryProtocol.GOOGLE),
        )
        assertEquals(
            200_000,
            empty.contextWindowTokensOrNull("claude-sonnet-4-5", ModelDiscoveryProtocol.ANTHROPIC),
        )
        assertEquals(
            1_000_000,
            empty.contextWindowTokensOrNull("claude-opus-4-6", ModelDiscoveryProtocol.ANTHROPIC),
        )
        assertEquals(
            131_072,
            empty.contextWindowTokensOrNull("gemini-3.1-flash-image-preview", ModelDiscoveryProtocol.GOOGLE),
        )
        assertEquals(
            64_000,
            explicit.contextWindowTokensOrNull("gpt-5", ModelDiscoveryProtocol.OPENAI),
        )
        assertEquals(
            200_000,
            empty.contextWindowTokensOrNull("anthropic/claude-sonnet-4-5", ModelDiscoveryProtocol.OPENAI),
        )
        assertEquals(
            null,
            empty.contextWindowTokensOrNull("custom-deployment", ModelDiscoveryProtocol.OPENAI),
        )
    }

    @Test
    fun `merges discovered capacities without replacing manual settings`() {
        val configured = listOf(
            Model(modelId = "gpt-5"),
            Model(modelId = "gpt-4o", contextWindowTokens = 96_000),
            Model(modelId = "custom-deployment"),
        )
        val discovered = listOf(
            Model(modelId = "OPENAI/GPT-5", contextWindowTokens = 400_000),
            Model(modelId = "gpt-4o", contextWindowTokens = 128_000),
        )

        val merged = mergeDiscoveredContextWindows(configured, discovered)

        assertEquals(400_000, merged[0].contextWindowTokens)
        assertEquals(96_000, merged[1].contextWindowTokens)
        assertEquals(null, merged[2].contextWindowTokens)
        assertEquals(configured.map(Model::id), merged.map(Model::id))
    }
}
