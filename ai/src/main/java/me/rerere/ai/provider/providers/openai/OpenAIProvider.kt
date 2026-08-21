package me.rerere.ai.provider.providers.openai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
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
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderCapability
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.contextWindowTokensOrNull
import me.rerere.ai.provider.inferModelTypeFromId
import me.rerere.ai.provider.usesVolcengineMultimodalEmbeddingApi
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
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import java.io.File
import java.io.FilterOutputStream
import java.io.InputStream
import java.util.Base64

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
        require(params.images.isEmpty() || params.model.usesVolcengineMultimodalEmbeddingApi()) {
            "The selected embedding model does not support image inputs"
        }

        val key = keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())
        val usesMultimodalEndpoint = params.model.usesVolcengineMultimodalEmbeddingApi()
        val requestBody = json.encodeToString(
            buildJsonObject {
                put("model", params.model.modelId)
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
                } else if (params.input.size == 1) {
                    put("input", params.input.first())
                } else {
                    putJsonArray("input") {
                        params.input.forEach { add(JsonPrimitive(it)) }
                    }
                }
                if (!usesMultimodalEndpoint) params.dimensions?.let { put("dimensions", it) }
            }.mergeCustomBody(params.customBody)
        )

        val request = Request.Builder()
            .url(
                if (usesMultimodalEndpoint) {
                    "${providerSetting.baseUrl}/embeddings/multimodal"
                } else {
                    "${providerSetting.baseUrl}/embeddings"
                }
            )
            .headers(params.customHeaders.toHeaders())
            .addHeader("Authorization", "Bearer $key")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).await()
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
        val model = bodyJson["model"]?.jsonPrimitive?.contentOrNull ?: params.model.modelId

        val embeddings = data.sortedBy { embeddingJson ->
            embeddingJson.jsonObject["index"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                ?: Int.MAX_VALUE
        }.map { embeddingJson ->
            val embeddingArray = embeddingJson.jsonObject["embedding"]?.jsonArray
                ?: error("No embedding in response")
            embeddingArray.map { it.jsonPrimitive.content.toFloat() }
        }

        EmbeddingGenerationResult(
            model = model,
            embeddings = embeddings
        )
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
                throw imageRequestFailure("generate image", response.code)
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
                params.customBody
                    .filter { customBody ->
                        val field = customBody.key.lowercase()
                        field !in RESERVED_IMAGE_EDIT_FIELDS &&
                            field !in explicitOptions &&
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
                throw imageRequestFailure("edit image", response.code)
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
                throw imageRequestFailure("download generated image", response.code)
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
        val isGptImage = GPT_IMAGE_MODEL_PATTERN.containsMatchIn(normalizedModel)
        val isDallE3 = normalizedModel.contains("dall-e-3")
        val isDallE2 = normalizedModel.contains("dall-e-2")
        val isSeedream = normalizedModel.contains("seedream")
        val seedreamMajorVersion = SEEDREAM_VERSION.find(normalizedModel)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
        val isEditableSeedream = isSeedream && (seedreamMajorVersion ?: 0) >= 4
        val isSingleOutputModel = SINGLE_OUTPUT_IMAGE_MODEL_MARKERS.any(normalizedModel::contains)
        val supportsEdit = when {
            isSeedream -> isEditableSeedream
            else -> !isDallE3
        }
        val supportedSizes = when {
            isXaiImage -> XAI_IMAGE_ASPECT_RATIOS
            isSeedream -> SEEDREAM_IMAGE_SIZES
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
                isGptImage || isDallE2 -> 10
                isXaiImagineImage -> 10
                else -> 4
            },
            supportsOutputCount = !isSeedream,
            maxReferenceImages = when {
                !supportsEdit -> 0
                isGptImage -> 16
                isEditableSeedream -> 10
                isXaiImagineImage -> 3
                else -> 1
            },
            supportsSize = true,
            supportedSizes = supportedSizes,
            supportsCustomSize = isGptImage2 || isSeedream || supportedSizes == null,
            groupSizesByAspectRatio = isGptImage2,
            customSizeMultiple = if (isGptImage2) 16 else null,
            customSizeMaxDimension = when {
                isGptImage2 -> 3_840
                isSeedream -> 4_096
                else -> null
            },
            customSizeMinPixels = if (isGptImage2) GPT_IMAGE_2_MIN_PIXELS else null,
            customSizeMaxPixels = if (isGptImage2) GPT_IMAGE_2_MAX_PIXELS else null,
            customSizeMaxAspectRatio = when {
                isGptImage2 -> 3
                isSeedream -> 16
                else -> null
            },
            sizeRequestField = if (isXaiImage) "aspect_ratio" else "size",
            supportedQualityValues = when {
                isXaiImage2 -> XAI_IMAGE_2_QUALITY
                isGptImage -> GPT_IMAGE_QUALITY
                isDallE3 -> DALL_E_3_QUALITY
                isDallE2 -> DALL_E_2_QUALITY
                else -> emptySet()
            },
            supportedOutputFormats = when {
                isGptImage -> GPT_IMAGE_OUTPUT_FORMATS
                isXaiImage || isSeedream || isDallE2 || isDallE3 -> URL_OR_BASE64_FORMATS
                else -> emptySet()
            },
            supportedBackgroundValues = when {
                isGptImage2 -> GPT_IMAGE_2_BACKGROUNDS
                isGptImage -> GPT_IMAGE_BACKGROUNDS
                else -> emptySet()
            },
            supportsOutputCompression = isGptImage,
            supportedResolutionValues = if (isXaiImagineImage) XAI_IMAGE_RESOLUTIONS else emptySet(),
            blockedImageOptionKeys = when {
                isSeedream -> setOf(
                    "quality", "output_format", "output_compression", "background", "input_fidelity",
                    "thinking", "resolution", "stream", "partial_images",
                )
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

    private fun imageRequestFailure(operation: String, statusCode: Int) =
        me.rerere.ai.provider.ProviderRequestException(
            statusCode = statusCode,
            retryAfterMillis = null,
            message = "Failed to $operation: HTTP $statusCode",
        )

    companion object {
        private const val MAX_GENERATED_IMAGE_BYTES = 64L * 1024L * 1024L
        private const val MAX_IMAGE_RESPONSE_CHARS = 96L * 1024L * 1024L
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
        private const val GPT_IMAGE_2_MIN_PIXELS = 655_360L
        private const val GPT_IMAGE_2_MAX_PIXELS = 8_294_400L
        private val GPT_IMAGE_QUALITY = setOf("auto", "low", "medium", "high")
        private val GPT_IMAGE_OUTPUT_FORMATS = setOf("png", "jpeg", "webp")
        private val GPT_IMAGE_2_BACKGROUNDS = setOf("auto", "opaque")
        private val GPT_IMAGE_BACKGROUNDS = setOf("auto", "opaque", "transparent")
        private val GPT_IMAGE_SIZES = linkedSetOf("auto", "1024x1024", "1536x1024", "1024x1536")
        private val GPT_IMAGE_MODEL_PATTERN = Regex("(?:^|[^a-z0-9])gpt[^a-z0-9]*image")
        private val GPT_IMAGE_2_MODEL_PATTERN = Regex(
            "(?:^|[^a-z0-9])gpt[^a-z0-9]*image[^a-z0-9]*2(?:[^0-9]|$)"
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

internal fun buildOpenAIImageGenerationRequestBody(
    params: ImageGenerationParams,
    constraints: ImageGenerationConstraints,
): JsonObject {
    val outputCount = params.numOfImages.coerceIn(1, constraints.maxOutputImages)
    val explicitOptions = params.explicitImageOptions(constraints)
    val reservedFields = setOf("model", "prompt", "n", constraints.sizeRequestField)
    val customFields = buildJsonObject {}
        .mergeCustomBody(params.customBody)
        .filter { (key, value) ->
            key.lowercase() !in reservedFields &&
                key.lowercase() !in explicitOptions &&
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
    val reservedFields = RESERVED_XAI_EDIT_FIELDS + constraints.sizeRequestField
    val customFields = buildJsonObject {}
        .mergeCustomBody(params.customBody)
        .filter { (key, value) ->
            key.lowercase() !in reservedFields &&
                key.lowercase() !in explicitOptions &&
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
    val reservedFields = RESERVED_SEEDREAM_EDIT_FIELDS + constraints.sizeRequestField
    val customFields = buildJsonObject {}
        .mergeCustomBody(params.customBody)
        .filter { (key, value) ->
            key.lowercase() !in reservedFields &&
                key.lowercase() !in explicitOptions &&
                constraints.acceptsImageOption(CustomBody(key, value))
        }
    val metadata = buildJsonObject {
        customFields.forEach { (key, value) -> put(key, value) }
        put("model", params.model.modelId)
        put("prompt", params.prompt)
        constraints.normalizedSize(params.size)?.let { put(constraints.sizeRequestField, it) }
        explicitOptions.forEach { (key, value) -> put(key, value) }
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
    val key = customBody.key.lowercase()
    if (key in blockedImageOptionKeys) return false
    val allowedValues = when (customBody.key.lowercase()) {
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
    lastOrNull { it.key.equals("output_format", ignoreCase = true) && constraints.acceptsImageOption(it) }
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

internal fun inferOpenAIModelType(modelId: String): ModelType = inferModelTypeFromId(modelId)
