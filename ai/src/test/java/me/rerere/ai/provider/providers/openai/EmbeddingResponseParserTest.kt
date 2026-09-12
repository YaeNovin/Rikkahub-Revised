package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.Json
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.usesVolcengineMultimodalEmbeddingApi
import me.rerere.ai.provider.usesVolcengineTextEmbeddingApi
import me.rerere.ai.provider.supportsVolcengineMultimodalDimensions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

class EmbeddingResponseParserTest {
    @Test
    fun `parses Volcano Ark nested multimodal vector`() {
        val vector = parseEmbeddingVector(Json.parseToJsonElement("[[0.25, -0.5, 1.0]]"))

        assertEquals(listOf(0.25f, -0.5f, 1.0f), vector)
    }

    @Test
    fun `parses OpenAI base64 vector`() {
        val bytes = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .putFloat(0.5f)
            .putFloat(-1.25f)
            .array()
        val encoded = Base64.getEncoder().encodeToString(bytes)

        assertEquals(
            listOf(0.5f, -1.25f),
            parseEmbeddingVector(Json.parseToJsonElement("\"$encoded\"")),
        )
    }

    @Test
    fun `normalizes model paths for Doubao embedding detection`() {
        val multimodal = Model(modelId = "models/doubao-embedding-vision-241215")
        val text = Model(modelId = "doubao-embedding-text-240715")

        assertTrue(multimodal.usesVolcengineMultimodalEmbeddingApi())
        assertTrue(text.usesVolcengineTextEmbeddingApi())
    }

    @Test
    fun `recognizes vision embedding dimension support by model revision`() {
        assertTrue(Model(modelId = "doubao-embedding-vision-250615").supportsVolcengineMultimodalDimensions())
        assertTrue(Model(modelId = "doubao-embedding-vision-251215").supportsVolcengineMultimodalDimensions())
        assertTrue(!Model(modelId = "doubao-embedding-vision-240715").supportsVolcengineMultimodalDimensions())
    }
}
