package me.rerere.ai.provider.providers.openai

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.rerere.ai.provider.EmbeddingGenerationParams
import me.rerere.ai.provider.EmbeddingGenerationResult
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.ImageEditParams
import me.rerere.ai.provider.ImageGenerationConstraints
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelDiscoveryProtocol
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderCapability
import me.rerere.ai.provider.ProviderRequestDiagnostics
import me.rerere.ai.provider.ProviderRequestOperation
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.VideoGenerationConstraints
import me.rerere.ai.provider.VideoGenerationParams
import me.rerere.ai.provider.VideoGenerationTaskSnapshot
import me.rerere.ai.provider.VideoReferenceImage
import me.rerere.ai.provider.constrained
import me.rerere.ai.provider.contextWindowTokensOrNull
import me.rerere.ai.provider.inferModelTypeFromId
import me.rerere.ai.provider.providerRequestFailure
import me.rerere.ai.provider.usesVolcengineMultimodalEmbeddingApi
import me.rerere.ai.provider.supportsVolcengineMultimodalDimensions
import me.rerere.ai.provider.supportsVolcengineMultimodalBatchInput
import me.rerere.ai.provider.usesVolcengineTextEmbeddingApi
import me.rerere.ai.registry.ModelRegistry
import me.rerere.ai.ui.ImageGenSize
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.util.KeyRoulette
import me.rerere.ai.util.configureReferHeaders
import me.rerere.ai.util.json
import me.rerere.ai.util.mergeCustomBody
import me.rerere.ai.util.toHeaders
import me.rerere.common.http.await
import me.rerere.common.http.awaitAndUse
import me.rerere.common.http.getByKey
import okhttp3.MultipartBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.util.Base64
import java.net.URLConnection

private const val TAG = "OpenAIProvider"

