package me.rerere.ai.provider.providers

import kotlinx.coroutines.runBlocking
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.providers.claude.ClaudeProvider
import me.rerere.ai.provider.providers.google.GoogleProvider
import me.rerere.ai.provider.providers.openai.OpenAIProvider
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderModelDiscoveryTest {
    @Test
    fun `OpenAI discovery falls back by model family and honors API metadata`() = runBlocking {
        val provider = OpenAIProvider(
            clientWithResponse(
                """
                {"data":[
                  {"id":"gpt-5"},
                  {"id":"gpt-4o","context_length":64000}
                ]}
                """.trimIndent()
            )
        )

        val models = provider.listModels(
            ProviderSetting.OpenAI(apiKey = "test", baseUrl = "https://example.test/v1")
        )

        assertEquals(400_000, models[0].contextWindowTokens)
        assertEquals(64_000, models[1].contextWindowTokens)
    }

    @Test
    fun `Google discovery reads input limit and falls back for compatible responses`() = runBlocking {
        val provider = GoogleProvider(
            clientWithResponse(
                """
                {"models":[
                  {
                    "name":"models/gemini-2.5-pro",
                    "displayName":"Gemini 2.5 Pro",
                    "supportedGenerationMethods":["generateContent"],
                    "inputTokenLimit":2000000
                  },
                  {
                    "name":"models/gemini-2.0-flash",
                    "displayName":"Gemini 2.0 Flash",
                    "supportedGenerationMethods":["generateContent"]
                  }
                ]}
                """.trimIndent()
            )
        )

        val models = provider.listModels(ProviderSetting.Google(apiKey = "test"))

        assertEquals(2_000_000, models[0].contextWindowTokens)
        assertEquals(1_048_576, models[1].contextWindowTokens)
    }

    @Test
    fun `Anthropic discovery falls back by model family and honors proxy metadata`() = runBlocking {
        val provider = ClaudeProvider(
            clientWithResponse(
                """
                {"data":[
                  {"id":"claude-sonnet-4-5","display_name":"Claude Sonnet 4.5"},
                  {"id":"claude-custom","display_name":"Claude Custom","context_window":1000000}
                ]}
                """.trimIndent()
            )
        )

        val models = provider.listModels(
            ProviderSetting.Claude(apiKey = "test", baseUrl = "https://example.test/v1")
        )

        assertEquals(200_000, models[0].contextWindowTokens)
        assertEquals(1_000_000, models[1].contextWindowTokens)
    }

    private fun clientWithResponse(body: String): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(Interceptor { chain ->
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
