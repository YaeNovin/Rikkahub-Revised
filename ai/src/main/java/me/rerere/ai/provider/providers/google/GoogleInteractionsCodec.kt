package me.rerere.ai.provider.providers.google

import kotlinx.serialization.json.*
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.*
import me.rerere.ai.provider.stream.*
import me.rerere.ai.ui.*
import me.rerere.ai.util.json
import me.rerere.ai.util.mergeCustomBody
import kotlin.uuid.Uuid

/** A distinct wire codec: GenerateContent part signatures cannot be used as Interactions steps. */
internal fun buildGoogleInteractionsRequest(
    messages: List<UIMessage>,
    params: TextGenerationParams,
    stream: Boolean,
    mediaPart: (UIMessagePart) -> JsonObject?,
): JsonObject {
    val options = params.geminiOptions
    val search = options.search.resolvedFor(params.model)
    val explicitBuiltIns = options.search.enabled == true || options.search.urlContext == true || options.codeExecution
    val functions = params.model.supportsFunctionCalling() && params.tools.isNotEmpty() &&
        (params.model.supportsGoogleToolCombination() || !explicitBuiltIns)
    val serverTools = !functions || params.model.supportsGoogleToolCombination()
    require(search.enabled != true || (search.startTime.isBlank() && search.endTime.isBlank())) {
        "Interactions API 不支持搜索时间范围，请清空时间范围或使用 GenerateContent。"
    }
    require(options.responseMimeType != GeminiResponseMimeType.ENUM) {
        "Interactions API 不支持 text/x.enum，请使用 JSON Schema 或 GenerateContent。"
    }
    fun media(part: UIMessagePart): JsonObject {
        val (type, url) = when (part) {
            is UIMessagePart.Image -> "image" to part.url
            is UIMessagePart.Video -> "video" to part.url
            is UIMessagePart.Audio -> "audio" to part.url
            is UIMessagePart.Document -> "document" to part.url
            else -> error("Interactions 不支持此附件类型")
        }
        if (url.startsWith("https://") || url.startsWith("http://")) return buildJsonObject {
            put("type", type); put("uri", url)
            if (part is UIMessagePart.Document) put("mime_type", part.mime)
            if (type == "image" || type == "video") options.mediaResolution.apiValue
                ?.removePrefix("MEDIA_RESOLUTION_")?.lowercase()?.let { put("resolution", it) }
        }
        return requireNotNull(mediaPart(part)) { "附件无法读入 Interactions 请求，请重新选择附件。" }
            .toInteractionContent(options.mediaResolution)
    }
    return buildJsonObject {
        put("model", params.model.modelId.removePrefix("models/"))
        put("stream", stream)
        put("store", false)
        put("background", false)
        messages.filter { it.role == MessageRole.SYSTEM }.takeIf { it.isNotEmpty() }?.let {
            put("system_instruction", it.joinToString("\n") { message -> message.toText() })
        }
        put("input", buildJsonArray {
            messages.filter { it.role != MessageRole.SYSTEM }.forEach { message ->
                val seenSteps = mutableSetOf<String>()
                val pendingResults = mutableListOf<JsonObject>()
                val content = mutableListOf<JsonObject>()
                fun flush() {
                    if (content.isEmpty()) return
                    add(buildJsonObject {
                        put("type", if (message.role == MessageRole.ASSISTANT) "model_output" else "user_input")
                        put("content", JsonArray(content.toList()))
                    })
                    content.clear()
                }
                message.parts.forEach { part ->
                    val replay = part.metadataAs<GoogleInteractionMetadata>()
                    if (part !is UIMessagePart.Tool && pendingResults.isNotEmpty()) {
                        flush()
                        pendingResults.forEach(::add)
                        pendingResults.clear()
                    }
                    if (replay?.step != null) {
                        flush()
                        if (replay.stepKey == null || seenSteps.add(replay.stepKey)) {
                            val raw = replay.step
                            if (raw.string("type") == "model_output" && part is UIMessagePart.Text && textOf(raw["content"]) != part.text) {
                                val media = (raw["content"] as? JsonArray).orEmpty().filter { (it as? JsonObject)?.string("type") != "text" }
                                add(JsonObject(raw + ("content" to JsonArray(listOf(buildJsonObject { put("type", "text"); put("text", part.text) }) + media))))
                            } else add(raw)
                        }
                    } else if (part is UIMessagePart.Tool) {
                        flush()
                        add(buildJsonObject {
                            put("type", "function_call")
                            put("id", part.toolCallId)
                            put("name", part.toolName)
                            put("arguments", part.inputAsJson())
                        })
                    } else if (replay?.stepKey != null) {
                        // An image belonging to an already replayed model_output step.
                    } else when (part) {
                        is UIMessagePart.Text -> if (part.text.isNotEmpty()) content.add(buildJsonObject {
                            put("type", "text"); put("text", part.text)
                        })
                        is UIMessagePart.Image, is UIMessagePart.Audio,
                        is UIMessagePart.Video, is UIMessagePart.Document ->
                            content.add(media(part))
                        else -> Unit // Other protocols' opaque reasoning is never reinterpreted.
                    }
                    if (part is UIMessagePart.Tool && part.isExecuted) {
                        pendingResults.add(buildJsonObject {
                            put("type", "function_result")
                            put("call_id", replay?.step?.string("id") ?: part.toolCallId)
                            put("name", part.toolName)
                            put("result", buildJsonArray {
                                part.output.forEach { result ->
                                    if (result is UIMessagePart.Text) add(buildJsonObject {
                                        put("type", "text"); put("text", result.text)
                                    }) else if (result is UIMessagePart.Image) add(media(result))
                                    else add(buildJsonObject {
                                        put("type", "text"); put("text", "[Interactions 函数结果仅支持文字和图片，此附件类型未回传]")
                                    })
                                }
                            })
                        })
                    }
                }
                flush()
                // Parallel calls must all precede their results.
                pendingResults.forEach(::add)
            }
        })
        put("generation_config", buildJsonObject {
            if (!params.model.supportsGoogleToolCombination()) {
                params.temperature?.let { put("temperature", it) }
                params.topP?.let { put("top_p", it) }
            }
            params.maxTokens?.let { put("max_output_tokens", it) }
            options.seed?.let { put("seed", it) }
            options.stopSequences.filter(String::isNotEmpty).take(5).takeIf { it.isNotEmpty() }?.let {
                put("stop_sequences", buildJsonArray { it.forEach(::add) })
            }
            put("thinking_summaries", if (options.includeThoughts) "auto" else "none")
            val level = if (params.reasoningLevel == ReasoningLevel.OFF) ReasoningLevel.LOW
                else resolveReasoningLevelSupport(params.model, ProviderSetting.Google()).coerce(params.reasoningLevel)
            if (level != ReasoningLevel.AUTO && params.model.supportsReasoningCapability()) {
                put("thinking_level", when (level) {
                    ReasoningLevel.XHIGH, ReasoningLevel.MAX -> if (params.model.isGeminiHydrogen()) "xhigh" else "high"
                    ReasoningLevel.HIGH -> "high"
                    ReasoningLevel.MEDIUM -> "medium"
                    else -> "low"
                })
            }
            if (functions) put("tool_choice", options.toolChoice.name.lowercase())
        })
        val schema = options.responseJsonSchema
            .takeIf { it.isNotBlank() && options.responseMimeType != GeminiResponseMimeType.TEXT }
            ?.let(::parseOutputSchema)
        if (options.responseMimeType != GeminiResponseMimeType.AUTO || schema != null) {
            put("response_format", buildJsonObject {
                put("type", "text")
                put("mime_type", options.responseMimeType.apiValue ?: "application/json")
                schema?.let { put("schema", it) }
            })
        }
        put("tools", buildJsonArray {
            if (functions) params.tools.forEach { tool ->
                add(buildJsonObject {
                    put("type", "function"); put("name", tool.name); put("description", tool.description)
                    put("parameters", tool.wireParameters())
                })
            }
            if (serverTools && search.enabled == true) add(buildJsonObject {
                put("type", "google_search")
                put("search_types", buildJsonArray {
                    val image = search.imageSearch && params.model.supportsGoogleImageSearch()
                    if (search.webSearch || !image) add("web_search")
                    if (image) add("image_search")
                })
            })
            if (serverTools && search.urlContext == true) add(buildJsonObject { put("type", "url_context") })
            if (serverTools && options.codeExecution) add(buildJsonObject { put("type", "code_execution") })
        })
        val safety = options.safetySettings
        put("safety_settings", buildJsonArray {
            listOf("harassment" to safety.harassment, "hate_speech" to safety.hateSpeech,
                "sexually_explicit" to safety.sexuallyExplicit, "dangerous_content" to safety.dangerousContent)
                .forEach { (category, threshold) ->
                    threshold.apiValue?.let { add(buildJsonObject {
                        put("type", category)
                        put("threshold", if (threshold == GeminiSafetyThreshold.BLOCK_NONE) "off" else it.lowercase())
                    }) }
                }
        })
    }.mergeCustomBody(params.customBody).let { body ->
        require(listOf("contents", "generationConfig", "systemInstruction", "safetySettings", "toolConfig").none(body::containsKey)) {
            "Interactions API 使用 snake_case 参数；请移除 GenerateContent 专用自定义参数。"
        }
        // Local branching owns history. Prevent custom settings from enabling unrelated remote state.
        JsonObject(body.toMutableMap().apply {
            put("store", JsonPrimitive(false)); put("background", JsonPrimitive(false)); put("stream", JsonPrimitive(stream))
            remove("previous_interaction_id")
            remove("continuation_token")
        })
    }
}

