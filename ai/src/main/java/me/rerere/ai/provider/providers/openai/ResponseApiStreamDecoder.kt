package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.stream.DecodeResult
import me.rerere.ai.provider.stream.SseEvent
import me.rerere.ai.provider.stream.StreamChunkDecoder
import me.rerere.ai.provider.stream.prematureStreamTermination
import me.rerere.ai.ui.OpenAIReasoningMetadata
import me.rerere.ai.ui.ReasoningType
import me.rerere.ai.ui.ServerToolStatus
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.toMetadata
import me.rerere.ai.util.json
import me.rerere.ai.util.parseErrorDetail
import me.rerere.common.http.jsonObjectOrNull

internal class ResponseApiStreamDecoder : StreamChunkDecoder {
    private val state = ResponseStreamState()
    private val outputIds = mutableMapOf<Int, String>()
    private val aliases = mutableMapOf<String, String>()
    private val providerIds = mutableMapOf<String, String>()
    private val itemKinds = mutableMapOf<String, String>()

    override fun accept(event: SseEvent): DecodeResult {
        if (state.finished) return DecodeResult(completed = true)
        if (event.data == "[DONE]") return DecodeResult(state.finish(), completed = true)

        if (event.data.isBlank()) return DecodeResult()
        val raw = json.parseToJsonElement(event.data).jsonObject
        val payload = if (raw["type"] == null && event.event?.startsWith("response.") == true)
            JsonObject(raw + ("type" to JsonPrimitive(event.event))) else raw
        val eventType = payload["type"]?.jsonPrimitive?.contentOrNull
        val chunks = parseEvent(payload)
        val completed = eventType == "response.completed" || eventType == "response.incomplete" ||
            event.event == "response.completed" || event.event == "response.incomplete"
        return DecodeResult(chunks, completed)
    }

    override fun onClosed(): List<StreamChunk> {
        if (state.finished) return emptyList()
        prematureStreamTermination("Responses API")
    }