class OpenAIProvider(
    private val client: OkHttpClient,
    private val context: Context? = null
) : Provider<ProviderSetting.OpenAI> {
    override val capabilities: Set<ProviderCapability> = setOf(
        ProviderCapability.BALANCE,
        ProviderCapability.IMAGE_GENERATION,
        ProviderCapability.IMAGE_EDIT,
    )

    private val keyRoulette = if (context != null) KeyRoulette.lru(context) else KeyRoulette.default()

    private val chatCompletionsAPI = ChatCompletionsAPI(client = client, keyRoulette = keyRoulette)
    private val responseAPI = ResponseAPI(client = client, keyRoulette = keyRoulette)


    override suspend fun listModels(providerSetting: ProviderSetting.OpenAI): List<Model> =
        withContext(Dispatchers.IO) {
            if (providerSetting.additionalVideoRoute(Model(modelId = "kling-v1-6", type = ModelType.VIDEO)) == VideoRoute.KLING) {
                return@withContext listOf("kling-v1", "kling-v1-6", "kling-v2-master", "kling-v2-1", "kling-v2-5-turbo", "kling-v2-6").map {
                    Model(modelId = it, displayName = it, type = ModelType.VIDEO,
                        inputModalities = listOf(Modality.TEXT, Modality.IMAGE), outputModalities = listOf(Modality.VIDEO))
                }
            }
            val key = keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())
            val request = Request.Builder()
                .url("${providerSetting.baseUrl}/models")
                .addHeader("Authorization", "Bearer $key")
                .get()
                .build()

            val response = client.newCall(request).await()
            if (!response.isSuccessful) {
                error("Failed to get models: ${response.code} ${response.body?.string()}")
            }

            val bodyStr = response.body?.string() ?: ""
            val bodyJson = json.parseToJsonElement(bodyStr).jsonObject
            val data = bodyJson["data"]?.jsonArray ?: return@withContext emptyList()

            data.mapNotNull { modelJson ->
                val modelObj = modelJson.jsonObject
                val id = modelObj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null

                Model(
                    modelId = id,
                    displayName = id,
                    type = inferOpenAIModelType(id),
                    inputModalities = ModelRegistry.MODEL_INPUT_MODALITIES.getData(id),
                    outputModalities = ModelRegistry.MODEL_OUTPUT_MODALITIES.getData(id),
                    abilities = ModelRegistry.MODEL_ABILITIES.getData(id),
                    contextWindowTokens = modelObj.contextWindowTokensOrNull(
                        modelId = id,
                        protocol = ModelDiscoveryProtocol.OPENAI,
                    ),
                )
            }
        }

    override suspend fun getBalance(providerSetting: ProviderSetting.OpenAI): String = withContext(Dispatchers.IO) {
        val key = keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())
        val url = if (providerSetting.balanceOption.apiPath.startsWith("http")) {
            providerSetting.balanceOption.apiPath
        } else {
            "${providerSetting.baseUrl}${providerSetting.balanceOption.apiPath}"
        }
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $key")
            .get()
            .build()
        val response = client.newCall(request).await()
        if (!response.isSuccessful) {
            error("Failed to get balance: ${response.code} ${response.body?.string()}")
        }

        val bodyStr = response.body.string()
        val bodyJson = json.parseToJsonElement(bodyStr).jsonObject
        val value = bodyJson.getByKey(providerSetting.balanceOption.resultPath)
        val digitalValue = value.toFloatOrNull()
        if(digitalValue != null) {
            "%.2f".format(digitalValue)
        } else {
            value
        }
    }

    override suspend fun streamText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams
    ): Flow<StreamChunk> = if (providerSetting.useResponseApi) {
        responseAPI.streamText(
            providerSetting = providerSetting,
            messages = messages,
            params = params
        )
    } else {
        chatCompletionsAPI.streamText(
            providerSetting = providerSetting,
            messages = messages,
            params = params
        )
    }

    override suspend fun generateText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams
    ): TextGenerationResult = if (providerSetting.useResponseApi) {
        responseAPI.generateText(
            providerSetting = providerSetting,
            messages = messages,
            params = params
        )
    } else {
        chatCompletionsAPI.generateText(
            providerSetting = providerSetting,
            messages = messages,
            params = params
        )
    }

    override suspend fun generateEmbedding(
        providerSetting: ProviderSetting.OpenAI,
        params: EmbeddingGenerationParams
    ): EmbeddingGenerationResult = withContext(Dispatchers.IO) {
        require(params.input.isNotEmpty() || params.images.isNotEmpty()) {
            "Embedding input cannot be empty"
        }
        require(params.input.none(String::isBlank)) {
            "Embedding input cannot contain empty strings"
        }
        val configuredModelId = params.model.modelId.trim()
        val modelId = configuredModelId.substringAfterLast('/').trim()
        val isVolcengineArk = providerSetting.baseUrl.isVolcengineArkBaseUrl()
        val modelSupportsImages = params.model.usesVolcengineMultimodalEmbeddingApi() ||
            Modality.IMAGE in params.model.inputModalities
        val usesMultimodalEndpoint = params.model.usesVolcengineMultimodalEmbeddingApi() ||
            (isVolcengineArk && params.model.type == ModelType.EMBEDDING &&
                (modelSupportsImages || params.images.isNotEmpty()))
        val usesVolcengineTextEndpoint = params.model.usesVolcengineTextEmbeddingApi() ||
            (isVolcengineArk && params.model.type == ModelType.EMBEDDING && !usesMultimodalEndpoint)
        require(params.images.isEmpty() || usesMultimodalEndpoint) {
            "The selected embedding model does not support image inputs"
        }
        if (usesMultimodalEndpoint) {
            if (!params.model.supportsVolcengineMultimodalBatchInput() &&
                modelId.startsWith("doubao-embedding-vision", ignoreCase = true)
            ) {
                require(params.input.size + params.images.size <= 2) {
                    "This Volcano Ark vision model revision accepts at most two input items"
                }
            }
        }
        if (isVolcengineArk || usesVolcengineTextEndpoint || usesMultimodalEndpoint) {
            require(params.input.all { it.toByteArray(Charsets.UTF_8).size <= VOLCENGINE_MAX_INPUT_BYTES }) {
                "Volcano Ark embedding input must be at most 100000 UTF-8 bytes per text"
            }
        }

        val key = keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())
        // Ark accepts a plain Model ID or Endpoint ID. Normalise a copied
        // `models/...` path while retaining slash-qualified IDs for other
        // OpenAI-compatible providers.
        val requestModelId = if (isVolcengineArk) modelId else configuredModelId
        val requestBody = json.encodeToString(
            buildJsonObject {
                put("model", requestModelId)
                // Ark documents this field explicitly. Keep it off for unrelated
                // OpenAI-compatible endpoints because some proxies reject unknown
                // optional fields; custom bodies may still provide their own value.
                if (usesMultimodalEndpoint) {
                    put("encoding_format", "float")
                }
                if (usesMultimodalEndpoint) {
                    putJsonArray("input") {
                        params.input.forEach { text ->
                            add(buildJsonObject {
                                put("type", "text")
                                put("text", text)
                            })
                        }
                        params.images.forEach { image ->
                            add(buildJsonObject {
                                put("type", "image_url")
                                put("image_url", buildJsonObject {
                                    put("url", "data:${image.mimeType};base64,${image.base64}")
                                })
                            })
                        }
                    }
                } else if (usesVolcengineTextEndpoint) {
                    // Ark's text embedding API documents String or Array input. Always
                    // use the array form so batches and single-item requests behave alike.
                    putJsonArray("input") {
                        params.input.forEach { add(JsonPrimitive(it)) }
                    }
                } else if (params.input.size == 1) {
                    put("input", params.input.first())
                } else {
                    putJsonArray("input") {
                        params.input.forEach { add(JsonPrimitive(it)) }
                    }
                }
                if (!usesMultimodalEndpoint && !usesVolcengineTextEndpoint) {
                    params.dimensions?.let { put("dimensions", it) }
                } else if (usesMultimodalEndpoint && params.model.supportsVolcengineMultimodalDimensions()) {
                    params.dimensions?.let { dimensions ->
                        require(dimensions == 1024 || dimensions == 2048) {
                            "Volcano Ark multimodal dimensions must be 1024 or 2048"
                        }
                        put("dimensions", dimensions)
                    }
                }
            }.mergeCustomBody(params.customBody)
        )

        val endpoint = if (usesMultimodalEndpoint) {
            "embeddings/multimodal"
        } else {
            "embeddings"
        }
        val request = Request.Builder()
            .url(
                "${providerSetting.baseUrl.trimEnd('/')}/$endpoint"
            )
            .headers(params.customHeaders.toHeaders())
            .addHeader("Authorization", "Bearer $key")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .tag(me.rerere.ai.provider.ProviderRequestDiagnostics::class.java, me.rerere.ai.provider.ProviderRequestDiagnostics(
                provider = providerSetting.name, model = configuredModelId, channel = providerSetting.requestChannel(),
                operation = me.rerere.ai.provider.ProviderRequestOperation.EMBEDDING,
                parameters = mapOf("input.count" to params.input.size.toString()), requestId = params.requestId))
            .build()

        client.newCall(request).await().use { response ->
            if (!response.isSuccessful) {
                error("Failed to generate embedding: ${response.code} ${response.body?.string()}")
            }

            val bodyStr = response.body?.string() ?: ""
            val bodyJson = json.parseToJsonElement(bodyStr).jsonObject
            val data = when (val dataElement = bodyJson["data"]) {
                is JsonArray -> dataElement
                is JsonObject -> JsonArray(listOf(dataElement))
                else -> error("No embedding data in response")
            }
            val responseModel = bodyJson["model"]?.jsonPrimitive?.contentOrNull ?: configuredModelId

            val embeddings = data.sortedBy { embeddingJson ->
                embeddingJson.jsonObject["index"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                    ?: Int.MAX_VALUE
            }.map { embeddingJson ->
                parseEmbeddingVector(
                    embeddingJson.jsonObject["embedding"]
                        ?: error("No embedding in response")
                )
            }
            val expectedCount = if (usesMultimodalEndpoint) 1 else params.input.size
            require(embeddings.size == expectedCount) {
                "Embedding provider returned ${embeddings.size} vectors for $expectedCount inputs"
            }
            require(embeddings.all { it.isNotEmpty() && it.all(Float::isFinite) }) {
                "Embedding provider returned an empty or invalid vector"
            }

            EmbeddingGenerationResult(
                model = responseModel,
                embeddings = embeddings
            )
        }
    }

    override fun videoGenerationConstraints(
        providerSetting: ProviderSetting,
        model: Model,
    ): VideoGenerationConstraints {
        val openAISetting = providerSetting as? ProviderSetting.OpenAI
            ?: return VideoGenerationConstraints(supportsGeneration = false)
        if (openAISetting.miniMaxVideoSetting(model) != null) return miniMaxVideoConstraints(model)
        openAISetting.additionalVideoRoute(model)?.let { return additionalVideoConstraints(it, model) }
        return if (openAISetting.supportsArkSeedance(model)) {
            seedanceVideoGenerationConstraints(model)
        } else {
            VideoGenerationConstraints(supportsGeneration = false)
        }
    }

    override suspend fun createVideoGenerationTask(
        providerSetting: ProviderSetting,
        params: VideoGenerationParams,
    ): VideoGenerationTaskSnapshot = withContext(Dispatchers.IO) {
        val setting = providerSetting as? ProviderSetting.OpenAI
            ?: error("Expected OpenAI provider setting")
        setting.miniMaxVideoSetting(params.model)?.let { miniMax ->
            return@withContext MiniMaxVideoAPI(client).create(miniMax, params.copy(
                referenceImages = params.referenceImages.map { it.materializeForArk() }))
        }
        setting.additionalVideoRoute(params.model)?.let { route ->
            val bounded = params.constrained(additionalVideoConstraints(route, params.model))
            return@withContext AdditionalVideoAPI(client).create(setting, route,
                bounded.copy(referenceImages = bounded.referenceImages.map { it.materializeForArk() }),
                keyRoulette.next(setting.apiKey, setting.id.toString()))
        }
        check(setting.supportsArkSeedance(params.model)) {
            "Seedance video generation requires a Volcengine Ark video model"
        }
        val materializedParams = params.copy(
            referenceImages = params.referenceImages.map { it.materializeForArk() },
        )
        val body = buildSeedanceVideoRequestBody(
            params = materializedParams,
            constraints = videoGenerationConstraints(setting, params.model),
        )
        val key = keyRoulette.next(setting.apiKey, setting.id.toString())
        val request = Request.Builder()
            .url(setting.arkVideoTaskUrl())
            .tag(
                ProviderRequestDiagnostics::class.java,
                body.seedanceVideoDiagnostics(
                    providerSetting = setting,
                    operation = ProviderRequestOperation.VIDEO_GENERATION_CREATE,
                ),
            )
            .headers(params.customHeaders.toHeaders())
            .header("Authorization", "Bearer $key")
            .header("Content-Type", "application/json")
            .post(json.encodeToString(body).toRequestBody("application/json".toMediaType()))
            .configureReferHeaders(setting.baseUrl)
            .build()

        client.newCall(request).awaitAndUse { response ->
            if (!response.isSuccessful) throw openAIVideoRequestFailure("create video task", response)
            ensureVideoTaskResponseSize(response.body.contentLength())
            parseSeedanceVideoTask(json.parseToJsonElement(response.body.string()).jsonObject)
        }
    }

    override suspend fun getVideoGenerationTask(
        providerSetting: ProviderSetting,
        model: Model,
        taskId: String,
    ): VideoGenerationTaskSnapshot = withContext(Dispatchers.IO) {
        val setting = providerSetting as? ProviderSetting.OpenAI
            ?: error("Expected OpenAI provider setting")
        setting.miniMaxVideoSetting(model)?.let { return@withContext MiniMaxVideoAPI(client).get(it, model, taskId) }
        setting.additionalVideoRoute(model)?.let { route ->
            return@withContext AdditionalVideoAPI(client).get(setting, route, model, taskId,
                keyRoulette.next(setting.apiKey, setting.id.toString()))
        }
        check(setting.supportsArkSeedance(model)) {
            "Seedance video generation requires a Volcengine Ark video model"
        }
        require(taskId.isNotBlank()) { "Video task id cannot be empty" }
        val key = keyRoulette.next(setting.apiKey, setting.id.toString())
        val diagnosticsBody = buildJsonObject { put("model", model.modelId) }
        val request = Request.Builder()
            .url(setting.arkVideoTaskUrl(taskId))
            .tag(
                ProviderRequestDiagnostics::class.java,
                diagnosticsBody.seedanceVideoDiagnostics(
                    providerSetting = setting,
                    operation = ProviderRequestOperation.VIDEO_GENERATION_STATUS,
                    taskId = taskId,
                ),
            )
            .headers(model.customHeaders.toHeaders())
            .header("Authorization", "Bearer $key")
            .get()
            .configureReferHeaders(setting.baseUrl)
            .build()

        client.newCall(request).awaitAndUse { response ->
            if (!response.isSuccessful) throw openAIVideoRequestFailure("get video task", response)
            ensureVideoTaskResponseSize(response.body.contentLength())
            parseSeedanceVideoTask(
                body = json.parseToJsonElement(response.body.string()).jsonObject,
                fallbackTaskId = taskId,
                retryAfterMillis = response.header("Retry-After")?.toLongOrNull()?.times(1_000L),
            )
        }
    }

    override suspend fun cancelVideoGenerationTask(
        providerSetting: ProviderSetting,
        model: Model,
        taskId: String,
    ): Boolean = withContext(Dispatchers.IO) {
        val setting = providerSetting as? ProviderSetting.OpenAI
            ?: error("Expected OpenAI provider setting")
        check(setting.supportsArkSeedance(model)) {
            "Seedance video generation requires a Volcengine Ark video model"
        }
        require(taskId.isNotBlank()) { "Video task id cannot be empty" }
        val key = keyRoulette.next(setting.apiKey, setting.id.toString())
        val diagnosticsBody = buildJsonObject { put("model", model.modelId) }
        val request = Request.Builder()
            .url(setting.arkVideoTaskUrl(taskId))
            .tag(
                ProviderRequestDiagnostics::class.java,
                diagnosticsBody.seedanceVideoDiagnostics(
                    providerSetting = setting,
                    operation = ProviderRequestOperation.VIDEO_GENERATION_CANCEL,
                    taskId = taskId,
                ),
            )
            .headers(model.customHeaders.toHeaders())
            .header("Authorization", "Bearer $key")
            .delete()
            .configureReferHeaders(setting.baseUrl)
            .build()

        client.newCall(request).awaitAndUse { response ->
            if (response.isSuccessful || response.code == 404) return@awaitAndUse true
            throw openAIVideoRequestFailure("cancel video task", response)
        }
    }

    override suspend fun generateImage(
        providerSetting: ProviderSetting,
        params: ImageGenerationParams
    ): Flow<ImageGenerationItem> = flow {
        require(providerSetting is ProviderSetting.OpenAI) {
            "Expected OpenAI provider setting"
        }
        cleanupStaleTemporaryImages()

        val constraints = imageGenerationConstraints(providerSetting, params.model)
        val outputCount = params.numOfImages.coerceIn(1, constraints.maxOutputImages)

        val key = keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())

        val requestBody = json.encodeToString(
            buildOpenAIImageGenerationRequestBody(params, constraints)
        )

        Log.d(TAG, "generateImage: model=${params.model.modelId}, count=$outputCount")

        val request = Request.Builder()
            .url("${providerSetting.baseUrl}/images/generations")
            .headers(params.customHeaders.toHeaders())
            .addHeader("Authorization", "Bearer $key")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .configureReferHeaders(providerSetting.baseUrl)
            .build()

        client.newCall(request).awaitAndUse { response ->
            if (!response.isSuccessful) {
                throw openAIImageRequestFailure("generate image", response)
            }
            ensureImageResponseSize(response.body.contentLength())
            val requestedFormat = params.requestedImageFileFormat(constraints)
            parseOpenAIImageResponse(
                reader = response.body.charStream(),
                defaultFormat = requestedFormat,
                maxChars = MAX_IMAGE_RESPONSE_CHARS,
                createBase64File = ::createTemporaryImageFileFromStream,
                resolveUrl = ::downloadImageToTemporaryFile,
                emitItem = { emit(it) },
            )
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun editImage(
        providerSetting: ProviderSetting,
        params: ImageEditParams
    ): Flow<ImageGenerationItem> = flow {
        require(providerSetting is ProviderSetting.OpenAI) {
            "Expected OpenAI provider setting"
        }
        require(params.images.isNotEmpty()) {
            "At least one image is required"
        }
        cleanupStaleTemporaryImages()

        val constraints = imageGenerationConstraints(providerSetting, params.model)
        val sourceImages = params.images.take(constraints.maxReferenceImages)
        val outputCount = params.numOfImages.coerceIn(1, constraints.maxOutputImages)

        val key = keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())
        val imageFiles = sourceImages.map { path ->
            File(path).also { imageFile ->
                require(imageFile.exists()) { "Image file does not exist: $path" }
                require(imageFile.extension.lowercase() in SUPPORTED_EDIT_IMAGE_EXTENSIONS) {
                    "Unsupported image file type for image edit: ${imageFile.extension}"
                }
            }
        }
        validateEditImageFiles(params.model.modelId, constraints, imageFiles)
        val requestBody = when {
            constraints.usesGenerationEndpointForEdit -> buildSeedreamImageEditRequestBody(
                params = params,
                constraints = constraints,
                images = imageFiles.map { imageFile -> imageFile to imageFile.imageMediaType() },
            )
            constraints.usesJsonImageEdit -> buildXaiImageEditRequestBody(
                params = params,
                constraints = constraints,
                images = imageFiles.map { imageFile -> imageFile to imageFile.imageMediaType() },
            )
            else -> {
                val bodyBuilder = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("model", params.model.modelId)
                    .addFormDataPart("prompt", params.prompt)
                if (constraints.supportsOutputCount) {
                    bodyBuilder.addFormDataPart("n", outputCount.toString())
                }
                constraints.normalizedSize(params.size)?.let { normalizedSize ->
                    bodyBuilder.addFormDataPart(constraints.sizeRequestField, normalizedSize)
                }
                val imageFieldName = if (imageFiles.size == 1) "image" else "image[]"
                imageFiles.forEach { imageFile ->
                    bodyBuilder.addFormDataPart(
                        imageFieldName,
                        imageFile.name,
                        imageFile.asRequestBody(imageFile.imageMediaType().toMediaType())
                    )
                }
                val explicitOptions = params.explicitImageOptions(constraints)
                val advancedOptions = params.advancedImageOptions(constraints)
                params.customBody
                    .filter { it.enabled && it.key.isNotBlank() }
                    .map { it.copy(key = it.key.trim()) }
                    .filter { customBody ->
                        val field = customBody.key.lowercase()
                        field !in RESERVED_IMAGE_EDIT_FIELDS &&
                            field !in explicitOptions &&
                            field !in advancedOptions &&
                            constraints.acceptsImageOption(customBody)
                    }
                    .forEach { customBody ->
                        val value = when (val element = customBody.value) {
                            is JsonPrimitive -> element.contentOrNull ?: element.toString()
                            else -> element.toString()
                        }
                        bodyBuilder.addFormDataPart(customBody.key, value)
                    }
                explicitOptions.forEach { (field, value) -> bodyBuilder.addFormDataPart(field, value) }
                advancedOptions.forEach { (field, value) ->
                    bodyBuilder.addFormDataPart(
                        field,
                        (value as? JsonPrimitive)?.contentOrNull ?: value.toString(),
                    )
                }
                bodyBuilder.build()
            }
        }

        val request = Request.Builder()
            .url(
                "${providerSetting.baseUrl}" + if (constraints.usesGenerationEndpointForEdit) {
                    "/images/generations"
                } else {
                    "/images/edits"
                }
            )
            .headers(params.customHeaders.toHeaders())
            .addHeader("Authorization", "Bearer $key")
            .post(requestBody)
            .configureReferHeaders(providerSetting.baseUrl)
            .build()

        client.newCall(request).awaitAndUse { response ->
            if (!response.isSuccessful) {
                throw openAIImageRequestFailure("edit image", response)
            }
            ensureImageResponseSize(response.body.contentLength())
            val requestedFormat = params.requestedImageFileFormat(constraints)
            parseOpenAIImageResponse(
                reader = response.body.charStream(),
                defaultFormat = requestedFormat,
                maxChars = MAX_IMAGE_RESPONSE_CHARS,
                createBase64File = ::createTemporaryImageFileFromStream,
                resolveUrl = ::downloadImageToTemporaryFile,
                emitItem = { emit(it) },
            )
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun downloadImageToTemporaryFile(url: String): ImageGenerationItem {
        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        return client.newCall(request).awaitAndUse { response ->
            if (!response.isSuccessful) {
                throw openAIImageRequestFailure("download generated image", response)
            }
            ensureGeneratedImageSize(response.body.contentLength())
            val mimeType = response.body.contentType()?.toString()?.substringBefore(';') ?: "image/png"
            val temporaryFile = createTemporaryImageFile()
            try {
                response.body.byteStream().use { input ->
                    temporaryFile.outputStream().use { output ->
                        input.copyToWithLimit(output::write)
                    }
                }
                ImageGenerationItem(
                    mimeType = mimeType,
                    temporaryFilePath = temporaryFile.absolutePath,
                )
            } catch (e: Throwable) {
                temporaryFile.delete()
                throw e
            }
        }
    }

    private suspend fun createTemporaryImageFileFromStream(input: InputStream): File {
        val temporaryFile = createTemporaryImageFile()
        try {
            temporaryFile.outputStream().use { output ->
                input.copyToWithLimit(output::write)
            }
            return temporaryFile
        } catch (e: Throwable) {
            temporaryFile.delete()
            throw e
        }
    }

    private fun createTemporaryImageFile(): File {
        val directory = temporaryImageDirectory()
        return File.createTempFile("rikkahub-image-", ".image", directory)
    }

    private fun temporaryImageDirectory(): File? =
        context?.cacheDir?.resolve(IMAGE_GENERATION_TEMP_DIRECTORY)?.apply { mkdirs() }

    private fun cleanupStaleTemporaryImages(nowMillis: Long = System.currentTimeMillis()) {
        temporaryImageDirectory()?.listFiles()?.forEach { file ->
            if (file.isFile && nowMillis - file.lastModified() >= TEMPORARY_IMAGE_MAX_AGE_MILLIS) {
                file.delete()
            }
        }
    }

    private suspend fun InputStream.copyToWithLimit(write: (ByteArray, Int, Int) -> Unit) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var totalBytes = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = read(buffer)
            if (read < 0) break
            totalBytes += read
            ensureGeneratedImageSize(totalBytes)
            write(buffer, 0, read)
        }
    }

    private suspend fun VideoReferenceImage.materializeForArk(): VideoReferenceImage {
        val source = url.trim()
        if (!source.startsWith("file:", ignoreCase = true) &&
            !source.startsWith("content:", ignoreCase = true)
        ) return copy(url = source)
        val appContext = context ?: error("Local video reference images require Android context")
        val uri = Uri.parse(source)
        val mimeType = appContext.contentResolver.getType(uri)
            ?: URLConnection.guessContentTypeFromName(uri.lastPathSegment.orEmpty())
            ?: "image/jpeg"
        require(mimeType.startsWith("image/")) { "Video reference must be an image" }
        val encoded = appContext.contentResolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                total += count
                require(total <= MAX_VIDEO_REFERENCE_IMAGE_BYTES) {
                    "Video reference image exceeds 20 MB"
                }
                output.write(buffer, 0, count)
            }
            require(total > 0L) { "Video reference image is empty" }
            Base64.getEncoder().encodeToString(output.toByteArray())
        } ?: error("Unable to open video reference image")
        return copy(url = "data:$mimeType;base64,$encoded")
    }

    private fun ensureImageResponseSize(contentLength: Long) {
        require(contentLength < 0 || contentLength <= MAX_IMAGE_RESPONSE_CHARS) {
            "Image response is too large"
        }
    }

    private fun ensureGeneratedImageSize(contentLength: Long) {
        require(contentLength < 0 || contentLength <= MAX_GENERATED_IMAGE_BYTES) {
            "Generated image is too large"
        }
    }

    private fun File.imageMediaType(): String = when (extension.lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        else -> "image/png"
    }

    private fun validateEditImageFiles(
        modelId: String,
        constraints: ImageGenerationConstraints,
        imageFiles: List<File>,
    ) {
        if (constraints.usesJsonImageEdit) return
        val normalizedModel = modelId.lowercase()
        when {
            GPT_IMAGE_MODEL_PATTERN.containsMatchIn(normalizedModel) -> imageFiles.forEach { imageFile ->
                require(imageFile.length() < GPT_IMAGE_MAX_INPUT_BYTES) {
                    "GPT Image reference images must be smaller than 50 MB"
                }
            }
            normalizedModel.contains("dall-e-2") -> {
                require(imageFiles.size == 1) { "DALL-E 2 accepts one reference image" }
                val imageFile = imageFiles.single()
                require(imageFile.extension.equals("png", ignoreCase = true)) {
                    "DALL-E 2 reference images must be PNG files"
                }
                require(imageFile.length() < DALL_E_2_MAX_INPUT_BYTES) {
                    "DALL-E 2 reference images must be smaller than 4 MB"
                }
            }
        }
    }

    override fun imageGenerationConstraints(
        providerSetting: ProviderSetting,
        model: Model,
    ): ImageGenerationConstraints {
        val normalizedModel = model.modelId.lowercase()
        val isXaiProvider = providerSetting is ProviderSetting.OpenAI &&
            providerSetting.baseUrl.contains("x.ai", ignoreCase = true)
        val isXaiImage = (isXaiProvider && normalizedModel.contains("image")) ||
            XAI_IMAGE_MODEL_MARKERS.any(normalizedModel::contains)
        val isXaiImagineImage = normalizedModel.contains("grok-imagine-image")
        val isXaiImage2 = isXaiImagineImage && normalizedModel.contains("grok-imagine-image-2")
        val isGptImage2 = GPT_IMAGE_2_MODEL_PATTERN.containsMatchIn(normalizedModel)
        val isGptImage25 = GPT_IMAGE_25_MODEL_PATTERN.containsMatchIn(normalizedModel)
        val isGptImage = GPT_IMAGE_MODEL_PATTERN.containsMatchIn(normalizedModel)
        val isDallE3 = normalizedModel.contains("dall-e-3")
        val isDallE2 = normalizedModel.contains("dall-e-2")
        val isSeedream = normalizedModel.contains("seedream")
        val isQwenImage = normalizedModel.contains("qwen-image")
        val isQwenImage3 = QWEN_IMAGE_3_PATTERN.containsMatchIn(normalizedModel)
        val isFlux = normalizedModel.contains("flux")
        val isFluxDev = isFlux && (
            normalizedModel.contains("flux-dev") ||
                normalizedModel.contains("flux.1-dev") ||
                normalizedModel.contains("flux1-dev")
            )
        val isStableDiffusion = STABLE_DIFFUSION_MODEL_MARKERS.any(normalizedModel::contains)
        val seedreamMajorVersion = SEEDREAM_VERSION.find(normalizedModel)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
        val isSeedream5Lite = isSeedream && seedreamMajorVersion == 5 && normalizedModel.contains("lite")
        val supportsSequentialSeedream = isSeedream && (
            seedreamMajorVersion == 4 || isSeedream5Lite
            )
        val isEditableSeedream = isSeedream && (seedreamMajorVersion ?: 0) >= 4
        val isSingleOutputModel = SINGLE_OUTPUT_IMAGE_MODEL_MARKERS.any(normalizedModel::contains)
        val supportsEdit = when {
            isSeedream -> isEditableSeedream
            else -> !isDallE3
        }
        val supportedSizes = when {
            isXaiImage -> XAI_IMAGE_ASPECT_RATIOS
            isSeedream -> SEEDREAM_IMAGE_SIZES
            isGptImage25 -> GPT_IMAGE_25_PRESET_SIZES
            isGptImage2 -> GPT_IMAGE_2_PRESET_SIZES
            isGptImage -> GPT_IMAGE_SIZES
            isDallE3 -> DALL_E_3_SIZES
            isDallE2 -> DALL_E_2_SIZES
            else -> null
        }
        return ImageGenerationConstraints(
            supportsGeneration = true,
            supportsEdit = supportsEdit,
            supportsPartialImages = false,
            maxOutputImages = when {
                isDallE3 || isSingleOutputModel -> 1
                isQwenImage3 -> 6
                isGptImage || isDallE2 -> 10
                isXaiImagineImage -> 10
                else -> 4
            },
            supportsOutputCount = !isSeedream,
            maxReferenceImages = when {
                !supportsEdit -> 0
                isGptImage25 -> 10
                isGptImage -> 16
                isEditableSeedream -> 10
                isXaiImagineImage -> 3
                isQwenImage3 -> 3
                else -> 1
            },
            supportsSize = true,
            supportedSizes = supportedSizes,
            supportsCustomSize = when {
            isGptImage25 -> true
                else -> isGptImage2 || isSeedream || supportedSizes == null
            },
            groupSizesByAspectRatio = isGptImage2 || isGptImage25,
            customSizeMultiple = if (isGptImage2 || isGptImage25) 16 else null,
            customSizeMaxDimension = when {
                isGptImage25 -> 3_840
                isGptImage2 -> 3_840
                isSeedream -> 4_096
                else -> null
            },
            customSizeMinPixels = if (isGptImage2 && !isGptImage25) GPT_IMAGE_2_MIN_PIXELS else null,
            customSizeMaxPixels = when {
                isGptImage25 -> GPT_IMAGE_25_MAX_PIXELS
                isGptImage2 -> GPT_IMAGE_2_MAX_PIXELS
                else -> null
            },
            customSizeMaxAspectRatio = when {
                isGptImage25 -> 3
                isGptImage2 -> 3
                isSeedream -> 16
                else -> null
            },
            sizeRequestField = if (isXaiImage) "aspect_ratio" else "size",
            supportedQualityValues = when {
                isXaiImage2 -> XAI_IMAGE_2_QUALITY
                isGptImage25 -> GPT_IMAGE_25_QUALITY
                isGptImage -> GPT_IMAGE_QUALITY
                isDallE3 -> DALL_E_3_QUALITY
                isDallE2 -> DALL_E_2_QUALITY
                else -> emptySet()
            },
            supportedOutputFormats = when {
                isGptImage -> GPT_IMAGE_OUTPUT_FORMATS
                isFlux || isStableDiffusion -> STABLE_IMAGE_OUTPUT_FORMATS
                isXaiImage || isSeedream || isDallE2 || isDallE3 -> URL_OR_BASE64_FORMATS
                else -> emptySet()
            },
            supportedBackgroundValues = when {
                isGptImage25 -> GPT_IMAGE_25_BACKGROUNDS
                isGptImage2 -> GPT_IMAGE_2_BACKGROUNDS
                isGptImage -> GPT_IMAGE_BACKGROUNDS
                else -> emptySet()
            },
            supportsOutputCompression = isGptImage,
            supportedResolutionValues = if (isXaiImagineImage) XAI_IMAGE_RESOLUTIONS else emptySet(),
            seedRange = when {
                isStableDiffusion || isQwenImage || isFlux -> 0L..Int.MAX_VALUE.toLong()
                else -> null
            },
            stepsRange = when {
                isFluxDev -> 1..50
                isStableDiffusion -> 10..150
                else -> null
            },
            defaultSteps = when {
                isFluxDev -> 28
                isStableDiffusion -> 30
                else -> null
            },
            guidanceScaleRange = when {
                isFluxDev -> 1.5f..5f
                isStableDiffusion -> 1f..35f
                else -> null
            },
            defaultGuidanceScale = when {
                isFluxDev -> 3f
                isStableDiffusion -> 7f
                else -> null
            },
            guidanceScaleRequestField = when {
                isFluxDev -> "guidance"
                isStableDiffusion -> "cfg_scale"
                else -> null
            },
            supportsNegativePrompt = isQwenImage || isStableDiffusion,
            promptEnhancementRequestField = when {
                isQwenImage -> "prompt_extend"
                isFlux -> "prompt_upsampling"
                else -> null
            },
            supportedPromptEnhancementModes = if (isQwenImage3) {
                QWEN_PROMPT_ENHANCEMENT_MODES
            } else {
                emptySet()
            },
            supportsImageThinking = isQwenImage3,
            supportsWatermark = isQwenImage || isSeedream,
            supportedModerationValues = if (isGptImage) OPENAI_IMAGE_MODERATION else emptySet(),
            supportedInputFidelityValues = if (
                isGptImage && !isGptImage2 && !normalizedModel.contains("mini")
            ) {
                OPENAI_IMAGE_INPUT_FIDELITY
            } else {
                emptySet()
            },
            safetyToleranceRange = if (isFluxDev) 0..6 else null,
            defaultSafetyTolerance = if (isFluxDev) 2 else null,
            supportedSamplerValues = if (isStableDiffusion) STABILITY_SAMPLERS else emptySet(),
            supportedStylePresetValues = if (isStableDiffusion) STABILITY_STYLE_PRESETS else emptySet(),
            sequentialImageMax = if (supportsSequentialSeedream) 15 else null,
            supportedPromptOptimizationModes = if (isSeedream5Lite) {
                SEEDREAM_PROMPT_OPTIMIZATION_MODES
            } else {
                emptySet()
            },
            blockedImageOptionKeys = when {
                isSeedream -> setOf(
                    "quality", "output_format", "output_compression", "background", "input_fidelity",
                    "thinking", "resolution", "stream", "partial_images",
                )
                isGptImage25 -> setOf("thinking", "response_format", "input_fidelity", "stream", "partial_images")
                isGptImage2 -> setOf("thinking", "response_format", "input_fidelity", "stream", "partial_images")
                isGptImage && normalizedModel.contains("mini") ->
                    setOf("thinking", "response_format", "input_fidelity", "stream", "partial_images")
                isGptImage -> setOf("thinking", "response_format", "stream", "partial_images")
                isXaiImage -> setOf(
                    "thinking", "size", "output_format", "output_compression", "background",
                    "input_fidelity", "stream", "partial_images",
                )
                isDallE2 || isDallE3 -> setOf(
                    "thinking", "output_format", "output_compression", "background", "input_fidelity",
                    "resolution", "stream", "partial_images",
                )
                else -> emptySet()
            },
            usesJsonImageEdit = isXaiImage || isEditableSeedream,
            usesGenerationEndpointForEdit = isEditableSeedream,
        )
    }

    companion object {
        private const val MAX_GENERATED_IMAGE_BYTES = 64L * 1024L * 1024L
        private const val MAX_IMAGE_RESPONSE_CHARS = 96L * 1024L * 1024L
        private const val MAX_VIDEO_REFERENCE_IMAGE_BYTES = 20L * 1024L * 1024L
        private const val GPT_IMAGE_MAX_INPUT_BYTES = 50L * 1024L * 1024L
        private const val DALL_E_2_MAX_INPUT_BYTES = 4L * 1024L * 1024L
        private const val IMAGE_GENERATION_TEMP_DIRECTORY = "image-generation"
        private const val TEMPORARY_IMAGE_MAX_AGE_MILLIS = 60L * 60L * 1_000L
        private val SUPPORTED_EDIT_IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp")
        private val RESERVED_IMAGE_EDIT_FIELDS = setOf(
            "model", "prompt", "n", "size", "aspect_ratio", "image", "image[]",
        )
        private val GPT_IMAGE_2_PRESET_SIZES = linkedSetOf(
            "auto",
            // Square
            "1024x1024", "1536x1536", "2048x2048", "2560x2560", "2880x2880",
            // 4:3 and 3:4
            "1024x768", "1536x1152", "2048x1536", "2560x1920", "3072x2304", "3264x2448",
            "768x1024", "1152x1536", "1536x2048", "1920x2560", "2304x3072", "2448x3264",
            // 3:2 and 2:3
            "1152x768", "1536x1024", "2304x1536", "3072x2048", "3456x2304",
            "768x1152", "1024x1536", "1536x2304", "2048x3072", "2304x3456",
            // 16:10 and 10:16
            "1280x800", "1920x1200", "2560x1600", "3200x2000", "3584x2240",
            "800x1280", "1200x1920", "1600x2560", "2000x3200", "2240x3584",
            // 16:9 and 9:16
            "1280x720", "1536x864", "2048x1152", "2560x1440", "3072x1728", "3840x2160",
            "720x1280", "864x1536", "1152x2048", "1440x2560", "1728x3072", "2160x3840",
            // 7:4 and 4:7, including the legacy 1792x1024 standard
            "1792x1024", "2240x1280", "2688x1536", "3136x1792", "3584x2048",
            "1024x1792", "1280x2240", "1536x2688", "1792x3136", "2048x3584",
            // 5:4 and 4:5
            "1280x1024", "1920x1536", "2560x2048", "3200x2560",
            "1024x1280", "1536x1920", "2048x2560", "2560x3200",
            // 2:1 and 1:2
            "1280x640", "2048x1024", "2560x1280", "3072x1536", "3584x1792", "3840x1920",
            "640x1280", "1024x2048", "1280x2560", "1536x3072", "1792x3584", "1920x3840",
            // 21:9 and 9:21
            "1344x576", "2016x864", "2688x1152", "3360x1440", "3696x1584",
            "576x1344", "864x2016", "1152x2688", "1440x3360", "1584x3696",
            // Maximum supported aspect ratios, 3:1 and 1:3
            "1536x512", "2304x768", "3072x1024", "3456x1152", "3840x1280",
            "512x1536", "768x2304", "1024x3072", "1152x3456", "1280x3840",
        )
        private val GPT_IMAGE_25_PRESET_SIZES = GPT_IMAGE_2_PRESET_SIZES
        private const val GPT_IMAGE_2_MIN_PIXELS = 655_360L
        private const val GPT_IMAGE_2_MAX_PIXELS = 8_294_400L
        private const val GPT_IMAGE_25_MAX_PIXELS = 8_294_400L
        private val GPT_IMAGE_QUALITY = setOf("auto", "low", "medium", "high")
        private val GPT_IMAGE_25_QUALITY = setOf("auto", "low", "medium", "high", "xhigh", "max")
        private val GPT_IMAGE_OUTPUT_FORMATS = setOf("png", "jpeg", "webp")
        private val STABLE_IMAGE_OUTPUT_FORMATS = setOf("png", "jpeg", "webp")
        private val OPENAI_IMAGE_MODERATION = setOf("auto", "low")
        private val OPENAI_IMAGE_INPUT_FIDELITY = setOf("low", "high")
        private val GPT_IMAGE_2_BACKGROUNDS = setOf("auto", "opaque")
        // OpenAI's current image API reference documents transparent for the 2.5 models.
        private val GPT_IMAGE_25_BACKGROUNDS = setOf("auto", "transparent", "opaque")
        private val GPT_IMAGE_BACKGROUNDS = setOf("auto", "opaque", "transparent")
        private val GPT_IMAGE_SIZES = linkedSetOf("auto", "1024x1024", "1536x1024", "1024x1536")
        private val GPT_IMAGE_MODEL_PATTERN = Regex("(?:^|[^a-z0-9])gpt[^a-z0-9]*image")
        private val GPT_IMAGE_2_MODEL_PATTERN = Regex(
            "(?:^|[^a-z0-9])gpt[^a-z0-9]*image[^a-z0-9]*2(?:[^0-9]|$)"
        )
        private val GPT_IMAGE_25_MODEL_PATTERN = Regex(
            "(?:^|[^a-z0-9])gpt[^a-z0-9]*image[^a-z0-9]*2[._-]?5(?:[._-]?(?:flare|sunburst))?(?:[^a-z0-9]|$)"
        )
        private val DALL_E_3_SIZES = linkedSetOf("auto", "1024x1024", "1792x1024", "1024x1792")
        private val DALL_E_2_SIZES = linkedSetOf("auto", "256x256", "512x512", "1024x1024")
        private val DALL_E_3_QUALITY = setOf("standard", "hd")
        private val DALL_E_2_QUALITY = setOf("standard")
        private val URL_OR_BASE64_FORMATS = setOf("url", "b64_json")
        private val XAI_IMAGE_ASPECT_RATIOS = linkedSetOf(
            "auto", "1:1", "16:9", "9:16", "4:3", "3:4", "3:2", "2:3", "2:1", "1:2", "19.5:9", "9:19.5", "20:9", "9:20",
        )
        private val XAI_IMAGE_RESOLUTIONS = setOf("1k", "2k")
        private val XAI_IMAGE_2_QUALITY = setOf("low", "medium")
        private val XAI_IMAGE_MODEL_MARKERS = listOf("grok-imagine-image", "grok-2-image")
        private val QWEN_IMAGE_3_PATTERN = Regex("(?:^|[^a-z0-9])qwen[^a-z0-9]*image[^a-z0-9]*3(?:[^0-9]|$)")
        private val QWEN_PROMPT_ENHANCEMENT_MODES = setOf("direct", "agent")
        private val SEEDREAM_PROMPT_OPTIMIZATION_MODES = setOf("standard", "fast")
        private val STABILITY_SAMPLERS = linkedSetOf(
            "DDIM",
            "DDPM",
            "K_DPMPP_2M",
            "K_DPMPP_2S_ANCESTRAL",
            "K_DPM_2",
            "K_DPM_2_ANCESTRAL",
            "K_EULER",
            "K_EULER_ANCESTRAL",
            "K_HEUN",
            "K_LMS",
        )
        private val STABILITY_STYLE_PRESETS = linkedSetOf(
            "3d-model",
            "analog-film",
            "anime",
            "cinematic",
            "comic-book",
            "digital-art",
            "enhance",
            "fantasy-art",
            "isometric",
            "line-art",
            "low-poly",
            "modeling-compound",
            "neon-punk",
            "origami",
            "photographic",
            "pixel-art",
            "tile-texture",
        )
        private val STABLE_DIFFUSION_MODEL_MARKERS = listOf(
            "stable-diffusion",
            "stable_image",
            "stable-image",
            "sdxl",
        )
        private val SEEDREAM_VERSION = Regex("seedream[-_.]?v?[-_.]?(\\d+)")
        private val SEEDREAM_IMAGE_SIZES = linkedSetOf(
            "2048x2048", "2560x1440", "1440x2560",
            "2730x2048", "2048x2730", "3072x2048", "2048x3072",
            "4096x4096", "4096x2304", "2304x4096",
        )
        private val SINGLE_OUTPUT_IMAGE_MODEL_MARKERS = listOf(
            "dall-e-3",
            "flux",
            "seedream",
            "jimeng",
            "cogview",
            "kolors",
            "wanx",
            "hunyuan-image",
        )
    }
}