private fun JsonObject.toInteractionContent(resolution: GeminiMediaResolution): JsonObject {
    string("text")?.let { return buildJsonObject { put("type", "text"); put("text", it) } }
    val media = (get("inlineData") ?: get("fileData")) as? JsonObject ?: error("不支持的 Interactions 媒体格式")
    val mime = media.string("mimeType") ?: "application/octet-stream"
    return buildJsonObject {
        put("type", when { mime.startsWith("image/") -> "image"; mime.startsWith("video/") -> "video"
            mime.startsWith("audio/") -> "audio"; else -> "document" })
        put("mime_type", mime)
        media["data"]?.let { put("data", it) }
        media["fileUri"]?.let { put("uri", it) }
        if (mime.startsWith("image/") || mime.startsWith("video/"))
            resolution.apiValue?.removePrefix("MEDIA_RESOLUTION_")?.lowercase()?.let { put("resolution", it) }
    }
}

internal fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
internal fun JsonObject.interactionUsage(): TokenUsage {
    fun count(key: String) = (get(key) as? JsonPrimitive)?.intOrNull ?: 0
    return TokenUsage(count("total_input_tokens"), count("total_output_tokens") + count("total_thought_tokens"),
        count("total_cached_tokens"), count("total_tokens"))
}

internal fun parseGoogleInteraction(body: JsonObject): TextGenerationResult {
    checkInteractionStatus(body)
    val decoder = GoogleInteractionsStreamDecoder()
    val handler = StreamChunkHandler()
    var messages = listOf(UIMessage(role = MessageRole.ASSISTANT, parts = emptyList()))
    body["steps"]?.jsonArray.orEmpty().forEachIndexed { i, step ->
        decoder.start(i, step.jsonObject).forEach { messages = handler.handle(messages, it) }
        decoder.stop(i).forEach { messages = handler.handle(messages, it) }
    }
    val usage = (body["usage"] as? JsonObject)?.interactionUsage()
    return TextGenerationResult(body.string("id").orEmpty(), body.string("model").orEmpty(),
        messages.last(), body.string("status"), usage)
}

