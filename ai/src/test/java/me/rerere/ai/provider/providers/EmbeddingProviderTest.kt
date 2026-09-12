package me.rerere.ai.provider.providers

import kotlinx.coroutines.runBlocking
import me.rerere.ai.provider.EmbeddingImageInput
import me.rerere.ai.provider.EmbeddingGenerationParams
import me.rerere.ai.provider.EmbeddingTaskType
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.providers.google.GoogleProvider
import me.rerere.ai.provider.providers.openai.OpenAIProvider
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okhttp3.MediaType.Companion.toMediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddingProviderTest {
    @Test
    fun `OpenAI model listing recognizes common local embedding model names`() = runBlocking {
        val provider = OpenAIProvider(
            clientWithResponse(
                """
                {"data":[
                  {"id":"text-embedding-3-small","context_length":8192},
                  {"id":"nomic-embed-text","architecture":{"context_window":32768}},
                  {"id":"BAAI/bge-small-en-v1.5"},
                  {"id":"gpt-4o-mini"}
                ]}
                """.trimIndent()
            )
        )

        val models = provider.listModels(
            ProviderSetting.OpenAI(apiKey = "test", baseUrl = "https://example.test/v1")
        )

        assertEquals(ModelType.EMBEDDING, models[0].type)
        assertEquals(ModelType.EMBEDDING, models[1].type)
        assertEquals(ModelType.EMBEDDING, models[2].type)
        assertEquals(ModelType.CHAT, models[3].type)
        assertEquals(8_192, models[0].contextWindowTokens)
        assertEquals(32_768, models[1].contextWindowTokens)
    }

    @Test
    fun `OpenAI embedding response is returned in input order`() = runBlocking {
        val provider = OpenAIProvider(
            clientWithResponse(
                """
                {"model":"text-embedding-3-small","data":[
                  {"index":1,"embedding":[0.0,1.0]},
                  {"index":0,"embedding":[1.0,0.0]}
                ]}
                """.trimIndent()
            )
        )

        val result = provider.generateEmbedding(
            ProviderSetting.OpenAI(apiKey = "test", baseUrl = "https://example.test/v1"),
            EmbeddingGenerationParams(
                model = Model(modelId = "text-embedding-3-small", type = ModelType.EMBEDDING),
                input = listOf("first", "second"),
            ),
        )

        assertEquals(listOf(1.0f, 0.0f), result.embeddings[0])
        assertEquals(listOf(0.0f, 1.0f), result.embeddings[1])
    }

    @Test
    fun `Doubao vision embedding uses the multimodal endpoint for text and images`() = runBlocking {
        var requestPath = ""
        var requestBody = ""
        val provider = OpenAIProvider(
            clientWithResponse(
                """
                {"model":"doubao-embedding-vision-251215","data":{"embedding":[1.0,0.0]}}
                """.trimIndent()
            ) { request ->
                requestPath = request.url.encodedPath
                requestBody = Buffer().use { buffer ->
                    request.body!!.writeTo(buffer)
                    buffer.readUtf8()
                }
            }
        )

        val result = provider.generateEmbedding(
            ProviderSetting.OpenAI(apiKey = "test", baseUrl = "https://example.test/api/v3"),
            EmbeddingGenerationParams(
                model = Model(modelId = "doubao-embedding-vision-251215", type = ModelType.EMBEDDING),
                input = listOf("a picture of a mountain"),
                images = listOf(EmbeddingImageInput(mimeType = "image/png", base64 = "aGVsbG8=")),
                dimensions = 2048,
            ),
        )

        assertTrue(requestPath.endsWith("/embeddings/multimodal"))
        assertTrue(requestBody.contains("\"type\":\"text\""))
        assertTrue(requestBody.contains("\"type\":\"image_url\""))
        assertTrue(requestBody.contains("data:image/png;base64,aGVsbG8="))
        assertTrue(requestBody.contains("\"dimensions\":2048"))
        assertEquals(listOf(1.0f, 0.0f), result.embeddings[0])
        assertEquals(1, result.embeddings.size)
    }

    @Test
    fun `Doubao vision 250615 accepts a mixed batch and returns one aggregate vector`() = runBlocking {
        var requestBody = ""
        val provider = OpenAIProvider(
            clientWithResponse(
                """{"model":"doubao-embedding-vision-251215","data":{"embedding":[0.2,0.8]}}"""
            ) { request ->
                requestBody = Buffer().use { buffer ->
                    request.body!!.writeTo(buffer)
                    buffer.readUtf8()
                }
            }
        )

        val result = provider.generateEmbedding(
            ProviderSetting.OpenAI(
                apiKey = "test",
                baseUrl = "https://ark.cn-beijing.volces.com/api/v3",
            ),
            EmbeddingGenerationParams(
                model = Model(
                    modelId = "doubao-embedding-vision-251215",
                    type = ModelType.EMBEDDING,
                ),
                input = listOf("first", "second"),
                images = listOf(
                    EmbeddingImageInput("image/png", "aA=="),
                    EmbeddingImageInput("image/jpeg", "bA=="),
                ),
            ),
        )

        assertTrue(requestBody.contains("\"text\":\"first\""))
        assertTrue(requestBody.contains("\"text\":\"second\""))
        assertTrue(requestBody.contains("data:image/png;base64,aA=="))
        assertTrue(requestBody.contains("data:image/jpeg;base64,bA=="))
        assertEquals(listOf(0.2f, 0.8f), result.embeddings.single())
    }

    @Test
    fun `Doubao text embedding uses Ark array input and endpoint`() = runBlocking {
        var requestPath = ""
        var requestBody = ""
        val provider = OpenAIProvider(
            clientWithResponse(
                """
                {"model":"doubao-embedding-text-240715","data":[
                  {"index":0,"embedding":[0.1,0.2]},
                  {"index":1,"embedding":[0.3,0.4]}
                ]}
                """.trimIndent()
            ) { request ->
                requestPath = request.url.encodedPath
                requestBody = Buffer().use { buffer ->
                    request.body!!.writeTo(buffer)
                    buffer.readUtf8()
                }
            }
        )

        val result = provider.generateEmbedding(
            ProviderSetting.OpenAI(
                apiKey = "test",
                baseUrl = "https://ark.cn-beijing.volces.com/api/v3/",
            ),
            EmbeddingGenerationParams(
                model = Model(modelId = "doubao-embedding-text-240715", type = ModelType.EMBEDDING),
                input = listOf("first", "second"),
                dimensions = 256,
            ),
        )

        assertTrue(requestPath.endsWith("/api/v3/embeddings"))
        assertTrue(requestBody.contains("\"input\":[\"first\",\"second\"]"))
        assertTrue(!requestBody.contains("encoding_format"))
        assertTrue(!requestBody.contains("dimensions"))
        assertEquals(2, result.embeddings.size)
    }

    @Test
    fun `Ark endpoint id with image input selects multimodal endpoint`() = runBlocking {
        var requestPath = ""
        val provider = OpenAIProvider(
            clientWithResponse(
                """{"data":{"embedding":[0.5,0.5]}}"""
            ) { requestPath = it.url.encodedPath }
        )

        provider.generateEmbedding(
            ProviderSetting.OpenAI(
                apiKey = "test",
                baseUrl = "https://ark.cn-beijing.volces.com/api/v3",
            ),
            EmbeddingGenerationParams(
                model = Model(modelId = "ep-vision-endpoint", type = ModelType.EMBEDDING),
                input = emptyList(),
                images = listOf(EmbeddingImageInput(mimeType = "image/png", base64 = "aGVsbG8=")),
            ),
        )

        assertTrue(requestPath.endsWith("/api/v3/embeddings/multimodal"))
    }

    @Test
    fun `Google batch embedding response is parsed`() = runBlocking {
        var requestPath = ""
        var requestBody = ""
        val provider = GoogleProvider(
            clientWithResponse(
                """
                {"embeddings":[
                  {"values":[1.0,0.0]},
                  {"values":[0.0,1.0]}
                ]}
                """.trimIndent()
            ) { request ->
                requestPath = request.url.encodedPath
                requestBody = Buffer().use { buffer ->
                    request.body!!.writeTo(buffer)
                    buffer.readUtf8()
                }
            }
        )

        val result = provider.generateEmbedding(
            ProviderSetting.Google(apiKey = "test"),
            EmbeddingGenerationParams(
                model = Model(modelId = "text-embedding-004", type = ModelType.EMBEDDING),
                input = listOf("first", "second"),
                taskType = EmbeddingTaskType.RETRIEVAL_QUERY,
            ),
        )

        assertTrue(requestPath.endsWith("/v1beta/models/text-embedding-004:batchEmbedContents"))
        assertTrue(requestBody.contains("\"taskType\":\"RETRIEVAL_QUERY\""))
        assertEquals(listOf(1.0f, 0.0f), result.embeddings[0])
        assertEquals(listOf(0.0f, 1.0f), result.embeddings[1])
    }

    @Test
    fun `Google single embedding uses batch endpoint for batch-only models`() = runBlocking {
        var requestPath = ""
        val provider = GoogleProvider(
            clientWithResponse(
                """
                {"embeddings":[{"values":[0.25,0.75]}]}
                """.trimIndent()
            ) { requestPath = it.url.encodedPath }
        )

        val result = provider.generateEmbedding(
            ProviderSetting.Google(apiKey = "test"),
            EmbeddingGenerationParams(
                model = Model(modelId = "custom-embedding", type = ModelType.EMBEDDING),
                input = listOf("one"),
            ),
        )

        assertTrue(requestPath.endsWith("/v1beta/models/custom-embedding:batchEmbedContents"))
        assertEquals(listOf(0.25f, 0.75f), result.embeddings.single())
    }

    @Test
    fun `Gemini Embedding 2 uses embedContent for text and image`() = runBlocking {
        var requestPath = ""
        var requestBody = ""
        val provider = GoogleProvider(
            clientWithResponse(
                """{"embedding":{"values":[0.1,0.9]}}"""
            ) { request ->
                requestPath = request.url.encodedPath
                requestBody = Buffer().use { buffer ->
                    request.body!!.writeTo(buffer)
                    buffer.readUtf8()
                }
            }
        )

        val result = provider.generateEmbedding(
            ProviderSetting.Google(apiKey = "test"),
            EmbeddingGenerationParams(
                model = Model(modelId = "gemini-embedding-2-preview", type = ModelType.EMBEDDING),
                input = listOf("a mountain"),
                images = listOf(EmbeddingImageInput(mimeType = "image/jpeg", base64 = "aGVsbG8=")),
                taskType = EmbeddingTaskType.RETRIEVAL_QUERY,
            ),
        )

        assertTrue(requestPath.endsWith("/v1beta/models/gemini-embedding-2-preview:embedContent"))
        assertTrue(requestBody.contains("\"inlineData\""))
        assertTrue(requestBody.contains("\"taskType\":\"RETRIEVAL_QUERY\""))
        assertEquals(listOf(0.1f, 0.9f), result.embeddings.single())
    }

    @Test
    fun `Gemini Embedding 2 text-only requests do not use batch endpoint`() = runBlocking {
        var requestPath = ""
        val provider = GoogleProvider(
            clientWithResponse(
                """{"embedding":{"values":[1.0,0.0]}}"""
            ) { requestPath = it.url.encodedPath }
        )

        provider.generateEmbedding(
            ProviderSetting.Google(apiKey = "test"),
            EmbeddingGenerationParams(
                model = Model(modelId = "gemini-embedding-2-preview", type = ModelType.EMBEDDING),
                input = listOf("a document"),
            ),
        )

        assertTrue(requestPath.endsWith("/v1beta/models/gemini-embedding-2-preview:embedContent"))
    }

    @Test
    fun `Gemini Embedding 2 aggregates multiple text and image parts`() = runBlocking {
        var requestBody = ""
        val provider = GoogleProvider(
            clientWithResponse(
                """{"embedding":{"values":[0.4,0.6]}}"""
            ) { request ->
                requestBody = Buffer().use { buffer ->
                    request.body!!.writeTo(buffer)
                    buffer.readUtf8()
                }
            }
        )

        val result = provider.generateEmbedding(
            ProviderSetting.Google(apiKey = "test"),
            EmbeddingGenerationParams(
                model = Model(modelId = "gemini-embedding-2-preview", type = ModelType.EMBEDDING),
                input = listOf("first", "second"),
                images = listOf(EmbeddingImageInput("image/jpeg", "aA==")),
            ),
        )

        assertTrue(requestBody.contains("\"text\":\"first\""))
        assertTrue(requestBody.contains("\"text\":\"second\""))
        assertTrue(requestBody.contains("\"inlineData\""))
        assertEquals(listOf(0.4f, 0.6f), result.embeddings.single())
    }

    private fun clientWithResponse(
        body: String,
        onRequest: (okhttp3.Request) -> Unit = {},
    ): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(Interceptor { chain ->
            onRequest(chain.request())
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        })
        .build()
}