internal fun openAIImageRequestFailure(operation: String, response: Response): Throwable {
    val responseDetail = runCatching {
        response.peekBody(MAX_IMAGE_ERROR_RESPONSE_BYTES).string().trim()
    }.getOrNull()
    val detail = buildString {
        append("Failed to ").append(operation).append(": HTTP ").append(response.code)
        responseDetail?.takeIf(String::isNotEmpty)?.let { append(": ").append(it) }
    }
    return providerRequestFailure(response = response, cause = null, detail = detail)
}

internal fun buildOpenAIImageGenerationRequestBody(
    params: ImageGenerationParams,
    constraints: ImageGenerationConstraints,
): JsonObject {
    val outputCount = params.numOfImages.coerceIn(1, constraints.maxOutputImages)
    val explicitOptions = params.explicitImageOptions(constraints)
    val advancedOptions = params.advancedImageOptions(constraints)
    val reservedFields = setOf("model", "prompt", "n", constraints.sizeRequestField)
    val customFields = buildJsonObject {}
        .mergeCustomBody(params.customBody)
        .filter { (key, value) ->
            key.lowercase() !in reservedFields &&
                key.lowercase() !in explicitOptions &&
                key.lowercase() !in advancedOptions &&
                constraints.acceptsImageOption(CustomBody(key, value))
        }

    return buildJsonObject {
        customFields.forEach { (key, value) -> put(key, value) }
        put("model", params.model.modelId)
        put("prompt", params.prompt)
        if (constraints.supportsOutputCount) put("n", outputCount)
        constraints.normalizedSize(params.size)?.let { put(constraints.sizeRequestField, it) }
        explicitOptions.forEach { (key, value) ->
            if (key == "output_compression") put(key, value.toInt()) else put(key, value)
        }
        advancedOptions.forEach { (key, value) -> put(key, value) }
    }
}

