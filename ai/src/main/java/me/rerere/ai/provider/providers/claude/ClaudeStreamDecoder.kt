package me.rerere.ai.provider.providers.claude

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.stream.DecodeResult
import me.rerere.ai.provider.stream.SseEvent
import me.rerere.ai.provider.stream.StreamChunkDecoder
import me.rerere.ai.provider.stream.prematureStreamTermination
import me.rerere.ai.ui.ClaudeReasoningMetadata
import me.rerere.ai.ui.ServerToolMetadata
import me.rerere.ai.ui.ServerToolProtocol
import me.rerere.ai.ui.ServerToolStatus
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.toMetadata
import me.rerere.ai.util.json
import me.rerere.ai.util.parseErrorDetail

internal class ClaudeStreamDecoder : StreamChunkDecoder {
    private val blocks = mutableMapOf<Int, ClaudeStreamBlock>()
    private var responseId: String? = null
    private var responseModel: String? = null
    private var finishReason: String? = null
    private var finished = false

    override fun accept(event: SseEvent): DecodeResult {
        if (finished) return DecodeResult(completed = true)
        if (event.data == "[DONE]") return DecodeResult(finish(), completed = true)

        val dataJson = json.parseToJsonElement(event.data).jsonObject
        // Compatibility relays sometimes omit the SSE event field and retain only the
        // Anthropic event type inside the JSON payload.
        val eventType = event.event?.takeUnless { it.isBlank() }
            ?: dataJson["type"]?.jsonPrimitive?.contentOrNull
        if (eventType == "error") {
            throw (dataJson["error"] ?: dataJson).parseErrorDetail()
        }

        dataJson["message"]?.jsonObject?.let { message ->
            responseId = message["id"]?.jsonPrimitive?.contentOrNull ?: responseId
            responseModel = message["model"]?.jsonPrimitive?.contentOrNull ?: responseModel
        }
        dataJson["delta"]?.jsonObject?.get("stop_reason")?.jsonPrimitive?.contentOrNull?.let {
            finishReason = it
        }

        val chunks = buildList {
            parseTokenUsage(dataJson)?.let { add(StreamChunk.Usage(it)) }
            val index = dataJson["index"]?.jsonPrimitive?.intOrNull
            val contentBlock = dataJson["content_block"]?.jsonObject

            if (eventType == "content_block_start" && index != null && contentBlock != null) {
                val kind = contentBlock["type"]?.jsonPrimitive?.contentOrNull ?: ""
                val previous = blocks[index]
                val blockId = contentBlock["id"]?.jsonPrimitive?.contentOrNull
                    ?: contentBlock["tool_use_id"]?.jsonPrimitive?.contentOrNull
                    ?: previous?.id
                    ?: "${responseId ?: event.id ?: "response"}:block-$index"
                val metadata = when (kind) {
                    "thinking" -> contentBlock["signature"]?.jsonPrimitive?.contentOrNull?.let {
                        ClaudeReasoningMetadata(signature = it).toMetadata()
                    } ?: previous?.metadata
                    else -> null
                }
                blocks[index] = ClaudeStreamBlock(kind, blockId, metadata)
                if (previous == null) {
                    when (kind) {
                        "text" -> add(StreamChunk.TextStart(blockId))
                        "thinking", "redacted_thinking" -> add(StreamChunk.ReasoningStart(blockId, metadata))
                        "tool_use" -> add(StreamChunk.ToolCallStart(
                            id = blockId,
                            toolName = contentBlock["name"]?.jsonPrimitive?.contentOrNull ?: "",
                        ))
                        else -> if (kind.isClaudeServerToolUseType()) {
                            add(StreamChunk.ServerToolStart(
                                id = blockId,
                                toolName = contentBlock["name"]?.jsonPrimitive?.contentOrNull ?: "",
                                input = contentBlock["input"],
                                metadata = ServerToolMetadata(
                                    protocol = ServerToolProtocol.ANTHROPIC_MESSAGES,
                                    call = contentBlock,
                                    callIndex = index,
                                ).toMetadata(),
                            ))
                        } else if (kind.isClaudeServerToolResultType()) {
                            val output = contentBlock["content"]
                            add(StreamChunk.ServerToolEnd(
                                id = blockId,
                                output = output,
                                status = if (output.isClaudeServerToolError()) {
                                    ServerToolStatus.FAILED
                                } else {
                                    ServerToolStatus.COMPLETED
                                },
                                metadata = ServerToolMetadata(
                                    protocol = ServerToolProtocol.ANTHROPIC_MESSAGES,
                                    result = contentBlock,
                                    resultIndex = index,
                                ).toMetadata(),
                            ))
                        }
                    }
                } else if (kind == "tool_use") {
                    // If a delta arrived first, update the temporary tool with its real name.
                    contentBlock["name"]?.jsonPrimitive?.contentOrNull
                        ?.takeIf { it.isNotBlank() }
                        ?.let { add(StreamChunk.ToolCallStart(blockId, it)) }
                }
                val input = contentBlock["input"]
                if (kind == "tool_use" && input != null &&
                    (input !is JsonObject || input.isNotEmpty())
                ) {
                    add(StreamChunk.ToolCallDelta(
                        id = blockId,
                        inputDelta = input.toString(),
                    ))
                }
            }

            if (eventType == "content_block_delta" && index != null) {
                val delta = dataJson["delta"]?.jsonObject ?: JsonObject(emptyMap())
                val deltaType = delta["type"]?.jsonPrimitive?.contentOrNull
                // Official streams send content_block_start first, but compatible gateways may
                // drop it. Create a minimal block so short calls are not reported as interrupted.
                val block = blocks[index] ?: run {
                    val kind = when (deltaType) {
                        "text_delta" -> "text"
                        "thinking_delta", "signature_delta" -> "thinking"
                        "input_json_delta" -> "tool_use"
                        else -> ""
                    }
                    val id = "${responseId ?: event.id ?: "response"}:block-$index"
                    val created = ClaudeStreamBlock(kind, id)
                    blocks[index] = created
                    when (kind) {
                        "text" -> add(StreamChunk.TextStart(id))
                        "thinking" -> add(StreamChunk.ReasoningStart(id))
                        "tool_use" -> add(StreamChunk.ToolCallStart(id, ""))
                    }
                    created
                }
                when (deltaType) {
                    "text_delta" -> add(StreamChunk.TextDelta(
                        block.id,
                        delta["text"]?.jsonPrimitive?.contentOrNull ?: "",
                    ))
                    "thinking_delta" -> add(StreamChunk.ReasoningDelta(
                        block.id,
                        delta["thinking"]?.jsonPrimitive?.contentOrNull ?: "",
                        block.metadata,
                    ))
                    "signature_delta" -> {
                        val metadata = delta["signature"]?.jsonPrimitive?.contentOrNull?.let {
                            ClaudeReasoningMetadata(signature = it).toMetadata()
                        }
                        blocks[index] = block.copy(metadata = metadata ?: block.metadata)
                        add(StreamChunk.ReasoningDelta(block.id, "", metadata))
                    }
                    "input_json_delta" -> {
                        val partialJson = delta["partial_json"]?.jsonPrimitive?.contentOrNull ?: ""
                        if (block.kind.isClaudeServerToolUseType()) {
                            add(StreamChunk.ServerToolInputDelta(block.id, partialJson))
                        } else {
                            add(StreamChunk.ToolCallDelta(id = block.id, inputDelta = partialJson))
                        }
                    }
                }
            }

            if (eventType == "content_block_stop" && index != null) {
                // Ignore duplicate/late stop events from relays.
                blocks.remove(index)?.let { block -> endBlock(block)?.let(::add) }
            }
        }

        return if (eventType == "message_stop") {
            DecodeResult(chunks + finish(), completed = true)
        } else {
            DecodeResult(chunks)
        }
    }