    private fun parseEvent(payload: JsonObject): List<StreamChunk> {
        val chunkType = payload["type"]?.jsonPrimitive?.content ?: error("chunk type not found")
        val itemId = resolveItemId(payload, chunkType)
        val contentIndex = payload["content_index"]?.jsonPrimitive?.intOrNull ?: 0
        val summaryIndex = payload["summary_index"]?.jsonPrimitive?.intOrNull ?: contentIndex
        val textId = itemId?.let { "$it:text:$contentIndex" }
        val summaryReasoningId = itemId?.let { "$it:reasoning:summary:$summaryIndex" }
        val contentReasoningId = itemId?.let { "$it:reasoning:content:$contentIndex" }

        return when (chunkType) {
            "response.output_text.delta", "response.refusal.delta" -> state.textDelta(
                textId ?: missingItemIdentity(chunkType),
                payload["delta"]?.jsonPrimitive?.contentOrNull ?: "",
            )
            "response.reasoning_summary_text.delta" -> state.reasoningDelta(
                summaryReasoningId ?: missingItemIdentity(chunkType),
                payload["delta"]?.jsonPrimitive?.contentOrNull ?: "",
                state.reasoningMetadata[itemId],
                ReasoningType.SUMMARY_TEXT,
            )
            "response.reasoning_text.delta" -> state.reasoningDelta(
                contentReasoningId ?: missingItemIdentity(chunkType),
                payload["delta"]?.jsonPrimitive?.contentOrNull ?: "",
                state.reasoningMetadata[itemId],
                ReasoningType.REASONING_TEXT,
            )
            "response.content_part.added" -> {
                val part = payload["part"]?.jsonObject ?: return emptyList()
                if (part["type"]?.jsonPrimitive?.contentOrNull == "output_text") {
                    state.startText(textId ?: missingItemIdentity(chunkType))
                } else emptyList()
            }
            "response.content_part.done", "response.output_text.done", "response.refusal.done" ->
                state.endText(textId ?: missingItemIdentity(chunkType))
            "response.reasoning_summary_part.added" -> state.startReasoning(
                summaryReasoningId ?: missingItemIdentity(chunkType),
                state.reasoningMetadata[itemId],
                ReasoningType.SUMMARY_TEXT,
            )
            "response.reasoning_summary_part.done",
            "response.reasoning_summary_text.done",
            "response.reasoning_text.done" -> emptyList()
            "response.output_item.added" -> {
                val item = payload["item"]?.jsonObject ?: error("chunk item not found")
                val type = item["type"]?.jsonPrimitive?.content ?: error("chunk type not found")
                val id = itemId ?: missingItemIdentity(chunkType)
                when (type) {
                    "function_call" -> {
                        val callId = item["call_id"]?.jsonPrimitive?.contentOrNull ?: id
                        state.toolCallIdsByItemId[id] = callId
                        state.startTool(
                            id = callId,
                            name = item["name"]?.jsonPrimitive?.contentOrNull ?: "",
                            initialInput = item["arguments"]?.toToolArgumentString() ?: "",
                        )
                    }
                    "image_generation_call" -> state.startImage(id)
                    "reasoning" -> {
                        state.reasoningMetadata[id] = OpenAIReasoningMetadata(
                            reasoningId = providerIds[id],
                            encryptedContent = item["encrypted_content"]?.jsonPrimitive?.contentOrNull,
                        ).toMetadata()
                        emptyList()
                    }
                    else -> if (isOpenAIServerToolCall(type)) {
                        state.startServerTool(item.toOpenAIServerTool())
                    } else emptyList()
                }
            }
            "response.output_item.done" -> {
                val item = payload["item"]?.jsonObject ?: error("chunk item not found")
                val type = item["type"]?.jsonPrimitive?.content ?: error("chunk type not found")
                val id = itemId ?: missingItemIdentity(chunkType)
                when (type) {
                    "reasoning" -> {
                        val metadata = OpenAIReasoningMetadata(
                            reasoningId = providerIds[id],
                            encryptedContent = item["encrypted_content"]?.jsonPrimitive?.contentOrNull,
                        ).toMetadata()
                        state.reasoningMetadata[id] = metadata
                        state.endReasoningItem(id, metadata)
                    }
                    "image_generation_call" -> buildList {
                        addAll(state.startImage(id))
                        item["result"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotEmpty)?.let {
                            add(StreamChunk.ImageSnapshot(id, it))
                        }
                        addAll(state.endImage(id))
                    }
                    "function_call" -> state.endTool(state.toolCallIdsByItemId.remove(id) ?: id)
                    else -> if (isOpenAIServerToolCall(type)) {
                        val tool = item.toOpenAIServerTool()
                        state.endServerTool(
                            id = tool.toolCallId,
                            input = tool.input,
                            output = tool.output,
                            status = tool.status.takeUnless { it == ServerToolStatus.IN_PROGRESS }
                                ?: ServerToolStatus.COMPLETED,
                            metadata = tool.metadata,
                        )
                    } else emptyList()
                }
            }
            "response.function_call_arguments.delta" -> {
                val requiredItemId = itemId ?: missingItemIdentity(chunkType)
                state.toolDelta(
                    state.toolCallIdsByItemId[requiredItemId] ?: requiredItemId,
                    payload["delta"]?.toToolArgumentString() ?: "",
                )
            }
            "response.function_call_arguments.done" -> {
                val requiredItemId = itemId ?: missingItemIdentity(chunkType)
                val toolCallId = state.toolCallIdsByItemId[requiredItemId] ?: requiredItemId
                buildList {
                    if (toolCallId !in state.toolIdsWithInput) {
                        addAll(state.toolDelta(
                            toolCallId,
                            payload["arguments"]?.toToolArgumentString() ?: "",
                        ))
                    }
                    addAll(state.endTool(toolCallId))
                }
            }
            "response.image_generation_call.partial_image" -> {
                val requiredItemId = itemId ?: missingItemIdentity(chunkType)
                buildList {
                    addAll(state.startImage(requiredItemId))
                    add(StreamChunk.ImageSnapshot(
                        requiredItemId,
                        payload["partial_image_b64"]?.jsonPrimitive?.contentOrNull ?: "",
                    ))
                }
            }
            "response.completed", "response.incomplete" -> parseTerminalResponse(payload)
            "response.failed" -> failWithResponseError(payload)
            "error" -> failWithError(payload)
            else -> parseServerToolStatusEvent(chunkType, itemId)
        }
    }