internal fun buildXaiImageEditRequestBody(
    params: ImageEditParams,
    constraints: ImageGenerationConstraints,
    images: List<Pair<File, String>>,
): RequestBody {
    require(images.isNotEmpty()) { "At least one xAI edit image is required" }
    require(images.size <= constraints.maxReferenceImages) {
        "xAI image editing accepts at most ${constraints.maxReferenceImages} source images"
    }
    val outputCount = params.numOfImages.coerceIn(1, constraints.maxOutputImages)
    val explicitOptions = params.explicitImageOptions(constraints)
    val advancedOptions = params.advancedImageOptions(constraints)
    val reservedFields = RESERVED_XAI_EDIT_FIELDS + constraints.sizeRequestField
    val customFields = buildJsonObject {}
        .mergeCustomBody(params.customBody)
        .filter { (key, value) ->
            key.lowercase() !in reservedFields &&
                key.lowercase() !in explicitOptions &&
                key.lowercase() !in advancedOptions &&
                constraints.acceptsImageOption(CustomBody(key, value))
        }
    val metadata = buildJsonObject {
        customFields.forEach { (key, value) -> put(key, value) }
        put("model", params.model.modelId)
        put("prompt", params.prompt)
        put("n", outputCount)
        constraints.normalizedSize(params.size)?.let { put(constraints.sizeRequestField, it) }
        explicitOptions.forEach { (key, value) ->
            if (key == "output_compression") put(key, value.toInt()) else put(key, value)
        }
        advancedOptions.forEach { (key, value) -> put(key, value) }
    }
    return XaiImageEditRequestBody(metadata, images)
}

