package me.rerere.ai.provider.providers.google

import me.rerere.ai.provider.ModelType
import org.junit.Assert.assertEquals
import org.junit.Test

class GoogleEmbeddingModelTypeTest {
    @Test
    fun `recognizes batch-only embedding model metadata`() {
        assertEquals(
            ModelType.EMBEDDING,
            inferGoogleModelType(
                modelId = "custom-embedding",
                supportedGenerationMethods = listOf("batchEmbedContents"),
            ),
        )
    }

    @Test
    fun `recognizes Gemini Embedding 2 by embedContent metadata`() {
        assertEquals(
            ModelType.EMBEDDING,
            inferGoogleModelType(
                modelId = "gemini-embedding-2-preview",
                supportedGenerationMethods = listOf("embedContent"),
            ),
        )
    }
}
