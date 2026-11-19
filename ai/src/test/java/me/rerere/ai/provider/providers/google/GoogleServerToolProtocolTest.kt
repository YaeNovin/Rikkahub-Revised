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
        assertEquals("VALIDATED", config.getValue("functionCallingConfig").jsonObject.getValue("mode").jsonPrimitive.content)
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

    @Test fun `mixed tool config keeps both custom roots and forces one canonical true flag`() {
        val body = obj("""{
            "tools":[{"functionDeclarations":[{"name":"ask_user"}]},{"googleSearch":{}}],
            "tool_config":{"include_server_side_tool_invocations":false,"retrievalConfig":{"latLng":{"latitude":1}},"functionCallingConfig":{"mode":"AUTO"}},
            "toolConfig":{"includeServerSideToolInvocations":false,"functionCallingConfig":{"allowedFunctionNames":["ask_user"]}}
        }""")
        val normalized = body.withGoogleToolContextCirculation()
        val config = normalized.getValue("toolConfig").jsonObject
        assertFalse("tool_config" in normalized)
        assertFalse("include_server_side_tool_invocations" in config)
        assertEquals(JsonPrimitive(true), config["includeServerSideToolInvocations"])
        assertEquals("VALIDATED", config.getValue("functionCallingConfig").jsonObject.getValue("mode").jsonPrimitive.content)
        assertEquals(JsonArray(listOf(JsonPrimitive("ask_user"))), config.getValue("functionCallingConfig").jsonObject["allowedFunctionNames"])
        assertEquals(body.getValue("tool_config").jsonObject["retrievalConfig"], config["retrievalConfig"])
        assertEquals(normalized, normalized.withGoogleToolContextCirculation())
    }

    @Test fun `null config and empty declaration aliases cannot hide mixed tools`() {
        val body = obj("""{"tools":[{"functionDeclarations":[],"function_declarations":[{"name":"generate_image"}]},{"google_search":{}}],"toolConfig":null,"tool_config":{"functionCallingConfig":{"mode":"AUTO"}}}""")
        val config = body.withGoogleToolContextCirculation().getValue("toolConfig").jsonObject
        assertEquals(JsonPrimitive(true), config["includeServerSideToolInvocations"])
        assertEquals("VALIDATED", config.getValue("functionCallingConfig").jsonObject.getValue("mode").jsonPrimitive.content)
    }

    @Test fun `builtin aliases enable circulation only with nonempty function declarations`() {
        for (builtin in listOf("googleSearch", "google_search", "urlContext", "url_context", "codeExecution", "code_execution", "googleMaps", "google_maps", "fileSearch", "file_search")) {
            val mixed = obj("""{"tools":[{"function_declarations":[{"name":"get_session_capabilities"}]},{"$builtin":{}}]}""")
            assertEquals(JsonPrimitive(true), mixed.withGoogleToolContextCirculation().getValue("toolConfig").jsonObject["includeServerSideToolInvocations"])
            val onlyBuiltin = obj("""{"tools":[{"functionDeclarations":[]},{"$builtin":{}}]}""")
            assertEquals(onlyBuiltin, onlyBuiltin.withGoogleToolContextCirculation())
        }
    }

    @Test fun `tool combination defaults to validated without relaxing explicit modes`() {
        for (mode in listOf(null, "AUTO", "ANY", "NONE", "VALIDATED")) {
            val body = buildJsonObject {
                put("tools", obj("""{"tools":[{"googleSearch":{}},{"functionDeclarations":[{"name":"ask_user"}]}]}""").getValue("tools"))
                put("toolConfig", buildJsonObject { put("functionCallingConfig", buildJsonObject {
                    mode?.let { put("mode", it) }
                    put("allowedFunctionNames", JsonArray(listOf(JsonPrimitive("ask_user"))))
                }) })
            }
            val config = body.withGoogleToolContextCirculation().getValue("toolConfig").jsonObject.getValue("functionCallingConfig").jsonObject
            assertEquals(mode.takeUnless { it == "AUTO" } ?: "VALIDATED", config.getValue("mode").jsonPrimitive.content)
            assertEquals(JsonArray(listOf(JsonPrimitive("ask_user"))), config["allowedFunctionNames"])
        }
    }

    @Test fun `snake case function config is normalized while preserving overlay priority`() {
        val body = obj("""{
            "tools":[{"googleSearch":{}},{"functionDeclarations":[{"name":"ask_user"}]}],
            "tool_config":{"functionCallingConfig":{"mode":"AUTO","allowed_function_names":["ask_user"]}},
            "toolConfig":{"function_calling_config":{"mode":"NONE"}}
        }""")
        val config = body.withGoogleToolContextCirculation().getValue("toolConfig").jsonObject
        assertFalse("function_calling_config" in config)
        val functions = config.getValue("functionCallingConfig").jsonObject
        assertEquals("NONE", functions.getValue("mode").jsonPrimitive.content)
        assertEquals(JsonArray(listOf(JsonPrimitive("ask_user"))), functions["allowedFunctionNames"])
        assertFalse("allowed_function_names" in functions)
    }

    @Test fun `explicit circulation also disallows auto with builtins alone`() {
        val body = obj("""{"tools":[{"googleSearch":{}}],"tool_config":{"include_server_side_tool_invocations":true,"function_calling_config":{"mode":"AUTO"}}}""")
        val normalized = body.withGoogleToolContextCirculation()
        assertEquals("VALIDATED", normalized.getValue("toolConfig").jsonObject.getValue("functionCallingConfig").jsonObject.getValue("mode").jsonPrimitive.content)
        assertEquals(normalized, normalized.withGoogleToolContextCirculation())
    }

    @Test fun `ordinary function auto mode is not changed when circulation is off`() {
        val body = obj("""{"tools":[{"functionDeclarations":[{"name":"ask_user"}]}],"tool_config":{"include_server_side_tool_invocations":false,"function_calling_config":{"mode":"AUTO"}}}""")
        assertEquals(body, body.withGoogleToolContextCirculation())
    }

    @Test fun `nonstream parts preserve server context without becoming executable functions`() {
        val parts = listOf(parseGoogleServerToolPart(call)!!, UIMessagePart.Text("middle"), parseGoogleServerToolPart(result)!!)
        val message = UIMessage(role = MessageRole.ASSISTANT, parts = completeGoogleServerCalls(parts))
        assertTrue(message.getTools().isEmpty())
        assertEquals(listOf(call, obj("""{"text":"middle"}"""), result), replay(message).single().jsonObject.getValue("parts").jsonArray.toList())
    }
}