internal fun buildSeedreamImageEditRequestBody(
    params: ImageEditParams,
    constraints: ImageGenerationConstraints,
    images: List<Pair<File, String>>,
): RequestBody {
    require(images.isNotEmpty()) { "At least one Seedream reference image is required" }
    require(images.size <= constraints.maxReferenceImages) {
        "Seedream image editing accepts at most ${constraints.maxReferenceImages} reference images"
    }
    val explicitOptions = params.explicitImageOptions(constraints)
    val advancedOptions = params.advancedImageOptions(constraints)
    val reservedFields = RESERVED_SEEDREAM_EDIT_FIELDS + constraints.sizeRequestField
    val customFields = buildJsonObject {}
        .mergeCustomBody(params.customBody)
        .filter { (key, value) ->
            key.lowercase() !in reservedFields &&
                key.lowercase() !in explicitOptions &&
                key.lowercase() !in advancedOptions &&
                constraints.acceptsImageOption(CustomBody(key, value))
        }
    val metadata = buildJsonObject {
        customFields.forEach { (key, value) -> put(key, value) }
        put("model", params.model.modelId)
        put("prompt", params.prompt)
        constraints.normalizedSize(params.size)?.let { put(constraints.sizeRequestField, it) }
        explicitOptions.forEach { (key, value) -> put(key, value) }
        advancedOptions.forEach { (key, value) -> put(key, value) }
    }
    return SeedreamImageEditRequestBody(metadata, images)
}