internal fun checkInteractionStatus(body: JsonObject) {
    if (body.string("status") in setOf("failed", "cancelled")) error("Gemini Interactions: ${body["errors"] ?: body.string("status")}")
}

/** One decoder per HTTP hop. Step metadata is finalized before the terminal event is emitted. */
internal class GoogleInteractionsStreamDecoder : StreamChunkDecoder {
    private class Block(val id: String, var raw: JsonObject) {
        val arguments = StringBuilder()
        val content = (raw["content"] as? JsonArray).orEmpty().toMutableList()
        private var text: StringBuilder? = null
        fun appendText(delta: String) {
            if (text == null) {
                val last = content.lastOrNull() as? JsonObject
                text = if (last?.string("type") == "text") {
                    content.removeAt(content.lastIndex)
                    StringBuilder(last.string("text").orEmpty())
                } else StringBuilder()
            }
            text!!.append(delta)
        }
        fun flushText() {
            text?.let { buffer -> content.add(buildJsonObject { put("type", "text"); put("text", buffer.toString()) }) }
            text = null
        }
    }
    private val blocks = linkedMapOf<Int, Block>()
    val steps = mutableListOf<JsonObject>()
    private val prefix = Uuid.random().toString()
    private var completed = false
    var continuationToken: String? = null
        private set
    var status: String? = null
        private set
    private var responseId: String? = null
    private var model: String? = null

