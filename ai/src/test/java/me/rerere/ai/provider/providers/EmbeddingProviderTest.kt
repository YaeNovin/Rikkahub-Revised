package me.rerere.ai.provider.providers

import kotlinx.coroutines.runBlocking
import me.rerere.ai.provider.EmbeddingImageInput
import me.rerere.ai.provider.EmbeddingGenerationParams
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
            ),
        )

        assertTrue(requestPath.endsWith("/embeddings/multimodal"))
        assertTrue(requestBody.contains("\"type\":\"text\""))
        assertTrue(requestBody.contains("\"type\":\"image_url\""))
        assertTrue(requestBody.contains("data:image/png;base64,aGVsbG8="))
        assertEquals(listOf(1.0f, 0.0f), result.embeddings[0])
        assertEquals(1, result.embeddings.size)
    }

    @Test
    fun `Google batch embedding response is parsed`() = runBlocking {
        var requestPath = ""
        val provider = GoogleProvider(
            clientWithResponse(
                """
                {"embeddings":[
                  {"values":[1.0,0.0]},
                  {"values":[0.0,1.0]}
                ]}
                """.trimIndent()
            ) { requestPath = it.url.encodedPath }
        )

        val result = provider.generateEmbedding(
            ProviderSetting.Google(apiKey = "test"),
            EmbeddingGenerationParams(
                model = Model(modelId = "text-embedding-004", type = ModelType.EMBEDDING),
                input = listOf("first", "second"),
            ),
        )

        assertTrue(requestPath.endsWith("/v1beta/models/text-embedding-004:batchEmbedContents"))
        assertEquals(listOf(1.0f, 0.0f), result.embeddings[0])
        assertEquals(listOf(0.0f, 1.0f), result.embeddings[1])
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
