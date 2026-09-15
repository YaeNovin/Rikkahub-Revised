package me.rerere.ai.provider.providers.google

import kotlinx.serialization.json.*
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.stream.SseEvent
import me.rerere.ai.ui.*
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class GoogleServerToolProtocolTest {
    private fun obj(value: String) = Json.parseToJsonElement(value).jsonObject
    private val call = obj("""{"toolCall":{"id":"s1","toolType":"GOOGLE_SEARCH_WEB","args":{"query":"weather"}},"thoughtSignature":"opaque-call"}""")
    private val result = obj("""{"toolResponse":{"id":"s1","toolType":"GOOGLE_SEARCH_WEB","response":{"result":"sunny"}},"thoughtSignature":"opaque-result"}""")
    private fun replay(message: UIMessage): JsonArray {
        val method = GoogleProvider::class.java.getDeclaredMethod("buildContents", List::class.java).apply { isAccessible = true }
        return method.invoke(GoogleProvider(OkHttpClient()), listOf(message)) as JsonArray
    }

    @Test fun `tool config is merged after custom tools and never enabled for a single tool category`() {
        val mixed = obj("""{"tools":[{"functionDeclarations":[{"name":"ask_user"}]},{"googleSearch":{}}],"tool_config":{"include_server_side_tool_invocations":false,"functionCallingConfig":{"mode":"AUTO"}}}""")
        val config = mixed.withGoogleToolContextCirculation().getValue("toolConfig").jsonObject
        assertEquals(JsonPrimitive(true), config["includeServerSideToolInvocations"])
        assertEquals("AUTO", config.getValue("functionCallingConfig").jsonObject.getValue("mode").jsonPrimitive.content)
        assertFalse(config.containsKey("include_server_side_tool_invocations"))
        listOf("""{"tools":[{"googleSearch":{}}]}""", """{"tools":[{"functionDeclarations":[{"name":"ask_user"}]}]}""").forEach {
            val body = obj(it)
            assertEquals(body, body.withGoogleToolContextCirculation())
        }
    }

    @Test fun `streamed builtin call result and client call keep original order and signatures`() {
        val decoder = GoogleStreamDecoder("response", "gemini-3.8-flash")
        val handler = StreamChunkHandler()
        var messages = listOf(UIMessage.user("weather"))
        fun accept(chunks: List<StreamChunk>) { chunks.forEach { messages = handler.handle(messages, it) } }
        val wire = listOf(call, obj("""{"text":"checking","thoughtSignature":"signed-text"}"""), result,
            obj("""{"functionCall":{"id":"client1","name":"ask_user","args":{"questions":[]}},"thoughtSignature":"client-signature"}"""))
        wire.forEach { part ->
            val data = buildJsonObject { putJsonArray("candidates") { add(buildJsonObject {
                put("content", buildJsonObject { put("role", "model"); put("parts", JsonArray(listOf(part))) })
            }) } }
            accept(decoder.accept(SseEvent(data = data.toString())).chunks)
        }
        accept(decoder.accept(SseEvent(data = """{"candidates":[{"finishReason":"STOP"}]}""")).chunks)
        accept(decoder.onClosed())
        val message = messages.last().let { reply -> reply.copy(parts = reply.parts.map { part ->
            if (part is UIMessagePart.Tool) part.copy(output = listOf(UIMessagePart.Text("{}"))) else part
        }) }
        assertEquals(1, message.getTools().size)
        assertEquals(2, message.parts.filterIsInstance<UIMessagePart.ServerTool>().size)
        assertTrue(message.parts.filterIsInstance<UIMessagePart.ServerTool>().all { it.isFinished })
        val first = replay(message).first().jsonObject.getValue("parts").jsonArray
        assertEquals(wire, first.toList())
        // Serialization/restart does not remove opaque protocol context.
        val restored = Json.decodeFromString<UIMessage>(Json.encodeToString(UIMessage.serializer(), message))
        assertEquals(replay(message), replay(restored))
    }

    @Test fun `nonstream parts preserve server context without becoming executable functions`() {
        val parts = listOf(parseGoogleServerToolPart(call)!!, UIMessagePart.Text("middle"), parseGoogleServerToolPart(result)!!)
        val message = UIMessage(role = MessageRole.ASSISTANT, parts = completeGoogleServerCalls(parts))
        assertTrue(message.getTools().isEmpty())
        assertEquals(listOf(call, obj("""{"text":"middle"}"""), result), replay(message).single().jsonObject.getValue("parts").jsonArray.toList())
    }
}