    override fun accept(event: SseEvent): DecodeResult {
        if (completed) return DecodeResult(completed = true)
        if (event.data == "[DONE]" || event.event == "done") {
            prematureStreamTermination("Google Interactions (missing terminal interaction)")
        }
        val payload = json.parseToJsonElement(event.data).jsonObject
        val chunks = mutableListOf<StreamChunk>()
        val index = (payload["index"] as? JsonPrimitive)?.intOrNull ?: 0
        when (payload.string("event_type") ?: event.event) {
            "interaction.created" -> {
                val interaction = payload["interaction"] as? JsonObject
                responseId = interaction?.string("id"); model = interaction?.string("model")
            }
            "step.start" -> chunks += start(index, payload["step"]!!.jsonObject)
            "step.delta" -> chunks += delta(index, payload["delta"]!!.jsonObject)
            "step.stop" -> chunks += stop(index)
            "error", "interaction.failed" -> error("Gemini Interactions: ${payload["error"] ?: payload["interaction"] ?: payload}")
            "interaction.completed", "interaction.status_update" -> {
                val interaction = (payload["interaction"] as? JsonObject) ?: payload
                val state = interaction.string("status") ?: "completed"
                checkInteractionStatus(interaction)
                if (state != "in_progress") {
                    blocks.keys.toList().forEach { chunks += stop(it) }
                    status = state
                    continuationToken = interaction.string("continuation_token")
                    require(state != "requires_continuation" || !continuationToken.isNullOrBlank()) {
                        "Interactions continuation token missing"
                    }
                    (interaction["usage"] as? JsonObject)?.let { chunks += StreamChunk.Usage(it.interactionUsage()) }
                    responseId = interaction.string("id") ?: interaction.string("interaction_id") ?: responseId
                    chunks += StreamChunk.Finish(state, responseId, model)
                    completed = true
                }
            }
        }
        ((payload["metadata"] as? JsonObject)?.get("total_usage") as? JsonObject)
            ?.let { chunks += StreamChunk.Usage(it.interactionUsage()) }
        return DecodeResult(chunks, completed)
    }

    override fun onClosed(): List<StreamChunk> {
        if (!completed) prematureStreamTermination("Google Interactions")
        return emptyList()
    }

    internal fun start(index: Int, step: JsonObject): List<StreamChunk> {
        require(index !in blocks) { "Duplicate Interactions step index $index" }
        val block = Block(step.string("id") ?: "$prefix:$index", step)
        blocks[index] = block
        return when (step.string("type")) {
            "model_output" -> listOf(StreamChunk.TextStart(block.id)) + contentChunks(block, step["content"] as? JsonArray)
            "thought" -> listOf(StreamChunk.ReasoningStart(block.id)) +
                listOf(StreamChunk.ReasoningDelta(block.id, textOf(step["summary"])))
            "function_call" -> listOf(StreamChunk.ToolCallStart(block.id, step.string("name").orEmpty()))
            else -> listOf(StreamChunk.ServerToolStart(block.id, step.string("type").orEmpty(), step["arguments"]))
        }
    }