private fun ImageGenerationConstraints.normalizedSize(requestedSize: String): String? {
    if (!supportsSize || requestedSize.isBlank() || requestedSize == ImageGenSize.AUTO.value) return null
    if (supportedSizes?.contains(requestedSize) == true) return requestedSize
    if (!supportsCustomSize) return null
    if (
        supportedSizes == null && customSizeMultiple == null && customSizeMaxDimension == null &&
        customSizeMinPixels == null && customSizeMaxPixels == null && customSizeMaxAspectRatio == null
    ) return requestedSize

    val match = CUSTOM_IMAGE_SIZE_REGEX.matchEntire(requestedSize.lowercase()) ?: return null
    val width = match.groupValues[1].toIntOrNull() ?: return null
    val height = match.groupValues[2].toIntOrNull() ?: return null
    if (width <= 0 || height <= 0) return null
    customSizeMultiple?.let { multiple ->
        if (width % multiple != 0 || height % multiple != 0) return null
    }
    customSizeMaxDimension?.let { maxDimension ->
        if (width > maxDimension || height > maxDimension) return null
    }
    val pixels = width.toLong() * height.toLong()
    customSizeMinPixels?.let { minPixels -> if (pixels < minPixels) return null }
    customSizeMaxPixels?.let { maxPixels -> if (pixels > maxPixels) return null }
    customSizeMaxAspectRatio?.let { maxRatio ->
        val longEdge = maxOf(width, height).toLong()
        val shortEdge = minOf(width, height).toLong()
        if (longEdge > shortEdge * maxRatio) return null
    }
    return "${width}x${height}"
}

