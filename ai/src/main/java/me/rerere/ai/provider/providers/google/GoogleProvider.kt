package me.rerere.ai.provider.providers.google

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.onFailure
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import androidx.core.net.toUri
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.cappedBudget
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.EmbeddingGenerationParams
import me.rerere.ai.provider.EmbeddingGenerationResult
import me.rerere.ai.provider.ImageEditParams
import me.rerere.ai.provider.ImageGenerationConstraints
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelDiscoveryProtocol
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderCapability
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.inferModelTypeFromId
import me.rerere.ai.provider.providerRequestFailure
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.contextWindowTokensOrNull
import me.rerere.ai.provider.providers.PartGroup
import me.rerere.ai.provider.providers.google.vertex.ServiceAccountTokenProvider
import me.rerere.ai.provider.providers.groupPartsByToolBoundary
import me.rerere.ai.provider.stream.SseEvent
import me.rerere.ai.registry.ModelRegistry
import me.rerere.ai.ui.GoogleThoughtMetadata
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.metadataAs
import me.rerere.ai.ui.toMetadata
import me.rerere.ai.util.KeyRoulette
import me.rerere.ai.util.configureReferHeaders
import me.rerere.ai.util.encodeBase64
import me.rerere.ai.util.json
import me.rerere.ai.util.mergeCustomBody
import me.rerere.ai.util.removeElements
import me.rerere.ai.util.stringSafe
import me.rerere.ai.util.toHeaders
import me.rerere.common.http.await
import me.rerere.common.http.jsonPrimitiveOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.apache.commons.text.StringEscapeUtils
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.uuid.Uuid

private const val TAG = "GoogleProvider"
private const val LARGE_MEDIA_UPLOAD_BYTES = 8L * 1024L * 1024L
private const val MEDIA_UPLOAD_CONCURRENCY = 2
private const val MEDIA_UPLOAD_ATTEMPTS = 3
private const val MEDIA_UPLOAD_RETRY_DELAY_MILLIS = 600L
private const val MEDIA_ACTIVE_POLL_INITIAL_MILLIS = 250L
private const val MEDIA_ACTIVE_POLL_MAX_MILLIS = 1_000L
private const val MEDIA_CACHE_TTL_MILLIS = 24L * 60L * 60L * 1_000L
private const val MAX_IMAGE_RESPONSE_BYTES = 96L * 1024L * 1024L
internal val GOOGLE_IMAGE_ASPECT_RATIOS = linkedSetOf(
    "1:1", "2:3", "3:2", "3:4", "4:3", "4:5", "5:4", "9:16", "16:9", "21:9",
)
internal val GOOGLE_EXTENDED_IMAGE_ASPECT_RATIOS = linkedSetOf(
    "1:1", "1:4", "1:8", "2:3", "3:2", "3:4", "4:1", "4:3", "4:5", "5:4",
    "8:1", "9:16", "16:9", "21:9",
)
internal val GOOGLE_3_IMAGE_RESOLUTIONS = linkedSetOf("1K", "2K", "4K")
internal val GOOGLE_31_IMAGE_RESOLUTIONS = linkedSetOf("512", "1K", "2K", "4K")

private data class UploadedGoogleFile(
    val uri: String,
    val name: String,
    val mimeType: String,
    val state: String = "ACTIVE",
    val cachedAtMillis: Long = System.currentTimeMillis(),
)

class GoogleProvider(private val client: OkHttpClient, context: Context? = null) : Provider<ProviderSetting.Google> {
    override val capabilities: Set<ProviderCapability> = setOf(
        ProviderCapability.IMAGE_GENERATION,
        ProviderCapability.IMAGE_EDIT,
    )

    private val keyRoulette = if (context != null) KeyRoulette.lru(context) else KeyRoulette.default()
    private val serviceAccountTokenProvider by lazy {
        ServiceAccountTokenProvider(client)
    }
    private val uploadedFileCache = ConcurrentHashMap<String, UploadedGoogleFile>()

    private fun buildUrl(providerSetting: ProviderSetting.Google, path: String): HttpUrl {
        return if (!providerSetting.vertexAI) {
            "${providerSetting.baseUrl}/$path".toHttpUrl()
        } else if (providerSetting.useServiceAccount) {
            "https://aiplatform.googleapis.com/v1/projects/${providerSetting.projectId}/locations/${providerSetting.location}/$path".toHttpUrl()
        } else {
            "https://aiplatform.googleapis.com/v1/$path".toHttpUrl()
        }
    }