    /** Compatible gateways sometimes omit item_id. output_index is the protocol's
     * independent item identity; retain one stable local ID even if a real ID arrives late.
     * Never guess between multiple tool calls (that could execute the wrong arguments).
     */
    private fun resolveItemId(payload: JsonObject, event: String): String? {
        val item = payload["item"] as? JsonObject
        val actual = (payload["item_id"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
            ?: (item?.get("id") as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
        val index = (payload["output_index"] as? JsonPrimitive)?.intOrNull
        val kind = (item?.get("type") as? JsonPrimitive)?.contentOrNull ?: when {
            event.startsWith("response.reasoning") -> "reasoning"
            event.startsWith("response.output_text") || event.startsWith("response.refusal") || event.startsWith("response.content_part") -> "message"
            event.startsWith("response.function_call") -> "function_call"
            event.startsWith("response.image_generation_call") -> "image_generation_call"
            else -> null
        }
        if (actual == null && index == null && kind == null) return null
        // A new tool item cannot inherit the previous tool's ID. Even a single
        // existing call is ambiguous once a second, unidentified item is announced.
        if (actual == null && index == null && event == "response.output_item.added" &&
            kind !in setOf("message", "reasoning")) return null
        val known = kind?.let { target -> itemKinds.filterValues { it == target }.keys }
        val provisional = known?.singleOrNull()?.takeIf { it == "compat:$kind:0" && outputIds.isEmpty() }
        val stable = actual?.let(aliases::get) ?: index?.let(outputIds::get) ?: provisional
            ?: actual
            ?: if (index != null) "compat:output:$index" else {
                when {
                    known?.size == 1 -> known.single()
                    known?.isNotEmpty() == true -> throw java.io.IOException(
                        "Responses protocol: $event omitted item_id and output_index for multiple output items")
                    kind == "message" || kind == "reasoning" -> "compat:$kind:0"
                    else -> return null
                }
            }
        if (index != null) outputIds[index] = stable
        if (actual != null) {
            aliases[actual] = stable
            providerIds[stable] = actual
        }
        if (kind != null) itemKinds[stable] = kind
        return stable
    }

    private fun missingItemIdentity(event: String): Nothing = throw java.io.IOException(
        "Responses protocol: $event omitted item_id and output_index; output item cannot be identified safely")

    private fun parseTerminalResponse(payload: JsonObject): List<StreamChunk> {
        val response = payload["response"]?.jsonObject
        val status = response?.get("status")?.jsonPrimitive?.contentOrNull
            ?: payload["type"]?.jsonPrimitive?.contentOrNull?.removePrefix("response.")
        val incompleteReason = response?.get("incomplete_details")?.jsonObjectOrNull
            ?.get("reason")?.jsonPrimitive?.contentOrNull
        val finishReason = if (status == "incomplete" && incompleteReason != null) {
            "$status:$incompleteReason"
        } else {
            status
        }

        return buildList {
            parseUsage(response?.get("usage") as? JsonObject)?.let { add(StreamChunk.Usage(it)) }
            addAll(state.finish(
                finishReason = finishReason,
                responseId = response?.get("id")?.jsonPrimitive?.contentOrNull,
                model = response?.get("model")?.jsonPrimitive?.contentOrNull,
            ))
        }
    }

    private fun failWithResponseError(payload: JsonObject): Nothing {
        val response = payload["response"]?.jsonObject
        return failWithError(response?.get("error")?.takeUnless { it is JsonNull }
            ?: response
            ?: payload)
    }

    private fun failWithError(error: JsonElement): Nothing {
        state.abort()
        throw error.parseErrorDetail()
    }

    private fun parseServerToolStatusEvent(chunkType: String, itemId: String?): List<StreamChunk> {
        val event = chunkType.removePrefix("response.")
        val itemType = event.substringBeforeLast('.', missingDelimiterValue = "")
        val providerStatus = event.substringAfterLast('.', missingDelimiterValue = "")
        if (!isOpenAIServerToolCall(itemType) || itemId == null) return emptyList()

        return when (providerStatus) {
            "completed" -> state.endServerTool(itemId, status = ServerToolStatus.COMPLETED)
            "failed", "cancelled", "incomplete" ->
                state.endServerTool(itemId, status = ServerToolStatus.FAILED)
            else -> state.startServerTool(itemId, itemType.removeSuffix("_call"))
        }
    }

    private fun parseUsage(usage: JsonObject?): TokenUsage? {
        if (usage == null) return null
        return TokenUsage(
            promptTokens = usage["input_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
            completionTokens = usage["output_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
            totalTokens = usage["total_tokens"]?.jsonPrimitive?.intOrNull
                ?: ((usage["input_tokens"]?.jsonPrimitive?.intOrNull ?: 0) + (usage["output_tokens"]?.jsonPrimitive?.intOrNull ?: 0)),
            cachedTokens = usage["input_tokens_details"]?.jsonObjectOrNull
                ?.get("cached_tokens")?.jsonPrimitive?.intOrNull ?: 0,
        )
    }

    private class ResponseStreamState {
        val toolCallIdsByItemId = mutableMapOf<String, String>()
        val toolIdsWithInput = mutableSetOf<String>()
        val reasoningMetadata = mutableMapOf<String, JsonObject>()
        private val openTextIds = linkedSetOf<String>()
        private val openReasoningIds = linkedSetOf<String>()
        private val openImageIds = linkedSetOf<String>()
        private val openToolIds = linkedSetOf<String>()
        private val openServerToolIds = linkedSetOf<String>()
        var finished = false
            private set

        fun abort() {
            finished = true
        }

        fun startText(id: String) = if (openTextIds.add(id)) listOf(StreamChunk.TextStart(id)) else emptyList()
        fun textDelta(id: String, text: String) = startText(id) + StreamChunk.TextDelta(id, text)
        fun endText(id: String) = if (openTextIds.remove(id)) listOf(StreamChunk.TextEnd(id)) else emptyList()
        fun startReasoning(id: String, metadata: JsonObject?, reasoningType: ReasoningType) =
            if (openReasoningIds.add(id)) {
                listOf(StreamChunk.ReasoningStart(id, metadata, reasoningType))
            } else {
                emptyList()
            }
        fun reasoningDelta(
            id: String,
            text: String,
            metadata: JsonObject?,
            reasoningType: ReasoningType,
        ) = startReasoning(id, metadata, reasoningType) +
            StreamChunk.ReasoningDelta(id, text, metadata, reasoningType)
        fun endReasoning(id: String, metadata: JsonObject?) =
            if (openReasoningIds.remove(id)) listOf(StreamChunk.ReasoningEnd(id, metadata)) else emptyList()

        fun endReasoningItem(itemId: String, metadata: JsonObject?): List<StreamChunk> {
            reasoningMetadata.remove(itemId)
            val ids = openReasoningIds.filter { it.startsWith("$itemId:reasoning:") }
            if (ids.isNotEmpty()) return ids.flatMap { endReasoning(it, metadata) }

            // encrypted_content 可以在 summary 为空时单独出现，仍需物化 metadata-only reasoning part。
            val id = "$itemId:reasoning:metadata:0"
            return startReasoning(id, metadata, ReasoningType.REASONING_TEXT) + endReasoning(id, metadata)
        }

        fun startImage(id: String) = if (openImageIds.add(id)) listOf(StreamChunk.ImageStart(id)) else emptyList()
        fun endImage(id: String) = if (openImageIds.remove(id)) listOf(StreamChunk.ImageEnd(id)) else emptyList()
        fun startTool(id: String, name: String, initialInput: String): List<StreamChunk> = buildList {
            if (openToolIds.add(id)) add(StreamChunk.ToolCallStart(id, name))
            if (initialInput.isNotEmpty()) addAll(toolDelta(id, initialInput))
        }
        fun toolDelta(id: String, input: String): List<StreamChunk> = buildList {
            if (openToolIds.add(id)) add(StreamChunk.ToolCallStart(id))
            if (input.isNotEmpty()) {
                toolIdsWithInput += id
                add(StreamChunk.ToolCallDelta(id, inputDelta = input))
            }
        }
        fun endTool(id: String) = if (openToolIds.remove(id)) {
            toolIdsWithInput.remove(id)
            listOf(StreamChunk.ToolCallEnd(id))
        } else emptyList()

        fun startServerTool(tool: me.rerere.ai.ui.UIMessagePart.ServerTool): List<StreamChunk> =
            startServerTool(tool.toolCallId, tool.toolName, tool.input, tool.metadata)

        fun startServerTool(
            id: String,
            toolName: String,
            input: kotlinx.serialization.json.JsonElement? = null,
            metadata: JsonObject? = null,
        ): List<StreamChunk> = if (openServerToolIds.add(id)) {
            listOf(StreamChunk.ServerToolStart(id, toolName, input, metadata))
        } else if (input != null || metadata != null) {
            listOf(StreamChunk.ServerToolStart(id, toolName, input, metadata))
        } else emptyList()

        fun endServerTool(
            id: String,
            input: kotlinx.serialization.json.JsonElement? = null,
            output: kotlinx.serialization.json.JsonElement? = null,
            status: ServerToolStatus,
            metadata: JsonObject? = null,
        ): List<StreamChunk> {
            openServerToolIds.remove(id)
            return listOf(StreamChunk.ServerToolEnd(id, input, output, status, metadata))
        }

        fun finish(
            finishReason: String? = null,
            responseId: String? = null,
            model: String? = null,
        ): List<StreamChunk> {
            if (finished) return emptyList()
            finished = true
            return buildList {
                openTextIds.toList().forEach { addAll(endText(it)) }
                reasoningMetadata.toMap().forEach { (itemId, metadata) ->
                    addAll(endReasoningItem(itemId, metadata))
                }
                openReasoningIds.toList().forEach { addAll(endReasoning(it, null)) }
                openImageIds.toList().forEach { addAll(endImage(it)) }
                openToolIds.toList().forEach { addAll(endTool(it)) }
                openServerToolIds.clear()
                add(StreamChunk.Finish(finishReason, responseId, model))
            }
        }
    }
}