private fun ImageGenerationConstraints.acceptsImageOption(customBody: CustomBody): Boolean {
    if (!customBody.enabled || customBody.key.isBlank()) return false
    val key = customBody.key.trim().lowercase()
    if (key in blockedImageOptionKeys) return false
    val allowedValues = when (key) {
        "quality" -> supportedQualityValues.takeIf { it.isNotEmpty() }
        "output_format" -> supportedOutputFormats
            .takeIf { formats -> formats.any { it in IMAGE_FILE_FORMATS } }
        "response_format" -> supportedOutputFormats
            .takeIf { formats -> formats.any { it in URL_OR_BASE64_FORMATS } }
        "background" -> supportedBackgroundValues.takeIf { it.isNotEmpty() }
        "resolution" -> supportedResolutionValues.takeIf { it.isNotEmpty() }
        sizeRequestField -> supportedSizes?.takeIf { it.isNotEmpty() }
        "output_compression" -> return if (supportsOutputCompression) {
            (customBody.value as? JsonPrimitive)?.contentOrNull?.toIntOrNull() in 0..100
        } else {
            true
        }
        else -> null
    } ?: return true
    return (customBody.value as? JsonPrimitive)?.contentOrNull in allowedValues
}

private fun ImageGenerationParams.explicitImageOptions(
    constraints: ImageGenerationConstraints,
): Map<String, String> = buildMap {
    quality?.takeIf { it in constraints.supportedQualityValues }?.let { put("quality", it) }
    outputFormat?.takeIf { it in constraints.supportedOutputFormats }?.let {
        put(constraints.outputFormatRequestField(), it)
    }
    background
        ?.takeIf { it in constraints.supportedBackgroundValues }
        ?.takeUnless { it == "transparent" && outputFormat !in TRANSPARENCY_IMAGE_FORMATS }
        ?.let { put("background", it) }
    if (constraints.supportsOutputCompression && outputFormat in COMPRESSIBLE_IMAGE_FORMATS) {
        put("output_compression", outputCompression.coerceIn(0, 100).toString())
    }
    resolution?.takeIf { it in constraints.supportedResolutionValues }?.let { put("resolution", it) }
}

private fun ImageEditParams.explicitImageOptions(
    constraints: ImageGenerationConstraints,
): Map<String, String> = buildMap {
    quality?.takeIf { it in constraints.supportedQualityValues }?.let { put("quality", it) }
    outputFormat?.takeIf { it in constraints.supportedOutputFormats }?.let {
        put(constraints.outputFormatRequestField(), it)
    }
    background
        ?.takeIf { it in constraints.supportedBackgroundValues }
        ?.takeUnless { it == "transparent" && outputFormat !in TRANSPARENCY_IMAGE_FORMATS }
        ?.let { put("background", it) }
    if (constraints.supportsOutputCompression && outputFormat in COMPRESSIBLE_IMAGE_FORMATS) {
        put("output_compression", outputCompression.coerceIn(0, 100).toString())
    }
    resolution?.takeIf { it in constraints.supportedResolutionValues }?.let { put("resolution", it) }
}

private fun ImageGenerationParams.advancedImageOptions(
    constraints: ImageGenerationConstraints,
): Map<String, JsonElement> = buildAdvancedImageOptions(
    constraints = constraints,
    editing = false,
    seed = seed,
    steps = steps,
    guidanceScale = guidanceScale,
    negativePrompt = negativePrompt,
    promptEnhancement = promptEnhancement,
    promptEnhancementMode = promptEnhancementMode,
    imageThinking = imageThinking,
    watermark = watermark,
    moderation = moderation,
    inputFidelity = null,
    safetyTolerance = safetyTolerance,
    sampler = sampler,
    stylePreset = stylePreset,
    sequentialImageGeneration = sequentialImageGeneration,
    sequentialMaxImages = sequentialMaxImages,
    promptOptimizationMode = promptOptimizationMode,
)

private fun ImageEditParams.advancedImageOptions(
    constraints: ImageGenerationConstraints,
): Map<String, JsonElement> = buildAdvancedImageOptions(
    constraints = constraints,
    editing = true,
    seed = seed,
    steps = steps,
    guidanceScale = guidanceScale,
    negativePrompt = negativePrompt,
    promptEnhancement = promptEnhancement,
    promptEnhancementMode = promptEnhancementMode,
    imageThinking = imageThinking,
    watermark = watermark,
    moderation = moderation,
    inputFidelity = inputFidelity,
    safetyTolerance = safetyTolerance,
    sampler = sampler,
    stylePreset = stylePreset,
    sequentialImageGeneration = sequentialImageGeneration,
    sequentialMaxImages = sequentialMaxImages,
    promptOptimizationMode = promptOptimizationMode,
)

private fun buildAdvancedImageOptions(
    constraints: ImageGenerationConstraints,
    editing: Boolean,
    seed: Long?,
    steps: Int?,
    guidanceScale: Float?,
    negativePrompt: String?,
    promptEnhancement: Boolean?,
    promptEnhancementMode: String?,
    imageThinking: Boolean?,
    watermark: Boolean?,
    moderation: String?,
    inputFidelity: String?,
    safetyTolerance: Int?,
    sampler: String?,
    stylePreset: String?,
    sequentialImageGeneration: Boolean?,
    sequentialMaxImages: Int,
    promptOptimizationMode: String?,
): Map<String, JsonElement> = buildMap {
    val seedRange = constraints.seedRange
    seed?.takeIf { seedRange != null && it in seedRange }?.let { put("seed", JsonPrimitive(it)) }

    val stepsRange = constraints.stepsRange
    steps?.takeIf { stepsRange != null && it in stepsRange }?.let { put("steps", JsonPrimitive(it)) }

    val guidanceRange = constraints.guidanceScaleRange
    val guidanceField = constraints.guidanceScaleRequestField
    guidanceScale
        ?.takeIf { guidanceRange != null && it in guidanceRange && guidanceField != null }
        ?.let { put(requireNotNull(guidanceField), JsonPrimitive(it)) }

    negativePrompt
        ?.trim()
        ?.takeIf { constraints.supportsNegativePrompt && it.isNotEmpty() }
        ?.let { put("negative_prompt", JsonPrimitive(it)) }
    promptEnhancement?.let { enabled ->
        constraints.promptEnhancementRequestField?.let { field -> put(field, JsonPrimitive(enabled)) }
    }
    val effectiveEnhancementMode = promptEnhancementMode?.takeIf {
        it in constraints.supportedPromptEnhancementModes && !(editing && it == "agent")
    }
    if (promptEnhancement != false) {
        effectiveEnhancementMode?.let { put("prompt_extend_mode", JsonPrimitive(it)) }
        imageThinking
            ?.takeIf { constraints.supportsImageThinking && !(editing && effectiveEnhancementMode == "agent") }
            ?.let { put("enable_thinking", JsonPrimitive(it)) }
    }
    watermark?.takeIf { constraints.supportsWatermark }?.let { put("watermark", JsonPrimitive(it)) }
    moderation
        ?.takeIf { it in constraints.supportedModerationValues }
        ?.let { put("moderation", JsonPrimitive(it)) }
    inputFidelity
        ?.takeIf { editing && it in constraints.supportedInputFidelityValues }
        ?.let { put("input_fidelity", JsonPrimitive(it)) }

    val safetyRange = constraints.safetyToleranceRange
    safetyTolerance
        ?.takeIf { safetyRange != null && it in safetyRange }
        ?.let { put("safety_tolerance", JsonPrimitive(it)) }
    sampler
        ?.takeIf { it in constraints.supportedSamplerValues }
        ?.let { put("sampler", JsonPrimitive(it)) }
    stylePreset
        ?.takeIf { it in constraints.supportedStylePresetValues }
        ?.let { put("style_preset", JsonPrimitive(it)) }

    val sequentialMax = constraints.sequentialImageMax
    sequentialImageGeneration?.takeIf { sequentialMax != null }?.let { enabled ->
        put("sequential_image_generation", JsonPrimitive(if (enabled) "auto" else "disabled"))
        if (enabled) {
            put("sequential_image_generation_options", buildJsonObject {
                put("max_images", sequentialMaxImages.coerceIn(1, requireNotNull(sequentialMax)))
            })
        }
    }
    promptOptimizationMode
        ?.takeIf { it in constraints.supportedPromptOptimizationModes }
        ?.let { mode ->
            put("optimize_prompt_options", buildJsonObject { put("mode", mode) })
        }
}

