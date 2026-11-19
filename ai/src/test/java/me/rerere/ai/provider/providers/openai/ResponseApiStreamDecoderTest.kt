package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.stream.SseEvent
import me.rerere.ai.ui.OpenAIReasoningMetadata
import me.rerere.ai.ui.ReasoningType
import me.rerere.ai.ui.ServerToolMetadata
import me.rerere.ai.ui.ServerToolStatus
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.StreamChunkHandler
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.metadataAs
import me.rerere.ai.util.HttpException
import me.rerere.ai.util.json
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponseApiStreamDecoderTest {
    private val api = ResponseAPI(OkHttpClient())

    @Test fun `refusal streams as visible text and closes once with derived usage`() {
        val decoder = ResponseApiStreamDecoder()
        val chunks = listOf(
            """{"type":"response.refusal.delta","output_index":0,"delta":"Cannot comply"}""",
            """{"type":"response.refusal.done","output_index":0}""",
            """{"type":"response.completed","response":{"status":"completed","usage":{"input_tokens":2,"output_tokens":3}}}""",
        ).flatMap { decoder.accept(SseEvent(data = it)).chunks }
        assertEquals("Cannot comply", chunks.filterIsInstance<StreamChunk.TextDelta>().single().text)
        assertEquals(1, chunks.filterIsInstance<StreamChunk.TextEnd>().size)
        assertEquals(5, chunks.filterIsInstance<StreamChunk.Usage>().single().usage.totalTokens)
    }

    @Test fun `gateway delta missing item id resolves from output index and keeps text`() {
        val decoder = ResponseApiStreamDecoder()
        val events = listOf(
            """{"type":"response.output_item.added","output_index":0,"item":{"id":"msg_real","type":"message"}}""",
            """{"type":"response.output_text.delta","output_index":0,"delta":"你好"}""",
            """{"type":"response.output_text.delta","output_index":0,"delta":"世界"}""",
            """{"type":"response.output_text.done","output_index":0}""",
            """{"type":"response.completed","response":{"id":"r","status":"completed"}}""",
        )
        val chunks = events.flatMap { decoder.accept(SseEvent(data=it)).chunks }
        assertEquals("你好世界", chunks.filterIsInstance<StreamChunk.TextDelta>().joinToString("") { it.text })
        assertEquals(1, chunks.filterIsInstance<StreamChunk.TextStart>().size)
        assertEquals(1, chunks.filterIsInstance<StreamChunk.TextEnd>().size)
    }

    @Test fun `provisional identity remains stable when real ids arrive late and output indices interleave`() {
        val decoder = ResponseApiStreamDecoder()
        val events = listOf(
            """{"type":"response.output_text.delta","output_index":0,"delta":"A"}""",
            """{"type":"response.output_text.delta","output_index":1,"delta":"B"}""",
            """{"type":"response.output_item.added","output_index":0,"item":{"id":"late","type":"message"}}""",
            """{"type":"response.output_text.delta","item_id":"late","delta":"C"}""",
        )
        val chunks = events.flatMap { decoder.accept(SseEvent(data=it)).chunks }
        val deltas = chunks.filterIsInstance<StreamChunk.TextDelta>()
        assertEquals(deltas[0].id, deltas[2].id)
        assertTrue(deltas[0].id != deltas[1].id)
        assertEquals(2, chunks.filterIsInstance<StreamChunk.TextStart>().size)
    }

    @Test fun `single item gateway can omit both identifiers and use SSE event names`() {
        val decoder = ResponseApiStreamDecoder()
        val first = decoder.accept(SseEvent(event="response.output_text.delta", data="""{"delta":"one"}"""))
        val second = decoder.accept(SseEvent(event="response.output_text.delta", data="""{"delta":"two"}"""))
        assertEquals(first.chunks.filterIsInstance<StreamChunk.TextDelta>().single().id,
            second.chunks.filterIsInstance<StreamChunk.TextDelta>().single().id)
        assertTrue(decoder.accept(SseEvent(data="[DONE]")).completed)
    }

    @Test fun `parallel tool arguments missing item id stay bound to independent call ids`() {
        val decoder = ResponseApiStreamDecoder()
        val events = listOf(
            """{"type":"response.output_item.added","output_index":0,"item":{"type":"function_call","id":"i0","call_id":"call0","name":"first"}}""",
            """{"type":"response.output_item.added","output_index":1,"item":{"type":"function_call","id":"i1","call_id":"call1","name":"second"}}""",
            """{"type":"response.function_call_arguments.delta","output_index":1,"delta":"{}"}""",
            """{"type":"response.function_call_arguments.delta","output_index":0,"delta":"{\"a\":1}"}""",
        )
        val chunks = events.flatMap { decoder.accept(SseEvent(data=it)).chunks }
        assertEquals(listOf("call1","call0"), chunks.filterIsInstance<StreamChunk.ToolCallDelta>().map { it.id })
        assertThrows(java.io.IOException::class.java) {
            decoder.accept(SseEvent(data="""{"type":"response.function_call_arguments.delta","delta":"bad"}"""))
        }
        val singleTool = ResponseApiStreamDecoder()
        singleTool.accept(SseEvent(data = events.first()))
        assertThrows(java.io.IOException::class.java) {
            singleTool.accept(SseEvent(data = """{"type":"response.output_item.added","item":{"type":"function_call","call_id":"another","name":"second"}}"""))
        }
    }

    @Test fun `late reasoning provider id is replayed while local stream identity stays stable`() {
        val decoder = ResponseApiStreamDecoder()
        val events = listOf(
            """{"type":"response.reasoning_summary_text.delta","output_index":0,"delta":"summary"}""",
            """{"type":"response.output_item.done","output_index":0,"item":{"type":"reasoning","id":"rs_late","encrypted_content":"encrypted"}}""",
        )
        val chunks = events.flatMap { decoder.accept(SseEvent(data = it)).chunks }
        assertEquals(chunks.filterIsInstance<StreamChunk.ReasoningStart>().single().id,
            chunks.filterIsInstance<StreamChunk.ReasoningEnd>().single().id)
        val handler = StreamChunkHandler(Model(modelId = "test-model"))
        val messages = chunks.fold(listOf(UIMessage.user("hello"))) { messages, chunk ->
            handler.handle(messages, chunk)
        }
        val reasoning = messages.last().parts.single() as UIMessagePart.Reasoning
        assertEquals("rs_late", reasoning.metadataAs<OpenAIReasoningMetadata>()?.reasoningId)
        val replay = api.buildMessages(messages).last().jsonObject
        assertEquals("rs_late", replay["id"]?.jsonPrimitive?.content)
        assertEquals("encrypted", replay["encrypted_content"]?.jsonPrimitive?.content)
    }

    @Test
    fun `incomplete response should preserve terminal metadata and usage`() {
        val decoder = ResponseApiStreamDecoder()
        val result = decoder.accept(SseEvent(
            event = "response.incomplete",
            data = json.encodeToString(buildJsonObject {
                put("type", "response.incomplete")
                put("response", buildJsonObject {
                    put("id", "resp_incomplete")
                    put("model", "test-model")
                    put("status", "incomplete")
                    put("incomplete_details", buildJsonObject {
                        put("reason", "max_output_tokens")
                    })
                    put("usage", buildJsonObject {
                        put("input_tokens", 10)
                        put("output_tokens", 20)
                        put("total_tokens", 30)
                        put("input_tokens_details", buildJsonObject {
                            put("cached_tokens", 4)
                        })
                    })
                })
            }),
        ))

        assertTrue(result.completed)
        assertEquals(
            TokenUsage(promptTokens = 10, completionTokens = 20, cachedTokens = 4, totalTokens = 30),
            (result.chunks[0] as StreamChunk.Usage).usage,
        )
        assertEquals(
            StreamChunk.Finish(
                finishReason = "incomplete:max_output_tokens",
                responseId = "resp_incomplete",
                model = "test-model",
            ),
            result.chunks[1],
        )
        assertTrue(decoder.onClosed().isEmpty())
    }

    @Test
    fun `failed response should terminate with provider error`() {
        val decoder = ResponseApiStreamDecoder()

        val exception = assertThrows(HttpException::class.java) {
            decoder.accept(SseEvent(
                event = "response.failed",
                data = json.encodeToString(buildJsonObject {
                    put("type", "response.failed")
                    put("response", buildJsonObject {
                        put("id", "resp_failed")
                        put("status", "failed")
                        put("error", buildJsonObject {
                            put("code", "server_error")
                            put("message", "Upstream generation failed")
                        })
                    })
                }),
            ))
        }

        assertEquals("Upstream generation failed", exception.message)
        assertTrue(decoder.onClosed().isEmpty())
    }

    @Test
    fun `reasoning item without summary should preserve encrypted content`() {
        val decoder = ResponseApiStreamDecoder()
        val chunks = buildList {
            addAll(decoder.decode(reasoningItemEvent("response.output_item.added")))
            addAll(decoder.decode(reasoningItemEvent("response.output_item.done", "encrypted")))
        }

        assertEquals(2, chunks.size)
        assertTrue(chunks[0] is StreamChunk.ReasoningStart)
        assertTrue(chunks[1] is StreamChunk.ReasoningEnd)

        val handler = StreamChunkHandler(Model(modelId = "test-model"))
        val messages = chunks.fold(listOf(UIMessage.user("hello"))) { messages, chunk ->
            handler.handle(messages, chunk)
        }
        val reasoning = messages.last().parts.single() as UIMessagePart.Reasoning

        assertEquals("", reasoning.reasoning)
        assertEquals("rs_test", reasoning.metadataAs<OpenAIReasoningMetadata>()?.reasoningId)
        assertEquals("encrypted", reasoning.metadataAs<OpenAIReasoningMetadata>()?.encryptedContent)
        assertFalse(messages.last().isValidToUpload())

        // ResponseAPI must still replay metadata-only reasoning even though generic providers consider it empty.
        val reasoningItem = api.buildMessages(messages).last().jsonObject
        assertEquals("reasoning", reasoningItem["type"]?.jsonPrimitive?.content)
        assertEquals("rs_test", reasoningItem["id"]?.jsonPrimitive?.content)
        assertEquals("encrypted", reasoningItem["encrypted_content"]?.jsonPrimitive?.content)
    }

    @Test
    fun `reasoning item should keep final metadata after summary done event`() {
        val decoder = ResponseApiStreamDecoder()
        val chunks = buildList {
            addAll(decoder.decode(reasoningItemEvent("response.output_item.added")))
            addAll(decoder.decode(buildJsonObject {
                put("type", "response.reasoning_summary_text.delta")
                put("item_id", "rs_test")
                put("summary_index", 0)
                put("delta", "summary")
            }))
            addAll(decoder.decode(buildJsonObject {
                put("type", "response.reasoning_summary_text.done")
                put("item_id", "rs_test")
                put("summary_index", 0)
            }))
            addAll(decoder.decode(reasoningItemEvent("response.output_item.done", "encrypted")))
        }

        val handler = StreamChunkHandler(Model(modelId = "test-model"))
        val messages = chunks.fold(listOf(UIMessage.user("hello"))) { messages, chunk ->
            handler.handle(messages, chunk)
        }
        val reasoningParts = messages.last().parts.filterIsInstance<UIMessagePart.Reasoning>()

        assertEquals(1, reasoningParts.size)
        assertEquals("summary", reasoningParts.single().reasoning)
        assertEquals(
            "encrypted",
            reasoningParts.single().metadataAs<OpenAIReasoningMetadata>()?.encryptedContent,
        )
        assertEquals(ReasoningType.SUMMARY_TEXT, reasoningParts.single().reasoningType)
    }

    @Test
    fun `raw reasoning and summary with the same index should remain distinct`() {
        val decoder = ResponseApiStreamDecoder()
        val chunks = buildList {
            addAll(decoder.decode(reasoningItemEvent("response.output_item.added")))
            addAll(decoder.decode(buildJsonObject {
                put("type", "response.reasoning_text.delta")
                put("item_id", "rs_test")
                put("content_index", 0)
                put("delta", "raw")
            }))
            addAll(decoder.decode(buildJsonObject {
                put("type", "response.reasoning_summary_text.delta")
                put("item_id", "rs_test")
                put("summary_index", 0)
                put("delta", "summary")
            }))
            addAll(decoder.decode(reasoningItemEvent("response.output_item.done", "encrypted")))
        }

        val handler = StreamChunkHandler(Model(modelId = "test-model"))
        val messages = chunks.fold(listOf(UIMessage.user("hello"))) { messages, chunk ->
            handler.handle(messages, chunk)
        }
        val reasoningParts = messages.last().parts.filterIsInstance<UIMessagePart.Reasoning>()

        assertEquals(2, reasoningParts.size)
        assertEquals("raw", reasoningParts.single { it.reasoningType == ReasoningType.REASONING_TEXT }.reasoning)
        assertEquals("summary", reasoningParts.single { it.reasoningType == ReasoningType.SUMMARY_TEXT }.reasoning)

        val reasoningItem = api.buildMessages(messages).last().jsonObject
        assertEquals(
            "summary",
            reasoningItem["summary"]?.jsonArray?.single()?.jsonObject
                ?.get("text")?.jsonPrimitive?.content,
        )
        assertEquals(
            "raw",
            reasoningItem["content"]?.jsonArray?.single()?.jsonObject
                ?.get("text")?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `web search events should produce one completed server tool`() {
        val decoder = ResponseApiStreamDecoder()
        val chunks = buildList {
            addAll(decoder.decode(buildJsonObject {
                put("type", "response.output_item.added")
                put("item", webSearchItem("in_progress"))
            }))
            addAll(decoder.decode(buildJsonObject {
                put("type", "response.web_search_call.searching")
                put("item_id", "ws_1")
            }))
            addAll(decoder.decode(buildJsonObject {
                put("type", "response.web_search_call.completed")
                put("item_id", "ws_1")
            }))
            addAll(decoder.decode(buildJsonObject {
                put("type", "response.output_item.done")
                put("item", webSearchItem("completed"))
            }))
        }

        val handler = StreamChunkHandler(Model(modelId = "test-model"))
        val messages = chunks.fold(listOf(UIMessage.user("search"))) { messages, chunk ->
            handler.handle(messages, chunk)
        }
        val tool = messages.last().parts.single() as UIMessagePart.ServerTool

        assertEquals("ws_1", tool.toolCallId)
        assertEquals("web_search", tool.toolName)
        assertEquals("search", tool.input?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertEquals(ServerToolStatus.COMPLETED, tool.status)
        assertEquals(
            "web_search_call",
            tool.metadataAs<ServerToolMetadata>()?.call?.get("type")?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `function call object arguments are preserved as tool json`() {
        val decoder = ResponseApiStreamDecoder()
        val chunks = buildList {
            addAll(decoder.decode(buildJsonObject {
                put("type", "response.output_item.added")
                put("item", buildJsonObject {
                    put("type", "function_call")
                    put("id", "fc_1")
                    put("call_id", "call_1")
                    put("name", "workspace_shell")
                    put("arguments", buildJsonObject { put("command", "ls -la") })
                })
            }))
            addAll(decoder.decode(buildJsonObject {
                put("type", "response.output_item.done")
                put("item", buildJsonObject {
                    put("type", "function_call")
                    put("id", "fc_1")
                    put("call_id", "call_1")
                    put("name", "workspace_shell")
                    put("arguments", buildJsonObject { put("command", "ls -la") })
                })
            }))
        }

        val handler = StreamChunkHandler(Model(modelId = "test-model"))
        val messages = chunks.fold(listOf(UIMessage.user("run a command"))) { messages, chunk ->
            handler.handle(messages, chunk)
        }
        val tool = messages.last().parts.single() as UIMessagePart.Tool

        assertEquals("workspace_shell", tool.toolName)
        assertEquals(
            "ls -la",
            json.parseToJsonElement(tool.input).jsonObject["command"]?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `non streaming response should parse web search without synthetic output`() {
        val result = api.parseResponseOutput(buildJsonObject {
            put("id", "resp_1")
            put("model", "test-model")
            put("status", "completed")
            put("output", buildJsonArray { add(webSearchItem("completed")) })
        })

        val tool = result.message.parts.single() as UIMessagePart.ServerTool
        assertEquals(ServerToolStatus.COMPLETED, tool.status)
        assertEquals(null, tool.output)
        assertEquals("Kotlin", tool.input?.jsonObject?.get("query")?.jsonPrimitive?.content)
    }

    @Test
    fun `non streaming response should preserve metadata-only reasoning`() {
        val result = api.parseResponseOutput(buildJsonObject {
            put("id", "resp_1")
            put("model", "test-model")
            put("status", "completed")
            put("output", buildJsonArray {
                add(buildJsonObject {
                    put("type", "reasoning")
                    put("id", "rs_test")
                    put("encrypted_content", "encrypted")
                    put("summary", buildJsonArray {})
                    put("content", buildJsonArray {})
                })
            })
        })

        val reasoning = result.message.parts.single() as UIMessagePart.Reasoning
        assertEquals("", reasoning.reasoning)
        assertEquals("rs_test", reasoning.metadataAs<OpenAIReasoningMetadata>()?.reasoningId)
        assertEquals("encrypted", reasoning.metadataAs<OpenAIReasoningMetadata>()?.encryptedContent)

        val reasoningItem = api.buildMessages(listOf(result.message)).single().jsonObject
        assertEquals("reasoning", reasoningItem["type"]?.jsonPrimitive?.content)
        assertEquals("rs_test", reasoningItem["id"]?.jsonPrimitive?.content)
        assertEquals("encrypted", reasoningItem["encrypted_content"]?.jsonPrimitive?.content)
    }

    @Test
    fun `non streaming response should distinguish raw reasoning and summary`() {
        val result = api.parseResponseOutput(buildJsonObject {
            put("id", "resp_1")
            put("model", "test-model")
            put("status", "completed")
            put("output", buildJsonArray {
                add(buildJsonObject {
                    put("type", "reasoning")
                    put("id", "rs_test")
                    put("encrypted_content", "encrypted")
                    put("summary", buildJsonArray {
                        add(buildJsonObject {
                            put("type", "summary_text")
                            put("text", "summary")
                        })
                    })
                    put("content", buildJsonArray {
                        add(buildJsonObject {
                            put("type", "reasoning_text")
                            put("text", "raw")
                        })
                    })
                })
            })
        })

        val reasoningParts = result.message.parts.filterIsInstance<UIMessagePart.Reasoning>()
        assertEquals(2, reasoningParts.size)
        assertEquals("raw", reasoningParts.single { it.reasoningType == ReasoningType.REASONING_TEXT }.reasoning)
        assertEquals("summary", reasoningParts.single { it.reasoningType == ReasoningType.SUMMARY_TEXT }.reasoning)
        reasoningParts.forEach {
            assertEquals("rs_test", it.metadataAs<OpenAIReasoningMetadata>()?.reasoningId)
            assertEquals("encrypted", it.metadataAs<OpenAIReasoningMetadata>()?.encryptedContent)
        }
    }

    private fun webSearchItem(status: String) = buildJsonObject {
        put("type", "web_search_call")
        put("id", "ws_1")
        put("status", status)
        put("action", buildJsonObject {
            put("type", "search")
            put("query", "Kotlin")
        })
    }

    private fun reasoningItemEvent(type: String, encryptedContent: String? = null) = buildJsonObject {
        put("type", type)
        put("item", buildJsonObject {
            put("type", "reasoning")
            put("id", "rs_test")
            encryptedContent?.let { put("encrypted_content", it) }
        })
    }

    private fun ResponseApiStreamDecoder.decode(payload: kotlinx.serialization.json.JsonObject): List<StreamChunk> =
        accept(SseEvent(
            event = payload["type"]?.jsonPrimitive?.content,
            data = json.encodeToString(payload),
        )).chunks
}
