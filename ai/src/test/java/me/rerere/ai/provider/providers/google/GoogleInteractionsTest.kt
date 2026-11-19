package me.rerere.ai.provider.providers.google

import java.io.EOFException
import kotlinx.serialization.json.*
import me.rerere.ai.core.*
import me.rerere.ai.provider.*
import me.rerere.ai.provider.stream.SseEvent
import me.rerere.ai.ui.*
import me.rerere.ai.util.json
import org.junit.Assert.*
import org.junit.Test

class GoogleInteractionsTest {
    private fun obj(text: String) = json.parseToJsonElement(text).jsonObject
    private val model = Model(modelId = "gemini-4-hydrogen").withDetectedCapabilities()
    private fun params(options: GeminiGenerationOptions = GeminiGenerationOptions()) =
        TextGenerationParams(model = model, maxTokens = 100_000, reasoningLevel = ReasoningLevel.XHIGH,
            geminiOptions = options, tools = listOf(Tool("lookup", "test",
                parameters = { InputSchema.Obj(properties = JsonObject(emptyMap())) }, execute = { emptyList() })))
    private fun body(messages: List<UIMessage>, params: TextGenerationParams = params()) =
        buildGoogleInteractionsRequest(messages, params, true) { part ->
            if (part is UIMessagePart.Image) obj("""{"fileData":{"mimeType":"image/png","fileUri":"https://example.test/image.png"}}""") else null
        }

    @Test fun `maps system tools generation media safety and schema to new wire format`() {
        val result = body(listOf(UIMessage.system("稳定指令"), UIMessage(role = MessageRole.USER,
            parts = listOf(UIMessagePart.Text("描述"), UIMessagePart.Image("https://example.test/image.png")))),
            params(GeminiGenerationOptions(search = GeminiSearchOptions(enabled = true, urlContext = true),
                mediaResolution = GeminiMediaResolution.ULTRA_HIGH, includeThoughts = false, codeExecution = true,
                seed = 9, stopSequences = listOf("END"), toolChoice = GeminiToolChoice.VALIDATED,
                responseMimeType = GeminiResponseMimeType.JSON, responseJsonSchema = """{"type":"object"}""")))
        assertEquals("稳定指令", result.string("system_instruction"))
        assertEquals(false, result["store"]!!.jsonPrimitive.boolean)
        assertFalse(result.containsKey("contents"))
        assertEquals(setOf("function", "google_search", "url_context", "code_execution"),
            result["tools"]!!.jsonArray.map { it.jsonObject.string("type") }.toSet())
        val config = result["generation_config"]!!.jsonObject
        assertEquals("xhigh", config.string("thinking_level"))
        assertEquals("none", config.string("thinking_summaries"))
        assertEquals("validated", config.string("tool_choice"))
        assertFalse(config.containsKey("temperature"))
        val image = result["input"]!!.jsonArray.single().jsonObject["content"]!!.jsonArray[1].jsonObject
        assertEquals("image", image.string("type"))
        assertEquals("ultra_high", image.string("resolution"))
        assertEquals("https://example.test/image.png", image.string("uri"))
        assertEquals("object", result["response_format"]!!.jsonObject["schema"]!!.jsonObject.string("type"))
        assertEquals(4, result["safety_settings"]!!.jsonArray.size)
    }

    @Test fun `stateless tool round trip preserves thought and server tool signatures exactly`() {
        val response = obj("""{"id":"turn1","model":"gemini-4-hydrogen","status":"requires_action","steps":[
            {"type":"thought","signature":"opaque+/=","summary":[]},
            {"type":"google_search_call","id":"s1","arguments":{"queries":["天气"]},"signature":"call+/="},
            {"type":"google_search_result","call_id":"s1","result":[{"search_suggestions":"<p>source</p>"}],"signature":"result+/="},
            {"type":"function_call","id":"f1","name":"lookup","arguments":{"city":"北京"}}
        ],"usage":{"total_input_tokens":10,"total_output_tokens":4,"total_thought_tokens":6,"total_tokens":20}}""")
        val parsed = parseGoogleInteraction(response)
        assertEquals(10, parsed.usage!!.completionTokens)
        assertEquals(1, parsed.message.annotations.size)
        val executed = parsed.message.copy(parts = parsed.message.parts.map {
            if (it is UIMessagePart.Tool) it.copy(output = listOf(UIMessagePart.Text("晴朗"))) else it
        })
        val replay = body(listOf(UIMessage.user("北京天气"), executed))["input"]!!.jsonArray
        assertEquals(response["steps"]!!.jsonArray, JsonArray(replay.drop(1).take(4)))
        val result = replay.last().jsonObject
        assertEquals("function_result", result.string("type"))
        assertEquals("f1", result.string("call_id"))
        assertEquals("晴朗", result["result"]!!.jsonArray.single().jsonObject.string("text"))
    }