    private suspend fun transformRequest(
        providerSetting: ProviderSetting.Google,
        request: Request
    ): Request {
        return if (providerSetting.vertexAI && providerSetting.useServiceAccount) {
            val accessToken = serviceAccountTokenProvider.fetchAccessToken(
                serviceAccountEmail = providerSetting.serviceAccountEmail.trim(),
                privateKeyPem = StringEscapeUtils.unescapeJson(providerSetting.privateKey.trim()),
            )
            request.newBuilder()
                .addHeader("Authorization", "Bearer $accessToken")
                .build()
        } else {
            val key = keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())
            if (providerSetting.vertexAI) {
                request.newBuilder()
                    .url(request.url.newBuilder().addQueryParameter("key", key).build())
                    .build()
            } else {
                request.newBuilder()
                    .addHeader("x-goog-api-key", key)
                    .build()
            }
        }
    }

    override suspend fun listModels(providerSetting: ProviderSetting.Google): List<Model> =
        withContext(Dispatchers.IO) {
            val url = buildUrl(providerSetting = providerSetting, path = "models?pageSize=1000")
            val request = transformRequest(
                providerSetting = providerSetting,
                request = Request.Builder()
                    .url(url)
                    .get()
                    .build()
            )
            val response = client.newCall(request).await()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: error("empty body")
                val bodyObject = json.parseToJsonElement(body).jsonObject
                val models = bodyObject["models"]?.jsonArray ?: return@withContext emptyList()

                models.mapNotNull {
                    val modelObject = it.jsonObject

                    val supportedGenerationMethods =
                        (modelObject["supportedGenerationMethods"] as? JsonArray)
                            .orEmpty()
                            .mapNotNull { method -> method.jsonPrimitive.contentOrNull }
                    val fullModelId = modelObject["name"]?.jsonPrimitive?.contentOrNull
                        ?: return@mapNotNull null
                    val modelId = fullModelId.substringAfter("/")
                    val modelType = inferGoogleModelType(modelId, supportedGenerationMethods)
                    if (modelType == null) {
                        return@mapNotNull null
                    }

                    Model(
                        modelId = modelId,
                        displayName = modelObject["displayName"]?.jsonPrimitive?.contentOrNull ?: modelId,
                        type = modelType,
                        inputModalities = ModelRegistry.MODEL_INPUT_MODALITIES.getData(modelId),
                        outputModalities = ModelRegistry.MODEL_OUTPUT_MODALITIES.getData(modelId),
                        abilities = ModelRegistry.MODEL_ABILITIES.getData(modelId),
                        contextWindowTokens = modelObject.contextWindowTokensOrNull(
                            modelId = fullModelId,
                            protocol = ModelDiscoveryProtocol.GOOGLE,
                        ),
                    )
                }
            } else {
                emptyList()
            }
        }

    override fun imageGenerationConstraints(
        providerSetting: ProviderSetting,
        model: Model,
    ): ImageGenerationConstraints {
        val modelId = model.modelId.substringAfterLast('/').lowercase()
        val isGeminiImage = modelId.startsWith("gemini-") &&
            inferModelTypeFromId(modelId) == ModelType.IMAGE
        if (providerSetting !is ProviderSetting.Google || !isGeminiImage) {
            return ImageGenerationConstraints(
                supportsGeneration = false,
                supportsEdit = false,
                supportsPartialImages = false,
                supportsSize = false,
                supportsCustomSize = false,
            )
        }

        val isGemini31 = modelId.startsWith("gemini-3.1-")
        val isGemini3 = modelId.startsWith("gemini-3-") || isGemini31
        return ImageGenerationConstraints(
            supportsGeneration = true,
            supportsEdit = true,
            supportsPartialImages = false,
            maxOutputImages = 1,
            maxReferenceImages = if (isGemini3) 14 else 3,
            supportsSize = true,
            supportedSizes = if (isGemini31) GOOGLE_EXTENDED_IMAGE_ASPECT_RATIOS else GOOGLE_IMAGE_ASPECT_RATIOS,
            supportsCustomSize = false,
            sizeRequestField = "aspect_ratio",
            supportedResolutionValues = when {
                isGemini31 -> GOOGLE_31_IMAGE_RESOLUTIONS
                isGemini3 -> GOOGLE_3_IMAGE_RESOLUTIONS
                else -> emptySet()
            },
        )
    }

    override suspend fun generateImage(
        providerSetting: ProviderSetting,
        params: ImageGenerationParams,
    ): Flow<ImageGenerationItem> = flow {
        require(providerSetting is ProviderSetting.Google) { "Expected Google provider setting" }
        val constraints = imageGenerationConstraints(providerSetting, params.model)
        require(constraints.supportsGeneration) { "Selected Google model does not support image generation" }

        val requestBody = buildGoogleImageRequestBody(
            prompt = params.prompt,
            size = params.size,
            resolution = params.resolution,
            customBody = params.customBody,
            constraints = constraints,
            imageParts = emptyList(),
        )
        requestGoogleImages(providerSetting, params.model, params.customHeaders.toHeaders(), requestBody)
            .forEach { emit(it) }
    }.flowOn(Dispatchers.IO)

    override suspend fun editImage(
        providerSetting: ProviderSetting,
        params: ImageEditParams,
    ): Flow<ImageGenerationItem> = flow {
        require(providerSetting is ProviderSetting.Google) { "Expected Google provider setting" }
        val constraints = imageGenerationConstraints(providerSetting, params.model)
        require(constraints.supportsEdit) { "Selected Google model does not support image editing" }
        require(params.images.isNotEmpty()) { "At least one image is required" }

        val imageParts = params.images.take(constraints.maxReferenceImages).map { path ->
            val imageFile = File(path)
            require(imageFile.isFile) { "Image file does not exist: $path" }
            UIMessagePart.Image(url = imageFile.absolutePath).toGooglePart()
                ?: error("Failed to encode reference image: ${imageFile.name}")
        }
        val requestBody = buildGoogleImageRequestBody(
            prompt = params.prompt,
            size = params.size,
            resolution = params.resolution,
            customBody = params.customBody,
            constraints = constraints,
            imageParts = imageParts,
        )
        requestGoogleImages(providerSetting, params.model, params.customHeaders.toHeaders(), requestBody)
            .forEach { emit(it) }
    }.flowOn(Dispatchers.IO)

    private suspend fun requestGoogleImages(
        providerSetting: ProviderSetting.Google,
        model: Model,
        headers: okhttp3.Headers,
        requestBody: JsonObject,
    ): List<ImageGenerationItem> {
        val path = if (providerSetting.vertexAI) {
            "publishers/google/models/${model.modelId}:generateContent"
        } else {
            "models/${model.modelId}:generateContent"
        }
        val request = transformRequest(
            providerSetting = providerSetting,
            request = Request.Builder()
                .url(buildUrl(providerSetting, path))
                .headers(headers)
                .post(json.encodeToString(requestBody).toRequestBody("application/json".toMediaType()))
                .configureReferHeaders(providerSetting.baseUrl)
                .build(),
        )

        return client.newCall(request).await().use { response ->
            if (!response.isSuccessful) {
                val detail = response.body?.string()
                throw providerRequestFailure(
                    response = response,
                    cause = null,
                    detail = "Failed to generate image: ${response.code} $detail",
                )
            }
            val responseBody = response.body ?: error("Empty image generation response")
            val contentLength = responseBody.contentLength()
            require(contentLength < 0 || contentLength <= MAX_IMAGE_RESPONSE_BYTES) {
                "Image generation response is too large"
            }
            val body = json.parseToJsonElement(responseBody.string()).jsonObject
            parseGoogleGeneratedImages(body).ifEmpty {
                error("Google response did not contain a generated image")
            }
        }
    }

    override suspend fun generateEmbedding(
        providerSetting: ProviderSetting.Google,
        params: EmbeddingGenerationParams,
    ): EmbeddingGenerationResult = withContext(Dispatchers.IO) {
        require(params.input.isNotEmpty()) { "Embedding input cannot be empty" }

        val requestBody = if (providerSetting.vertexAI) {
            buildJsonObject {
                put("instances", buildJsonArray {
                    params.input.forEach { text ->
                        add(buildJsonObject { put("content", text) })
                    }
                })
                params.dimensions?.let { dimensions ->
                    put("parameters", buildJsonObject {
                        put("outputDimensionality", dimensions)
                    })
                }
            }
        } else if (params.input.size == 1) {
            buildJsonObject {
                put("model", "models/${params.model.modelId}")
                put("content", embeddingContent(params.input.single()))
                params.dimensions?.let { dimensions ->
                    put("outputDimensionality", dimensions)
                }
            }
        } else {
            buildJsonObject {
                put("requests", buildJsonArray {
                    params.input.forEach { text ->
                        add(buildJsonObject {
                            put("model", "models/${params.model.modelId}")
                            put("content", embeddingContent(text))
                            params.dimensions?.let { dimensions ->
                                put("outputDimensionality", dimensions)
                            }
                        })
                    }
                })
            }
        }.mergeCustomBody(params.customBody)

        val path = if (providerSetting.vertexAI) {
            "publishers/google/models/${params.model.modelId}:predict"
        } else if (params.input.size == 1) {
            "models/${params.model.modelId}:embedContent"
        } else {
            "models/${params.model.modelId}:batchEmbedContents"
        }
        val request = transformRequest(
            providerSetting = providerSetting,
            request = Request.Builder()
                .url(buildUrl(providerSetting, path))
                .headers(params.customHeaders.toHeaders())
                .post(json.encodeToString(requestBody).toRequestBody("application/json".toMediaType()))
                .configureReferHeaders(providerSetting.baseUrl)
                .build()
        )

        val response = client.newCall(request).await()
        if (!response.isSuccessful) {
            error("Failed to generate embedding: ${response.code} ${response.body?.string()}")
        }
        val body = json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject
        val embeddings = if (providerSetting.vertexAI) {
            body["predictions"]?.jsonArray.orEmpty().map { prediction ->
                prediction.jsonObject["embeddings"]?.jsonObject?.get("values")?.jsonArray
                    ?.map { it.jsonPrimitive.content.toFloat() }
                    ?: error("No embedding values in Vertex response")
            }
        } else if (params.input.size == 1) {
            listOf(
                body["embedding"]?.jsonObject?.get("values")?.jsonArray
                    ?.map { it.jsonPrimitive.content.toFloat() }
                    ?: error("No embedding values in Google response")
            )
        } else {
            body["embeddings"]?.jsonArray?.map { embedding ->
                embedding.jsonObject["values"]?.jsonArray
                    ?.map { it.jsonPrimitive.content.toFloat() }
                    ?: error("No embedding values in Google response")
            } ?: error("No embeddings in Google response")
        }
        require(embeddings.size == params.input.size) {
            "Google returned ${embeddings.size} vectors for ${params.input.size} inputs"
        }
        EmbeddingGenerationResult(
            model = params.model.modelId,
            embeddings = embeddings,
        )
    }

    private fun embeddingContent(text: String) = buildJsonObject {
        put("parts", buildJsonArray {
            add(buildJsonObject { put("text", text) })
        })
    }

    override suspend fun generateText(
        providerSetting: ProviderSetting.Google,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): TextGenerationResult = withContext(Dispatchers.IO) {
        val uploadedFiles = uploadLargeMedia(providerSetting, messages)
        val requestBody = buildCompletionRequestBody(messages, params, uploadedFiles)

        val url = buildUrl(
            providerSetting = providerSetting,
            path = if (providerSetting.vertexAI) {
                "publishers/google/models/${params.model.modelId}:generateContent"
            } else {
                "models/${params.model.modelId}:generateContent"
            }
        )

        val request = transformRequest(
            providerSetting = providerSetting,
            request = Request.Builder()
                .url(url)
                .headers(params.customHeaders.toHeaders())
                .post(
                    json.encodeToString(requestBody).toRequestBody("application/json".toMediaType())
                )
                .configureReferHeaders(providerSetting.baseUrl)
                .build()
        )

        val response = client.newCall(request).await()
        if (!response.isSuccessful) {
            val body = response.body?.string()
            throw providerRequestFailure(
                response = response,
                cause = null,
                detail = "Failed to get response: ${response.code} $body",
            )
        }

        val bodyStr = response.body?.string() ?: ""
        val bodyJson = json.parseToJsonElement(bodyStr).jsonObject

        val candidate = bodyJson["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: error("No candidates in response")
        TextGenerationResult(
            id = Uuid.random().toString(),
            model = params.model.modelId,
            message = parseMessage(candidate),
            finishReason = candidate["finishReason"]?.jsonPrimitive?.contentOrNull,
            usage = parseUsageMeta(bodyJson["usageMetadata"] as? JsonObject),
        )
    }

    override suspend fun streamText(
        providerSetting: ProviderSetting.Google,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<StreamChunk> = callbackFlow {
        val uploadedFiles = uploadLargeMedia(providerSetting, messages)
        val requestBody = buildCompletionRequestBody(messages, params, uploadedFiles)

        val url = buildUrl(
            providerSetting = providerSetting,
            path = if (providerSetting.vertexAI) {
                "publishers/google/models/${params.model.modelId}:streamGenerateContent"
            } else {
                "models/${params.model.modelId}:streamGenerateContent"
            }
        ).newBuilder().addQueryParameter("alt", "sse").build()

        val request = transformRequest(
            providerSetting = providerSetting,
            request = Request.Builder()
                .url(url)
                .headers(params.customHeaders.toHeaders())
                .post(
                    json.encodeToString(requestBody).toRequestBody("application/json".toMediaType())
                )
                .configureReferHeaders(providerSetting.baseUrl)
                .build()
        )

        Log.i(TAG, "streamText: model=${params.model.modelId}, uploadedFiles=${uploadedFiles.size}")

        val responseId = Uuid.random().toString()
        val decoder = GoogleStreamDecoder(responseId, params.model.modelId)

        fun sendChunks(chunks: Iterable<StreamChunk>) {
            chunks.forEach { chunk ->
                trySend(chunk).onFailure { e ->
                    Log.w(TAG, "onEvent: chunk dropped (${e?.message})")
                }
            }
        }

        val listener = object : EventSourceListener() {
            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String
            ) {
                Log.i(TAG, "onEvent: $data")

                try {
                    val result = decoder.accept(SseEvent(id = id, event = type, data = data))
                    sendChunks(result.chunks)
                    if (result.completed) close()
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed to parse stream event: $data", e)
                    close(e)
                }
            }

            override fun onFailure(
                eventSource: EventSource,
                t: Throwable?,
                response: Response?
            ) {
                var detail = t?.message

                t?.printStackTrace()
                println("[onFailure] 发生错误: ${t?.message}")

                try {
                    if (t == null && response != null) {
                        val bodyStr = response.body.stringSafe()
                        if (!bodyStr.isNullOrEmpty()) {
                            val bodyElement = json.parseToJsonElement(bodyStr)
                            println(bodyElement)
                            if (bodyElement is JsonObject) {
                                detail = bodyElement["error"]?.jsonObject?.get("message")
                                    ?.jsonPrimitive?.content ?: bodyStr
                            }
                        } else {
                            detail = "Unknown error: ${response.code}"
                        }
                    }
                } catch (e: Throwable) {
                    e.printStackTrace()
                } finally {
                    close(providerRequestFailure(response, t, detail ?: "Stream failed"))
                }
            }

            override fun onClosed(eventSource: EventSource) {
                println("[onClosed] 连接已关闭")
                sendChunks(decoder.onClosed())
                close()
            }
        }

        val eventSource = EventSources.createFactory(client)
                .newEventSource(request, listener)

        awaitClose {
            println("[awaitClose] 关闭eventSource")
            eventSource.cancel()
        }
        // trySend 在缓冲满时会静默丢弃 delta，导致回复中间缺字 (#1295)，因此缓冲必须无界
    }.buffer(Channel.UNLIMITED)

    private fun buildCompletionRequestBody(
        messages: List<UIMessage>,
        params: TextGenerationParams
    ): JsonObject = buildCompletionRequestBody(messages, params, emptyMap())

    private fun buildCompletionRequestBody(
        messages: List<UIMessage>,
        params: TextGenerationParams,
        uploadedFiles: Map<String, UploadedGoogleFile>,
    ): JsonObject = buildJsonObject {
        // System message if available
        val systemMessage = messages.firstOrNull { it.role == MessageRole.SYSTEM }
        if (systemMessage != null && !params.model.outputModalities.contains(Modality.IMAGE)) {
            put("systemInstruction", buildJsonObject {
                putJsonArray("parts") {
                    add(buildJsonObject {
                        put(
                            "text",
                            systemMessage.parts.filterIsInstance<UIMessagePart.Text>()
                                .joinToString { it.text })
                    })
                }
            })
        }

        // Generation config
        put("generationConfig", buildJsonObject {
            if (params.temperature != null) put("temperature", params.temperature)
            if (params.topP != null) put("topP", params.topP)
            if (params.maxTokens != null) put("maxOutputTokens", params.maxTokens)
            if (params.model.outputModalities.contains(Modality.IMAGE)) {
                put("responseModalities", buildJsonArray {
                    add(JsonPrimitive("TEXT"))
                    add(JsonPrimitive("IMAGE"))
                })
            }
            if (params.model.abilities.contains(ModelAbility.REASONING)) {
                put("thinkingConfig", buildJsonObject {
                    put("includeThoughts", true)

                    val isGeminiPro =
                        params.model.modelId.contains(Regex("2\\.5.*pro", RegexOption.IGNORE_CASE))

                    when (params.reasoningLevel) {
                        ReasoningLevel.AUTO -> {} // 自动模式，不设置参数

                        ReasoningLevel.OFF -> {
                            if (ModelRegistry.GEMINI_3_SERIES.match(modelId = params.model.modelId)) {
                                put("thinkingLevel", "minimal")
                            } else if (!isGeminiPro) {
                                put("thinkingBudget", 0)
                                put("includeThoughts", false)
                            }
                        }

                        else -> {
                            if (ModelRegistry.GEMINI_3_SERIES.match(modelId = params.model.modelId)) {
                                when (params.reasoningLevel) {
                                    ReasoningLevel.LOW -> put("thinkingLevel", "low")
                                    ReasoningLevel.MEDIUM -> put("thinkingLevel", "medium")
                                    else -> put("thinkingLevel", "high") // HIGH, XHIGH, MAX
                                }
                            } else {
                                val maximumBudget = if (isGeminiPro) 32_768 else 24_576
                                params.reasoningLevel.cappedBudget(maximumBudget)?.let {
                                    put("thinkingBudget", it)
                                }
                            }
                        }
                    }
                })
            }
        })

        // Contents (user messages)
        put(
            "contents",
            buildContents(messages, uploadedFiles)
        )

        // Tools
        if (params.tools.isNotEmpty() && params.model.abilities.contains(ModelAbility.TOOL)) {
            put("tools", buildJsonArray {
                add(buildJsonObject {
                    put("functionDeclarations", buildJsonArray {
                        params.tools.forEach { tool ->
                            add(buildJsonObject {
                                put("name", JsonPrimitive(tool.name))
                                put("description", JsonPrimitive(tool.description))
                                put(
                                    key = "parameters",
                                    element = json.encodeToJsonElement(tool.parameters())
                                        .removeElements(
                                            listOf(
                                                "const",
                                                "exclusiveMaximum",
                                                "exclusiveMinimum",
                                                "format",
                                                "additionalProperties",
                                                "enum",
                                            )
                                        )
                                )
                            })
                        }
                    })
                })
            })
        }
        // Model BuiltIn Tools
        // 目前不能和工具调用兼容
        if (params.model.tools.isNotEmpty()) {
            put("tools", buildJsonArray {
                params.model.tools.forEach { builtInTool ->
                    when (builtInTool) {
                        BuiltInTools.Search -> {
                            add(buildJsonObject {
                                put("googleSearch", buildJsonObject {})
                            })
                        }

                        BuiltInTools.UrlContext -> {
                            add(buildJsonObject {
                                put("urlContext", buildJsonObject {})
                            })
                        }

                        else -> {}
                    }
                }
            })
        }

        // Safety Settings
        putJsonArray("safetySettings") {
            add(buildJsonObject {
                put("category", "HARM_CATEGORY_HARASSMENT")
                put("threshold", "OFF")
            })
            add(buildJsonObject {
                put("category", "HARM_CATEGORY_HATE_SPEECH")
                put("threshold", "OFF")
            })
            add(buildJsonObject {
                put("category", "HARM_CATEGORY_SEXUALLY_EXPLICIT")
                put("threshold", "OFF")
            })
            add(buildJsonObject {
                put("category", "HARM_CATEGORY_DANGEROUS_CONTENT")
                put("threshold", "OFF")
            })
            add(buildJsonObject {
                put("category", "HARM_CATEGORY_CIVIC_INTEGRITY")
                put("threshold", "OFF")
            })
        }
    }.mergeCustomBody(params.customBody)

    private fun commonRoleToGoogleRole(role: MessageRole): String {
        return when (role) {
            MessageRole.USER -> "user"
            MessageRole.SYSTEM -> "system"
            MessageRole.ASSISTANT -> "model"
            MessageRole.TOOL -> "user" // google api中, tool结果是用户role发送的
        }
    }

    private suspend fun uploadLargeMedia(
        providerSetting: ProviderSetting.Google,
        messages: List<UIMessage>,
    ): Map<String, UploadedGoogleFile> {
        if (!providerSetting.supportsFilesApi()) return emptyMap()
        val candidates = messages.asSequence()
            .flatMap { it.parts.asSequence() }
            .mapNotNull { part ->
                val url = when (part) {
                    is UIMessagePart.Image -> part.url
                    is UIMessagePart.Video -> part.url
                    is UIMessagePart.Audio -> part.url
                    else -> return@mapNotNull null
                }
                val file = localMediaFile(url) ?: return@mapNotNull null
                if (file.length() < LARGE_MEDIA_UPLOAD_BYTES) return@mapNotNull null
                val mimeType = mediaMimeType(part, file) ?: return@mapNotNull null
                Triple(url, file, mimeType)
            }
            .distinctBy { it.first }
            .toList()

        if (candidates.isEmpty()) return emptyMap()
        val uploadSemaphore = Semaphore(MEDIA_UPLOAD_CONCURRENCY)
        return coroutineScope {
            candidates.map { (url, file, mimeType) ->
                async(Dispatchers.IO) {
                    uploadSemaphore.withPermit {
                        val cacheKey = "${providerSetting.baseUrl}|${file.absolutePath}|${file.length()}|${file.lastModified()}"
                        val cached = uploadedFileCache[cacheKey]?.takeIf {
                            System.currentTimeMillis() - it.cachedAtMillis < MEDIA_CACHE_TTL_MILLIS
                        }
                        val uploaded = cached ?: runCatching {
                            uploadFile(providerSetting, file, mimeType)
                        }.onFailure {
                            Log.w(TAG, "Files API upload failed for ${file.name}; using inline media", it)
                        }.getOrNull()?.also { uploadedFileCache[cacheKey] = it }
                        url to uploaded
                    }
                }
            }.awaitAll().mapNotNull { (url, uploaded) -> uploaded?.let { url to it } }.toMap()
        }
    }

    private suspend fun uploadFile(
        providerSetting: ProviderSetting.Google,
        file: File,
        mimeType: String,
    ): UploadedGoogleFile {
        var lastFailure: Throwable? = null
        repeat(MEDIA_UPLOAD_ATTEMPTS) { attempt ->
            try {
                return uploadFileOnce(providerSetting, file, mimeType)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                lastFailure = error
                if (attempt + 1 < MEDIA_UPLOAD_ATTEMPTS && error.isRetryableMediaUploadFailure()) {
                    delay(MEDIA_UPLOAD_RETRY_DELAY_MILLIS * (attempt + 1))
                } else {
                    throw error
                }
            }
        }
        throw lastFailure ?: IllegalStateException("Google media upload failed")
    }

    private suspend fun uploadFileOnce(
        providerSetting: ProviderSetting.Google,
        file: File,
        mimeType: String,
    ): UploadedGoogleFile {
        val startRequest = transformRequest(
            providerSetting,
            Request.Builder()
                .url(providerSetting.filesUploadUrl())
                .header("X-Goog-Upload-Protocol", "resumable")
                .header("X-Goog-Upload-Command", "start")
                .header("X-Goog-Upload-Header-Content-Length", file.length().toString())
                .header("X-Goog-Upload-Header-Content-Type", mimeType)
                .post(
                    buildJsonObject {
                        put("file", buildJsonObject { put("display_name", file.name) })
                    }.toString().toRequestBody("application/json".toMediaType())
                )
                .build(),
        )
        val uploadUrl = client.newCall(startRequest).await().use { response ->
            require(response.isSuccessful) { "Files API start failed: ${response.code}" }
            response.header("X-Goog-Upload-URL") ?: error("Files API did not return an upload URL")
        }

        val finishRequest = transformRequest(
            providerSetting,
            Request.Builder()
                .url(uploadUrl)
                .header("X-Goog-Upload-Offset", "0")
                .header("X-Goog-Upload-Command", "upload, finalize")
                .post(file.asRequestBody(mimeType.toMediaType()))
                .build(),
        )
        val uploaded = client.newCall(finishRequest).await().use { response ->
            require(response.isSuccessful) { "Files API upload failed: ${response.code}" }
            parseUploadedFile(response.body?.string().orEmpty(), mimeType)
        }
        return awaitActiveFile(providerSetting, uploaded)
    }

    private fun Throwable.isRetryableMediaUploadFailure(): Boolean {
        if (this is IOException) return true
        val message = message.orEmpty()
        return Regex("\\b(408|429|5\\d{2})\\b").containsMatchIn(message)
    }

    private suspend fun awaitActiveFile(
        providerSetting: ProviderSetting.Google,
        uploaded: UploadedGoogleFile,
    ): UploadedGoogleFile {
        var waitMillis = MEDIA_ACTIVE_POLL_INITIAL_MILLIS
        repeat(90) {
            val state = client.newCall(
                transformRequest(
                    providerSetting,
                    Request.Builder().url(buildUrl(providerSetting, uploaded.name)).get().build(),
                )
            ).await().use { response ->
                require(response.isSuccessful) { "Files API status failed: ${response.code}" }
                parseUploadedFile(response.body?.string().orEmpty(), uploaded.mimeType)
            }
            when (state.state.uppercase()) {
                "ACTIVE" -> return state.copy(state = "ACTIVE")
                "FAILED" -> error("Google rejected uploaded media")
            }
            delay(waitMillis)
            waitMillis = (waitMillis * 2).coerceAtMost(MEDIA_ACTIVE_POLL_MAX_MILLIS)
        }
        error("Timed out waiting for Google media processing")
    }

    private fun parseUploadedFile(body: String, fallbackMimeType: String): UploadedGoogleFile {
        val root = json.parseToJsonElement(body).jsonObject
        val file = (root["file"] ?: root).jsonObject
        val uri = file["uri"]?.jsonPrimitive?.contentOrNull ?: error("Google file URI missing")
        val name = file["name"]?.jsonPrimitive?.contentOrNull
            ?: uri.substringAfterLast("/v1beta/").substringAfterLast("/v1/")
        require(name.isNotBlank()) { "Google file name missing" }
        return UploadedGoogleFile(
            uri = uri,
            name = name,
            mimeType = file["mimeType"]?.jsonPrimitive?.contentOrNull ?: fallbackMimeType,
            state = file["state"]?.jsonPrimitive?.contentOrNull ?: "ACTIVE",
        )
    }

    private fun ProviderSetting.Google.supportsFilesApi(): Boolean =
        !vertexAI && runCatching { baseUrl.toHttpUrl().host == "generativelanguage.googleapis.com" }.getOrDefault(false)

    private fun ProviderSetting.Google.filesUploadUrl(): HttpUrl {
        val base = baseUrl.toHttpUrl()
        val version = base.pathSegments.lastOrNull()?.takeIf { it.startsWith("v1") } ?: "v1beta"
        return base.newBuilder().encodedPath("/upload/$version/files").query(null).build()
    }

    private fun localMediaFile(url: String): File? = runCatching {
        when {
            url.startsWith("file://") -> File(url.toUri().path ?: return@runCatching null)
            File(url).isAbsolute -> File(url)
            else -> null
        }
    }.getOrNull()?.takeIf { it.isFile }

    private fun mediaMimeType(part: UIMessagePart, file: File): String? {
        val extension = file.extension.lowercase()
        return when (part) {
            is UIMessagePart.Image -> when (extension) {
                "png" -> "image/png"
                "gif" -> "image/gif"
                "webp" -> "image/webp"
                "heic", "heif" -> "image/heif"
                "avif" -> "image/avif"
                else -> "image/jpeg"
            }
            is UIMessagePart.Video -> when (extension) {
                "webm" -> "video/webm"
                "3gp", "3gpp" -> "video/3gpp"
                "mov" -> "video/quicktime"
                "mkv" -> "video/x-matroska"
                else -> "video/mp4"
            }
            is UIMessagePart.Audio -> when (extension) {
                "mp3", "mp2", "mpga" -> "audio/mpeg"
                "wav", "wave" -> "audio/wav"
                "ogg", "oga", "opus" -> "audio/ogg"
                "flac" -> "audio/flac"
                "aac" -> "audio/aac"
                "mid", "midi" -> "audio/midi"
                else -> null
            }
            else -> null
        }
    }

    private fun googleRoleToCommonRole(role: String): MessageRole {
        return when (role) {
            "user" -> MessageRole.USER
            "system" -> MessageRole.SYSTEM
            "model" -> MessageRole.ASSISTANT
            else -> error("Unknown role $role")
        }
    }

    private fun parseMessage(message: JsonObject): UIMessage {
        val role = googleRoleToCommonRole(
            message["role"]?.jsonPrimitive?.contentOrNull ?: "model"
        )
        val content = message["content"]?.jsonObject ?: error("No content")
        val parts = content["parts"]?.jsonArray?.map { part ->
            parseMessagePart(part.jsonObject)
        } ?: emptyList()

        val groundingMetadata = message["groundingMetadata"]?.jsonObject
        Log.i(TAG, "parseMessage: $groundingMetadata")
        val annotations = parseSearchGroundingMetadata(groundingMetadata)

        return UIMessage(
            role = role,
            parts = parts,
            annotations = annotations
        )
    }

    private fun parseSearchGroundingMetadata(jsonObject: JsonObject?): List<UIMessageAnnotation> {
        if (jsonObject == null) return emptyList()
        val groundingChunks = jsonObject["groundingChunks"]?.jsonArray ?: emptyList()
        val chunks = groundingChunks.mapNotNull { chunk ->
            val web = chunk.jsonObject["web"]?.jsonObject ?: return@mapNotNull null
            val uri = web["uri"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val title = web["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            UIMessageAnnotation.UrlCitation(
                title = title,
                url = uri
            )
        }
        Log.i(TAG, "parseSearchGroundingMetadata: $chunks")
        return chunks
    }

    private fun parseMessagePart(jsonObject: JsonObject): UIMessagePart {
        return when {
            jsonObject.containsKey("text") -> {
                val thought = jsonObject["thought"]?.jsonPrimitive?.booleanOrNull ?: false
                val text = jsonObject["text"]?.jsonPrimitive?.content ?: ""
                if (thought) UIMessagePart.Reasoning(
                    reasoning = text,
                    createdAt = Clock.System.now(),
                    finishedAt = null
                ) else UIMessagePart.Text(text)
            }

            jsonObject.containsKey("functionCall") -> {
                UIMessagePart.Tool(
                    toolCallId = Uuid.random().toString(),
                    toolName = jsonObject["functionCall"]!!.jsonObject["name"]!!.jsonPrimitive.content,
                    input = json.encodeToString(jsonObject["functionCall"]!!.jsonObject["args"]),
                    output = emptyList(),
                    metadata = GoogleThoughtMetadata(
                        thoughtSignature = jsonObject["thoughtSignature"]?.jsonPrimitive?.contentOrNull
                    ).toMetadata()
                )
            }

            jsonObject.containsKey("inlineData") -> {
                val inlineData = jsonObject["inlineData"]!!.jsonObject
                val mime = inlineData["mimeType"]?.jsonPrimitive?.content ?: "image/png"
                val data = inlineData["data"]?.jsonPrimitive?.content ?: ""
                val thought = jsonObject["thought"]?.jsonPrimitive?.booleanOrNull ?: false
                val thoughtSignature = jsonObject["thoughtSignature"]?.jsonPrimitive?.contentOrNull
                require(mime.startsWith("image/")) {
                    "Only image mime type is supported"
                }
                // 如果是思考过程中的草稿图，直接忽略
                if (thought) {
                    return UIMessagePart.Reasoning(
                        reasoning = "[Draft Image]\n",
                        createdAt = Clock.System.now(),
                        finishedAt = null
                    )
                }
                UIMessagePart.Image(
                    url = "data:$mime;base64,$data",
                    metadata = GoogleThoughtMetadata(thoughtSignature = thoughtSignature).toMetadata()
                )
            }

            else -> error("unknown message part type: $jsonObject")
        }
    }

    private fun buildContents(messages: List<UIMessage>): JsonArray = buildContents(messages, emptyMap())

    private fun buildContents(
        messages: List<UIMessage>,
        uploadedFiles: Map<String, UploadedGoogleFile>,
    ): JsonArray {
        return buildJsonArray {
            messages
                .filter { it.role != MessageRole.SYSTEM && it.isValidToUpload() }
                .forEach { message ->
                    if (message.role == MessageRole.ASSISTANT) {
                        addModelMessage(message, uploadedFiles)
                    } else {
                        addUserMessage(message, uploadedFiles)
                    }
                }
        }
    }

    private fun JsonArrayBuilder.addModelMessage(
        message: UIMessage,
        uploadedFiles: Map<String, UploadedGoogleFile> = emptyMap(),
    ) {
        val groups = groupPartsByToolBoundary(message.parts)
        val partsBuffer = mutableListOf<JsonObject>()

        for (group in groups) {
            when (group) {
                is PartGroup.Content -> {
                    group.parts.mapNotNull { it.toGooglePart(uploadedFiles) }.forEach { partsBuffer.add(it) }
                }

                is PartGroup.Tools -> {
                    // 添加 functionCall 到 parts 缓冲
                    group.tools.forEach { partsBuffer.add(it.toFunctionCallPart()) }

                    // 输出 model 消息
                    add(buildJsonObject {
                        put("role", "model")
                        putJsonArray("parts") { partsBuffer.forEach { add(it) } }
                    })
                    partsBuffer.clear()

                    // 紧跟 functionResponse
                    add(buildJsonObject {
                        put("role", "user")
                        putJsonArray("parts") {
                            group.tools.forEach { add(it.toFunctionResponsePart()) }
                        }
                    })
                }
            }
        }

        // 输出剩余内容
        if (partsBuffer.isNotEmpty()) {
            add(buildJsonObject {
                put("role", "model")
                putJsonArray("parts") { partsBuffer.forEach { add(it) } }
            })
        }
    }

    private fun JsonArrayBuilder.addUserMessage(
        message: UIMessage,
        uploadedFiles: Map<String, UploadedGoogleFile> = emptyMap(),
    ) {
        add(buildJsonObject {
            put("role", commonRoleToGoogleRole(message.role))
            putJsonArray("parts") {
                message.parts.mapNotNull { it.toGooglePart(uploadedFiles) }.forEach { add(it) }
            }
        })
    }

    private fun UIMessagePart.toGooglePart(
        uploadedFiles: Map<String, UploadedGoogleFile> = emptyMap(),
    ): JsonObject? = when (this) {
        is UIMessagePart.Text -> buildJsonObject {
            put("text", text)
        }

        is UIMessagePart.Image -> {
            uploadedFiles[url]?.let { uploaded ->
                buildJsonObject {
                    put("fileData", buildJsonObject {
                        put("mimeType", uploaded.mimeType)
                        put("fileUri", uploaded.uri)
                    })
                }
            } ?: encodeBase64(false).getOrNull()?.let { encoded ->
                buildJsonObject {
                    put("inlineData", buildJsonObject {
                        put("mimeType", encoded.mimeType)
                        put("data", encoded.base64)
                    })
                    metadataAs<GoogleThoughtMetadata>()?.thoughtSignature?.let {
                        put("thoughtSignature", it)
                    }
                }
            }
        }

        is UIMessagePart.Video -> {
            uploadedFiles[url]?.let { uploaded ->
                buildJsonObject {
                    put("fileData", buildJsonObject {
                        put("mimeType", uploaded.mimeType)
                        put("fileUri", uploaded.uri)
                    })
                }
            } ?: encodeBase64(false).getOrNull()?.let { encoded ->
                buildJsonObject {
                    put("inlineData", buildJsonObject {
                        put("mimeType", encoded.mimeType)
                        put("data", encoded.base64)
                    })
                }
            }
        }

        is UIMessagePart.Audio -> {
            uploadedFiles[url]?.let { uploaded ->
                buildJsonObject {
                    put("fileData", buildJsonObject {
                        put("mimeType", uploaded.mimeType)
                        put("fileUri", uploaded.uri)
                    })
                }
            } ?: encodeBase64(false).getOrNull()?.let { encoded ->
                buildJsonObject {
                    put("inlineData", buildJsonObject {
                        put("mimeType", encoded.mimeType)
                        put("data", encoded.base64)
                    })
                }
            }
        }

        else -> null
    }

    private fun UIMessagePart.Tool.toFunctionCallPart() = buildJsonObject {
        put("functionCall", buildJsonObject {
            put("name", toolName)
            put("args", inputAsJson())
        })
        metadataAs<GoogleThoughtMetadata>()?.thoughtSignature?.let {
            put("thoughtSignature", it)
        }
    }

    private fun UIMessagePart.Tool.toFunctionResponsePart() = buildJsonObject {
            put("functionResponse", buildJsonObject {
                put("name", toolName)

                // 1. 拆分出纯文本部分
                val textParts = output.filterIsInstance<UIMessagePart.Text>()
                
                // 2. 提取所有的多模态(图片/视频/音频)，并直接转为 Google 要求的格式
                // 过滤出最终包含 inlineData 的数据块
                val mediaGoogleParts = output
                    .filter { it !is UIMessagePart.Text }
                    .mapNotNull { it.toGooglePart() }
                    .filter { it.containsKey("inlineData") } 

                // 3. 构建给模型看的结构化 response 节点
                put("response", buildJsonObject {
                    // 处理文本结果
                    if (textParts.isNotEmpty()) {
                        put(
                            "result", 
                            textParts.joinToString("\n") { it.text }
                        )
                    } else if (mediaGoogleParts.isEmpty()) {
                        // 如果工具啥都没返回，给个兜底成功状态
                        put("result", " ")
                    }

                    // 处理媒体数据（图片、音频、视频），打上 $ref 标签
                    mediaGoogleParts.forEachIndexed { index, _ ->
                        val refName = "media_ref_$index"
                        put(refName, buildJsonObject {
                            put("\$ref", refName)
                        })
                    }
                })

                // 4. 将真实的 Base64 多媒体数据挂载到 parts 中，并建立指针绑定
                if (mediaGoogleParts.isNotEmpty()) {
                    putJsonArray("parts") {
                        mediaGoogleParts.forEachIndexed { index, googlePart ->
                            val refName = "media_ref_$index"
                            val inlineData = googlePart["inlineData"]!!.jsonObject

                            add(buildJsonObject {
                                // 重新组装 inlineData，并在内部注入 displayName
                                put("inlineData", buildJsonObject {
                                    // 复制原有的 mimeType 和 data
                                    inlineData.forEach { (k, v) -> put(k, v) }
                                    // 添加能够让 $ref 认出它的唯一名称
                                    put("displayName", refName)
                                })
                                
                                // 保留可能存在的其他字段
                                googlePart.forEach { (k, v) ->
                                    if (k != "inlineData") put(k, v)
                                }
                            })
                        }
                    }
                }
            })
        }

    private fun parseUsageMeta(jsonObject: JsonObject?): TokenUsage? {
        if (jsonObject == null) {
            return null
        }
        val promptTokens = jsonObject["promptTokenCount"]?.jsonPrimitiveOrNull?.intOrNull ?: 0
        val thoughtTokens = jsonObject["thoughtsTokenCount"]?.jsonPrimitiveOrNull?.intOrNull ?: 0
        val cachedTokens = jsonObject["cachedContentTokenCount"]?.jsonPrimitiveOrNull?.intOrNull ?: 0
        val candidatesTokens = jsonObject["candidatesTokenCount"]?.jsonPrimitiveOrNull?.intOrNull ?: 0
        val totalTokens = jsonObject["totalTokenCount"]?.jsonPrimitiveOrNull?.intOrNull ?: 0
        return TokenUsage(
            promptTokens = promptTokens,
            completionTokens = candidatesTokens + thoughtTokens,
            totalTokens = totalTokens,
            cachedTokens = cachedTokens
        )
    }
}

internal fun buildGoogleImageRequestBody(
    prompt: String,
    size: String,
    resolution: String?,
    customBody: List<CustomBody>,
    constraints: ImageGenerationConstraints,
    imageParts: List<JsonObject>,
): JsonObject {
    val aspectRatio = size.takeIf {
        it.isNotBlank() && it != "auto" && constraints.supportedSizes?.contains(it) == true
    }
    val imageSize = resolution?.takeIf(constraints.supportedResolutionValues::contains)

    return buildJsonObject {
        putJsonArray("contents") {
            add(buildJsonObject {
                put("role", "user")
                putJsonArray("parts") {
                    add(buildJsonObject { put("text", prompt) })
                    imageParts.forEach { add(it) }
                }
            })
        }
        put("generationConfig", buildJsonObject {
            putJsonArray("responseModalities") {
                add(JsonPrimitive("TEXT"))
                add(JsonPrimitive("IMAGE"))
            }
            if (aspectRatio != null || imageSize != null) {
                put("imageConfig", buildJsonObject {
                    aspectRatio?.let { put("aspectRatio", it) }
                    imageSize?.let { put("imageSize", it) }
                })
            }
        })
    }.mergeCustomBody(customBody)
}

internal fun parseGoogleGeneratedImages(response: JsonObject): List<ImageGenerationItem> =
    (response["candidates"] as? JsonArray).orEmpty().flatMap { candidateElement ->
        val candidate = candidateElement as? JsonObject ?: return@flatMap emptyList()
        val content = candidate["content"] as? JsonObject ?: return@flatMap emptyList()
        (content["parts"] as? JsonArray).orEmpty().mapNotNull { partElement ->
            val part = partElement as? JsonObject ?: return@mapNotNull null
            if ((part["thought"] as? JsonPrimitive)?.booleanOrNull == true) return@mapNotNull null
            val inlineData = (part["inlineData"] ?: part["inline_data"]) as? JsonObject
                ?: return@mapNotNull null
            val data = inlineData["data"]?.jsonPrimitive?.contentOrNull
                ?.takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            val mimeType = (inlineData["mimeType"] ?: inlineData["mime_type"])
                ?.jsonPrimitive
                ?.contentOrNull
                ?.takeIf(String::isNotBlank)
                ?: "image/png"
            ImageGenerationItem(data = data, mimeType = mimeType)
        }
    }

internal fun inferGoogleModelType(
    modelId: String,
    supportedGenerationMethods: List<String>,
): ModelType? = when (inferModelTypeFromId(modelId)) {
    ModelType.IMAGE -> ModelType.IMAGE
    ModelType.EMBEDDING -> ModelType.EMBEDDING
    ModelType.CHAT -> when {
        "embedContent" in supportedGenerationMethods && "generateContent" !in supportedGenerationMethods ->
            ModelType.EMBEDDING
        "generateContent" in supportedGenerationMethods -> ModelType.CHAT
        else -> null
    }
}