    override fun onClosed(): List<StreamChunk> {
        if (finished) return emptyList()
        prematureStreamTermination("Anthropic Messages")
    }

    private fun finish(): List<StreamChunk> {
        if (finished) return emptyList()
        finished = true
        return buildList {
            blocks.values.mapNotNull(::endBlock).forEach(::add)
            blocks.clear()
            add(StreamChunk.Finish(finishReason, responseId, responseModel))
        }
    }

    private fun endBlock(block: ClaudeStreamBlock): StreamChunk? = when (block.kind) {
        "text" -> StreamChunk.TextEnd(block.id)
        "thinking", "redacted_thinking" -> StreamChunk.ReasoningEnd(block.id, block.metadata)
        "tool_use" -> StreamChunk.ToolCallEnd(block.id)
        else -> if (block.kind.isClaudeServerToolUseType()) {
            StreamChunk.ServerToolInputEnd(block.id)
        } else {
            null
        }
    }

    private fun parseTokenUsage(bodyJson: JsonObject): TokenUsage? {
        val usageJson = bodyJson["usage"]?.jsonObject
            ?: bodyJson["message"]?.jsonObject?.get("usage")?.jsonObject
            ?: return null
        val inputTokens = usageJson["input_tokens"]?.jsonPrimitive?.intOrNull ?: 0
        val cachedInputTokens = usageJson["cache_read_input_tokens"]?.jsonPrimitive?.intOrNull ?: 0
        val cachedCreationTokens = usageJson["cache_creation_input_tokens"]?.jsonPrimitive?.intOrNull ?: 0
        val completionTokens = usageJson["output_tokens"]?.jsonPrimitive?.intOrNull ?: 0
        val promptTokens = inputTokens + cachedInputTokens + cachedCreationTokens
        return TokenUsage(
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            totalTokens = promptTokens + completionTokens,
            cachedTokens = cachedInputTokens,
        )
    }

    private data class ClaudeStreamBlock(
        val kind: String,
        val id: String,
        val metadata: JsonObject? = null,
    )
}