    private fun delta(index: Int, delta: JsonObject): List<StreamChunk> {
        val block = blocks[index] ?: error("Interactions delta has no step.start ($index)")
        val fields = block.raw.toMutableMap()
        val chunks = mutableListOf<StreamChunk>()
        when (delta.string("type")) {
            "text" -> {
                val text = delta.string("text").orEmpty()
                block.appendText(text)
                chunks += StreamChunk.TextDelta(block.id, text)
            }
            "thought_summary" -> {
                val content = delta["content"] ?: JsonObject(emptyMap())
                fields["summary"] = JsonArray((fields["summary"] as? JsonArray).orEmpty() + content)
                chunks += StreamChunk.ReasoningDelta(block.id, textOf(JsonArray(listOf(content))))
            }
            "thought_signature" -> delta["signature"]?.let { fields["signature"] = it }
            "arguments_delta" -> block.arguments.append(delta.string("arguments").orEmpty())
            "text_annotation_delta" -> chunks += interactionAnnotations(delta)
            "image" -> {
                block.flushText()
                block.content.add(delta)
                chunks += contentChunks(block, JsonArray(listOf(delta)))
            }
            else -> delta.filterKeys { it != "type" }.forEach { (key, value) ->
                fields[key] = if (value is JsonArray && fields[key] is JsonArray) JsonArray((fields[key] as JsonArray) + value) else value
            }
        }
        block.raw = JsonObject(fields)
        return chunks
    }

    internal fun stop(index: Int): List<StreamChunk> {
        val block = blocks.remove(index) ?: return emptyList()
        if (block.raw.string("type") == "model_output") {
            block.flushText()
            block.raw = JsonObject(block.raw + ("content" to JsonArray(block.content)))
        }
        if (block.arguments.isNotEmpty()) block.raw = JsonObject(block.raw +
            ("arguments" to json.parseToJsonElement(block.arguments.toString()).jsonObject))
        val metadata = GoogleInteractionMetadata(block.raw, block.id).toMetadata()
        steps.add(block.raw)
        return when (block.raw.string("type")) {
            "model_output" -> listOf(StreamChunk.TextDelta(block.id, "", metadata), StreamChunk.TextEnd(block.id))
            "thought" -> listOf(StreamChunk.ReasoningEnd(block.id, metadata))
            "function_call" -> listOf(StreamChunk.ToolCallDelta(block.id,
                inputDelta = (block.raw["arguments"] ?: JsonObject(emptyMap())).toString(), metadata = metadata), StreamChunk.ToolCallEnd(block.id))
            else -> listOf(StreamChunk.ServerToolEnd(block.id, input = block.raw["arguments"], output = block.raw["result"],
                status = if ((block.raw["is_error"] as? JsonPrimitive)?.booleanOrNull == true) ServerToolStatus.FAILED else ServerToolStatus.COMPLETED,
                metadata = metadata)) + interactionAnnotations(block.raw)
        }
    }

    private fun contentChunks(block: Block, content: JsonArray?): List<StreamChunk> = buildList {
        content.orEmpty().forEachIndexed { index, value ->
            val part = value as? JsonObject ?: return@forEachIndexed
            when (part.string("type")) {
                "text" -> { add(StreamChunk.TextDelta(block.id, part.string("text").orEmpty())); addAll(interactionAnnotations(part)) }
                "image" -> {
                    val id = "${block.id}:image:${Uuid.random()}"
                    val data = part.string("uri") ?: part.string("data")?.let { "data:${part.string("mime_type") ?: "image/png"};base64,$it" }
                    if (data != null) {
                        add(StreamChunk.ImageStart(id, part.string("mime_type") ?: "image/png", GoogleInteractionMetadata(stepKey = block.id).toMetadata()))
                        add(StreamChunk.ImageSnapshot(id, data)); add(StreamChunk.ImageEnd(id))
                    }
                }
            }
        }
    }
}

private fun textOf(value: JsonElement?): String = (value as? JsonArray).orEmpty()
    .mapNotNull { (it as? JsonObject)?.string("text") }.joinToString("")

private fun interactionAnnotations(step: JsonObject): List<StreamChunk> {
    val annotations = mutableListOf<UIMessageAnnotation>()
    (step["annotations"] as? JsonArray).orEmpty().forEach { value ->
        val citation = value as? JsonObject ?: return@forEach
        val url = citation.string("url") ?: citation.string("uri") ?: return@forEach
        annotations.add(UIMessageAnnotation.UrlCitation(citation.string("title") ?: url, url))
    }
    if (step.string("type") == "google_search_result") (step["result"] as? JsonArray).orEmpty().forEach { value ->
        (value as? JsonObject)?.string("search_suggestions")?.let { annotations.add(UIMessageAnnotation.GoogleSearchSuggestions(html = it)) }
    }
    return if (annotations.isEmpty()) emptyList() else listOf(StreamChunk.Annotations(annotations))
}