    @Test fun `streamed unicode thought signature and split function arguments survive replay`() {
        val decoder = GoogleInteractionsStreamDecoder()
        val handler = StreamChunkHandler()
        var messages = listOf(UIMessage.assistant(""))
        fun event(raw: String) = decoder.accept(SseEvent(data = raw)).also { result ->
            result.chunks.forEach { messages = handler.handle(messages, it) }
        }
        event("""{"event_type":"interaction.created","interaction":{"id":"i1","model":"gemini-4-hydrogen"}}""")
        event("""{"event_type":"step.start","index":0,"step":{"type":"thought"}}""")
        event("""{"event_type":"step.delta","index":0,"delta":{"type":"thought_summary","content":{"type":"text","text":"分析🧠"}}}""")
        event("""{"event_type":"step.delta","index":0,"delta":{"type":"thought_signature","signature":"signed+/="}}""")
        event("""{"event_type":"step.stop","index":0}""")
        event("""{"event_type":"step.start","index":1,"step":{"type":"model_output"}}""")
        event("""{"event_type":"step.delta","index":1,"delta":{"type":"text","text":"你好"}}""")
        event("""{"event_type":"step.delta","index":1,"delta":{"type":"text","text":"🌏"}}""")
        event("""{"event_type":"step.stop","index":1}""")
        event("""{"event_type":"step.start","index":2,"step":{"type":"function_call","id":"call1","name":"lookup","arguments":{}}}""")
        event("""{"event_type":"step.delta","index":2,"delta":{"type":"arguments_delta","arguments":"{\"city\":"}}""")
        event("""{"event_type":"step.delta","index":2,"delta":{"type":"arguments_delta","arguments":"\"北京\"}"}}""")
        event("""{"event_type":"step.stop","index":2}""")
        assertTrue(event("""{"event_type":"interaction.completed","interaction":{"status":"requires_action","usage":{"total_input_tokens":3,"total_output_tokens":2,"total_thought_tokens":4,"total_tokens":9}}}""").completed)
        decoder.onClosed()
        assertEquals("你好🌏", messages.last().parts.filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text })
        assertEquals("北京", messages.last().getTools().single().inputAsJson().jsonObject.string("city"))
        val replay = body(messages)["input"]!!.jsonArray
        assertEquals(JsonArray(decoder.steps), replay)
        assertEquals("signed+/=", replay.first().jsonObject.string("signature"))
    }

    @Test fun `image output has one replay step and edited text updates history`() {
        val response = obj("""{"id":"img","status":"completed","steps":[{"type":"model_output","content":[{"type":"text","text":"原文"},{"type":"image","mime_type":"image/png","data":"AA=="}]}]}""")
        val parsed = parseGoogleInteraction(response)
        assertEquals(1, parsed.message.parts.filterIsInstance<UIMessagePart.Image>().size)
        assertEquals(response["steps"], body(listOf(parsed.message))["input"])
        val edited = parsed.message.copy(parts = parsed.message.parts.map { if (it is UIMessagePart.Text) it.copy(text = "编辑") else it })
        val steps = body(listOf(edited))["input"]!!.jsonArray
        assertEquals(1, steps.size)
        assertEquals("编辑", steps.single().jsonObject["content"]!!.jsonArray[0].jsonObject.string("text"))
    }

    @Test(expected = EOFException::class) fun `early EOF is an error`() { GoogleInteractionsStreamDecoder().onClosed() }
    @Test(expected = EOFException::class) fun `done without terminal status is an error`() {
        GoogleInteractionsStreamDecoder().accept(SseEvent(data = "[DONE]"))
    }
    @Test(expected = IllegalStateException::class) fun `provider error propagates`() {
        GoogleInteractionsStreamDecoder().accept(SseEvent(data = """{"event_type":"error","error":{"code":400,"message":"bad tool"}}"""))
    }
    @Test(expected = IllegalArgumentException::class) fun `unsupported search time range fails explicitly`() {
        body(listOf(UIMessage.user("test")), params(GeminiGenerationOptions(search = GeminiSearchOptions(enabled = true,
            startTime = "2026-01-01T00:00:00Z", endTime = "2026-02-01T00:00:00Z"))))
    }
    @Test fun `continuation keeps full local history and repeats configuration`() {
        val original = body(listOf(UIMessage.user("long output")))
        val next = interactionContinuation(original, obj("""{"steps":[{"type":"model_output","content":[{"type":"text","text":"part"}]}]}""")["steps"]!!.jsonArray, "opaque+/=")
        assertEquals(2, next["input"]!!.jsonArray.size)
        assertEquals(original["generation_config"], next["generation_config"])
        assertEquals(original["tools"], next["tools"])
        assertEquals(false, next["store"]!!.jsonPrimitive.boolean)
        assertFalse(next.containsKey("previous_interaction_id"))
    }
    @Test fun `legacy Google settings keep GenerateContent default`() {
        assertFalse(json.decodeFromString<ProviderSetting.Google>("{}").useInteractionsApi)
        val saved = ProviderSetting.Google(useInteractionsApi = true)
        assertTrue(json.decodeFromString<ProviderSetting.Google>(json.encodeToString(saved)).useInteractionsApi)
    }

    @Test fun `parallel results precede following model answer in a single UI message`() {
        val message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(
            UIMessagePart.Tool("a", "lookup", "{}", output = listOf(UIMessagePart.Text("one"))),
            UIMessagePart.Tool("b", "lookup", "{}", output = listOf(UIMessagePart.Text("two"))),
            UIMessagePart.Text("final answer"),
        ))
        assertEquals(listOf("function_call", "function_call", "function_result", "function_result", "model_output"),
            body(listOf(message))["input"]!!.jsonArray.map { it.jsonObject.string("type") })
    }

    @Test fun `long streamed output preserves every character and its replay text`() {
        val decoder = GoogleInteractionsStreamDecoder()
        decoder.start(0, obj("""{"type":"model_output"}"""))
        val delta = "中文🌏".repeat(64)
        val text = StringBuilder()
        repeat(80) {
            decoder.accept(SseEvent(data = """{"event_type":"step.delta","index":0,"delta":{"type":"text","text":"$delta"}}"""))
                .chunks.filterIsInstance<StreamChunk.TextDelta>().forEach { text.append(it.text) }
        }
        decoder.stop(0)
        assertTrue(text.length > 16_384)
        assertEquals(delta.repeat(80), text.toString())
        assertEquals(text.toString(), decoder.steps.single()["content"]!!.jsonArray.single().jsonObject.string("text"))
    }
}