private fun ImageGenerationConstraints.outputFormatRequestField(): String =
    if (supportedOutputFormats.any { it in IMAGE_FILE_FORMATS }) "output_format" else "response_format"

private fun ImageGenerationParams.requestedImageFileFormat(constraints: ImageGenerationConstraints): String =
    outputFormat?.takeIf { it in IMAGE_FILE_FORMATS }
        ?: customBody.lastValidImageFileFormat(constraints)
        ?: if (constraints.usesJsonImageEdit) "jpeg" else "png"

private fun ImageEditParams.requestedImageFileFormat(constraints: ImageGenerationConstraints): String =
    outputFormat?.takeIf { it in IMAGE_FILE_FORMATS }
        ?: customBody.lastValidImageFileFormat(constraints)
        ?: if (constraints.usesJsonImageEdit) "jpeg" else "png"

private fun List<CustomBody>.lastValidImageFileFormat(constraints: ImageGenerationConstraints): String? =
    lastOrNull { it.key.trim().equals("output_format", ignoreCase = true) && constraints.acceptsImageOption(it) }
        ?.value
        ?.let { it as? JsonPrimitive }
        ?.contentOrNull

private class XaiImageEditRequestBody(
    private val metadata: JsonObject,
    private val images: List<Pair<File, String>>,
) : RequestBody() {
    override fun contentType() = "application/json".toMediaType()

    override fun writeTo(sink: BufferedSink) {
        sink.writeUtf8("{")
        metadata.entries.forEachIndexed { index, (key, value) ->
            if (index > 0) sink.writeUtf8(",")
            sink.writeUtf8(JsonPrimitive(key).toString())
            sink.writeUtf8(":")
            sink.writeUtf8(value.toString())
        }
        if (metadata.isNotEmpty()) sink.writeUtf8(",")
        if (images.size == 1) {
            sink.writeUtf8("\"image\":")
            writeImage(sink, images.single())
        } else {
            sink.writeUtf8("\"images\":[")
            images.forEachIndexed { index, image ->
                if (index > 0) sink.writeUtf8(",")
                writeImage(sink, image)
            }
            sink.writeUtf8("]")
        }
        sink.writeUtf8("}")
    }

    private fun writeImage(sink: BufferedSink, image: Pair<File, String>) {
        sink.writeUtf8("{\"url\":")
        writeImageDataUri(sink, image)
        sink.writeUtf8(",\"type\":\"image_url\"}")
    }
}

private class SeedreamImageEditRequestBody(
    private val metadata: JsonObject,
    private val images: List<Pair<File, String>>,
) : RequestBody() {
    override fun contentType() = "application/json".toMediaType()

    override fun writeTo(sink: BufferedSink) {
        sink.writeUtf8("{")
        metadata.entries.forEachIndexed { index, (key, value) ->
            if (index > 0) sink.writeUtf8(",")
            sink.writeUtf8(JsonPrimitive(key).toString())
            sink.writeUtf8(":")
            sink.writeUtf8(value.toString())
        }
        if (metadata.isNotEmpty()) sink.writeUtf8(",")
        sink.writeUtf8("\"image\":[")
        images.forEachIndexed { index, image ->
            if (index > 0) sink.writeUtf8(",")
            writeImageDataUri(sink, image)
        }
        sink.writeUtf8("]}")
    }
}

private fun writeImageDataUri(sink: BufferedSink, image: Pair<File, String>) {
    val (imageFile, imageMimeType) = image
    sink.writeUtf8("\"data:")
    sink.writeUtf8(imageMimeType)
    sink.writeUtf8(";base64,")
    val nonClosingOutput = object : FilterOutputStream(sink.outputStream()) {
        override fun close() = flush()
    }
    Base64.getEncoder().wrap(nonClosingOutput).use { encodedOutput ->
        imageFile.inputStream().use { input -> input.copyTo(encodedOutput, IMAGE_EDIT_COPY_BUFFER_BYTES) }
    }
    sink.writeUtf8("\"")
}

private val CUSTOM_IMAGE_SIZE_REGEX = Regex("^(\\d+)x(\\d+)$")
private val IMAGE_FILE_FORMATS = setOf("png", "jpeg", "webp")
private val COMPRESSIBLE_IMAGE_FORMATS = setOf("jpeg", "webp")
private val TRANSPARENCY_IMAGE_FORMATS = setOf(null, "png", "webp")
private val URL_OR_BASE64_FORMATS = setOf("url", "b64_json")
private val RESERVED_XAI_EDIT_FIELDS =
    setOf("model", "prompt", "n", "image", "images", "image[]", "size", "aspect_ratio")
private val RESERVED_SEEDREAM_EDIT_FIELDS =
    setOf("model", "prompt", "n", "image", "images", "image[]", "size", "aspect_ratio")
private const val IMAGE_EDIT_COPY_BUFFER_BYTES = 256 * 1024
private const val MAX_IMAGE_ERROR_RESPONSE_BYTES = 64L * 1024L
private const val MAX_VIDEO_ERROR_RESPONSE_BYTES = 64L * 1024L
private const val MAX_VIDEO_TASK_RESPONSE_BYTES = 1L * 1024L * 1024L
private const val VOLCENGINE_MAX_INPUT_BYTES = 100_000

internal fun inferOpenAIModelType(modelId: String): ModelType = inferModelTypeFromId(modelId)

/** Parses both OpenAI's one-dimensional vectors and Ark's nested multimodal vectors. */
internal fun parseEmbeddingVector(element: JsonElement): List<Float> {
    val values = when (element) {
        is JsonArray -> {
            // Ark multimodal responses currently return [[...]] while the text API
            // returns [...]. Flatten exactly one wrapper and leave malformed nesting
            // to the validation below.
            if (element.size == 1 && element.firstOrNull() is JsonArray) {
                element.first().jsonArray
            } else {
                element
            }
        }

        is JsonPrimitive -> {
            val encoded = element.contentOrNull?.takeIf(String::isNotBlank)
                ?: error("Embedding value is empty")
            val bytes = runCatching { Base64.getDecoder().decode(encoded) }
                .getOrElse { error("Unsupported embedding value") }
            require(bytes.isNotEmpty() && bytes.size % Float.SIZE_BYTES == 0) {
                "Base64 embedding has an invalid byte length"
            }
            // OpenAI-compatible APIs encode base64 embeddings as IEEE-754 float32
            // values in little-endian order.
            val buffer = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            return List(bytes.size / Float.SIZE_BYTES) { buffer.float }
        }

        else -> error("Embedding value must be an array or base64 string")
    }
    return values.map { value ->
        value.jsonPrimitive.contentOrNull?.toFloatOrNull()
            ?: error("Embedding value is not numeric")
    }
}

private fun ensureVideoTaskResponseSize(contentLength: Long) {
    require(contentLength < 0 || contentLength <= MAX_VIDEO_TASK_RESPONSE_BYTES) {
        "Video task response is too large"
    }
}

internal fun openAIVideoRequestFailure(operation: String, response: Response): Throwable {
    val responseDetail = runCatching {
        response.peekBody(MAX_VIDEO_ERROR_RESPONSE_BYTES).string().trim()
    }.getOrNull()
    val detail = buildString {
        append("Failed to ").append(operation).append(": HTTP ").append(response.code)
        responseDetail?.takeIf(String::isNotEmpty)?.let { append(": ").append(it) }
    }
    return providerRequestFailure(response = response, cause = null, detail = detail)
}
