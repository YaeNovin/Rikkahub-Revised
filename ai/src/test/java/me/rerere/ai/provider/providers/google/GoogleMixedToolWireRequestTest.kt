package me.rerere.ai.provider.providers.google

import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.*
import me.rerere.ai.ui.UIMessage
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

/** Captures the serialized, authenticated HTTP request and stops before any network access. */
class GoogleMixedToolWireRequestTest {
    @Test fun `outgoing Google request carries the mixed tool flag and safe diagnostics`() = runBlocking {
        for (vertex in listOf(false, true)) {
            val captured = AtomicReference<Request>()
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                captured.set(chain.request())
                throw IOException("Test request captured without contacting a provider")
            }.build()
            try {
                val provider = GoogleProvider(client)
                val params = TextGenerationParams(
                    model = Model(modelId = "gemini-3.5-flash", abilities = listOf(ModelAbility.TOOL), tools = setOf(BuiltInTools.Search)),
                    tools = listOf(Tool("get_session_capabilities", "Session tools", parameters = { InputSchema.Obj(buildJsonObject {}) }, execute = { emptyList() })),
                    customBody = listOf(CustomBody("tool_config", buildJsonObject {
                        put("include_server_side_tool_invocations", false)
                        put("function_calling_config", buildJsonObject { put("mode", "AUTO") })
                    })),
                )
                val result = runCatching {
                    withTimeout(5_000) { provider.generateText(ProviderSetting.Google(apiKey = "test-only-key", vertexAI = vertex),
                        listOf(UIMessage.user("private test prompt")), params) }
                }
                assertTrue(result.isFailure)
                val request = requireNotNull(captured.get())
                val buffer = Buffer()
                requireNotNull(request.body).writeTo(buffer)
                val wire = Json.parseToJsonElement(buffer.readUtf8()).jsonObject
                assertEquals(JsonPrimitive(true), wire.getValue("toolConfig").jsonObject["includeServerSideToolInvocations"])
                assertEquals("VALIDATED", wire.getValue("toolConfig").jsonObject.getValue("functionCallingConfig").jsonObject.getValue("mode").jsonPrimitive.content)
                assertFalse("tool_config" in wire)
                assertTrue(wire.getValue("tools").jsonArray.any { "googleSearch" in it.jsonObject })
                assertTrue(wire.getValue("tools").jsonArray.any { "functionDeclarations" in it.jsonObject })
                assertTrue(request.url.encodedPath.endsWith(":generateContent"))
                val diagnostics = requireNotNull(request.tag(ProviderRequestDiagnostics::class.java)).parameters
                assertEquals("true", diagnostics["toolConfig.includeServerSideToolInvocations"])
                assertEquals("VALIDATED", diagnostics["toolConfig.functionCallingConfig.mode"])
                assertEquals("true", diagnostics["tools.googleSearch"])
                assertEquals("1", diagnostics["tools.functionDeclarations"])
                assertFalse(diagnostics.toString().contains("test-only-key"))
                assertFalse(diagnostics.toString().contains("private test prompt"))
            } finally {
                client.dispatcher.executorService.shutdownNow()
                client.connectionPool.evictAll()
            }
        }
    }

    @Test fun `stream request diagnostics report the same mandatory flag without user content`() {
        val request = Json.parseToJsonElement("""{"tools":[{"googleSearch":{}},{"functionDeclarations":[{"name":"ask_user"}]}]}""").jsonObject
            .withGoogleToolContextCirculation()
        val diagnostics = buildGoogleRequestDiagnostics(ProviderSetting.Google(), Model(modelId = "gemini-3.5-flash"),
            ProviderRequestOperation.STREAM_TEXT, request)
        assertEquals("true", diagnostics.parameters["toolConfig.includeServerSideToolInvocations"])
        assertEquals("VALIDATED", diagnostics.parameters["toolConfig.functionCallingConfig.mode"])
        assertEquals("1", diagnostics.parameters["tools.functionDeclarations"])
    }
}
