package me.rerere.ai.provider

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.Tool
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.ImageGenSize
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage

// 提供商实现
// 采用无状态设计，使用时除了需要传入需要的参数外，还需要传入provider setting作为参数
interface Provider<T : ProviderSetting> {
    val capabilities: Set<ProviderCapability>
        get() = emptySet()

    fun supports(capability: ProviderCapability): Boolean = capability in capabilities

    suspend fun listModels(providerSetting: T): List<Model>

    suspend fun getBalance(providerSetting: T): String {
        return "TODO"
    }

    suspend fun generateText(
        providerSetting: T,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): TextGenerationResult

    suspend fun streamText(
        providerSetting: T,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<StreamChunk>

    suspend fun generateEmbedding(
        providerSetting: T,
        params: EmbeddingGenerationParams,
    ): EmbeddingGenerationResult {
        error("Embedding generation is not supported")
    }

    fun imageGenerationConstraints(
        providerSetting: ProviderSetting,
        model: Model,
    ): ImageGenerationConstraints = ImageGenerationConstraints(
        supportsGeneration = supports(ProviderCapability.IMAGE_GENERATION),
        supportsEdit = supports(ProviderCapability.IMAGE_EDIT),
        supportsPartialImages = supports(ProviderCapability.PARTIAL_IMAGES),
    )

    suspend fun generateImage(
        providerSetting: ProviderSetting,
        params: ImageGenerationParams,
    ): Flow<ImageGenerationItem> {
        error("Image generation is not supported")
    }

    suspend fun editImage(
        providerSetting: ProviderSetting,
        params: ImageEditParams,
    ): Flow<ImageGenerationItem> {
        error("Image edit is not supported")
    }
}

enum class ProviderCapability {
    BALANCE,
    IMAGE_GENERATION,
    IMAGE_EDIT,
    PARTIAL_IMAGES,
}

data class ImageGenerationConstraints(
    val supportsGeneration: Boolean,
    val supportsEdit: Boolean,
    val supportsPartialImages: Boolean,
    val maxOutputImages: Int = 1,
    val maxReferenceImages: Int = 0,
    val supportsSize: Boolean = true,
    val supportedSizes: Set<String>? = null,
    val supportsCustomSize: Boolean = true,
    val customSizeMultiple: Int? = null,
    val customSizeMaxDimension: Int? = null,
    val customSizeMinPixels: Long? = null,
    val customSizeMaxPixels: Long? = null,
    val customSizeMaxAspectRatio: Int? = null,
    val sizeRequestField: String = "size",
    val supportedQualityValues: Set<String> = emptySet(),
    val supportedOutputFormats: Set<String> = emptySet(),
    val supportedBackgroundValues: Set<String> = emptySet(),
    val supportsOutputCompression: Boolean = false,
    val supportedResolutionValues: Set<String> = emptySet(),
    val blockedImageOptionKeys: Set<String> = emptySet(),
    val usesJsonImageEdit: Boolean = false,
)

@Serializable
data class TextGenerationResult(
    val id: String,
    val model: String,
    val message: UIMessage,
    val finishReason: String? = null,
    val usage: TokenUsage? = null,
)

@Serializable
data class TextGenerationParams(
    val model: Model,
    val temperature: Float? = null,
    val topP: Float? = null,
    val maxTokens: Int? = null,
    val tools: List<Tool> = emptyList(),
    val reasoningLevel: ReasoningLevel = ReasoningLevel.OFF,
    val customHeaders: List<CustomHeader> = emptyList(),
    val customBody: List<CustomBody> = emptyList(),
)

@Serializable
data class ImageGenerationParams(
    val model: Model,
    val prompt: String,
    val numOfImages: Int = 1,
    val size: String = ImageGenSize.AUTO.value,
    val partialImages: Int = 2,
    val quality: String? = null,
    val outputFormat: String? = null,
    val background: String? = null,
    val outputCompression: Int = 100,
    val resolution: String? = null,
    val customHeaders: List<CustomHeader> = emptyList(),
    val customBody: List<CustomBody> = emptyList(),
)

@Serializable
data class ImageEditParams(
    val model: Model,
    val prompt: String,
    val images: List<String>,
    val numOfImages: Int = 1,
    val size: String = ImageGenSize.AUTO.value,
    val partialImages: Int = 2,
    val quality: String? = null,
    val outputFormat: String? = null,
    val background: String? = null,
    val outputCompression: Int = 100,
    val resolution: String? = null,
    val customHeaders: List<CustomHeader> = emptyList(),
    val customBody: List<CustomBody> = emptyList(),
)

@Serializable
data class EmbeddingGenerationParams(
    val model: Model,
    val input: List<String>,
    val images: List<EmbeddingImageInput> = emptyList(),
    val dimensions: Int? = null,
    val customHeaders: List<CustomHeader> = emptyList(),
    val customBody: List<CustomBody> = emptyList(),
)

@Serializable
data class EmbeddingImageInput(
    val mimeType: String,
    val base64: String,
)

@Serializable
data class EmbeddingGenerationResult(
    val model: String,
    val embeddings: List<List<Float>>,
)

@Serializable
data class CustomHeader(
    val name: String,
    val value: String
)

@Serializable
data class CustomBody(
    val key: String,
    val value: JsonElement
)

fun Model.usesVolcengineMultimodalEmbeddingApi(): Boolean =
    modelId.startsWith("doubao-embedding-vision", ignoreCase = true)
