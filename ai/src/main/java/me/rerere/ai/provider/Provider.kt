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
    val supportsOutputCount: Boolean = true,
    val maxReferenceImages: Int = 0,
    val supportsSize: Boolean = true,
    val supportedSizes: Set<String>? = null,
    val supportsCustomSize: Boolean = true,
    val groupSizesByAspectRatio: Boolean = false,
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
    val supportedThinkingValues: Set<String> = emptySet(),
    val seedRange: LongRange? = null,
    val stepsRange: IntRange? = null,
    val defaultSteps: Int? = null,
    val guidanceScaleRange: ClosedFloatingPointRange<Float>? = null,
    val defaultGuidanceScale: Float? = null,
    val guidanceScaleRequestField: String? = null,
    val supportsNegativePrompt: Boolean = false,
    val promptEnhancementRequestField: String? = null,
    val supportedPromptEnhancementModes: Set<String> = emptySet(),
    val supportsImageThinking: Boolean = false,
    val supportsWatermark: Boolean = false,
    val supportedModerationValues: Set<String> = emptySet(),
    val supportedInputFidelityValues: Set<String> = emptySet(),
    val safetyToleranceRange: IntRange? = null,
    val defaultSafetyTolerance: Int? = null,
    val supportedSamplerValues: Set<String> = emptySet(),
    val supportedStylePresetValues: Set<String> = emptySet(),
    val sequentialImageMax: Int? = null,
    val supportedPromptOptimizationModes: Set<String> = emptySet(),
    val supportsTextResponse: Boolean = false,
    val supportsSafetySettings: Boolean = false,
    val supportsWebSearchGrounding: Boolean = false,
    val supportsImageSearchGrounding: Boolean = false,
    val blockedImageOptionKeys: Set<String> = emptySet(),
    val usesJsonImageEdit: Boolean = false,
    val usesGenerationEndpointForEdit: Boolean = false,
)

enum class ProviderRequestChannel {
    OPENAI_API,
    XAI_API,
    ANTHROPIC_API,
    GOOGLE_AI_STUDIO,
    VERTEX_AI,
    COMPATIBLE_ENDPOINT,
}

enum class ProviderRequestOperation {
    TEXT_GENERATION,
    STREAM_TEXT,
    IMAGE_GENERATION,
    IMAGE_EDIT,
}

data class ProviderRequestDiagnostics(
    val provider: String,
    val model: String,
    val channel: ProviderRequestChannel,
    val operation: ProviderRequestOperation,
    val parameters: Map<String, String>,
    val requestId: String? = null,
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
    val openAIOptions: OpenAIGenerationOptions = OpenAIGenerationOptions(),
    val grokOptions: GrokGenerationOptions = GrokGenerationOptions(),
    val qwenOptions: QwenGenerationOptions = QwenGenerationOptions(),
    val deepSeekOptions: DeepSeekGenerationOptions = DeepSeekGenerationOptions(),
    val geminiOptions: GeminiGenerationOptions = GeminiGenerationOptions(),
    val claudeOptions: ClaudeGenerationOptions = ClaudeGenerationOptions(),
    val customHeaders: List<CustomHeader> = emptyList(),
    val customBody: List<CustomBody> = emptyList(),
    val requestId: String? = null,
)

@Serializable
data class ClaudeGenerationOptions(
    val serviceTier: ClaudeServiceTier = ClaudeServiceTier.DEFAULT,
    val inferenceGeo: ClaudeInferenceGeo = ClaudeInferenceGeo.DEFAULT,
    val parallelToolCalls: ClaudeParallelToolCalls = ClaudeParallelToolCalls.AUTO,
    val toolChoice: ClaudeToolChoice = ClaudeToolChoice.DEFAULT,
    val stopSequences: List<String> = emptyList(),
    val topK: Int? = null,
    val thinkingDisplay: ClaudeThinkingDisplay = ClaudeThinkingDisplay.SUMMARIZED,
    val responseFormat: ClaudeResponseFormat = ClaudeResponseFormat.AUTO,
    val responseJsonSchema: String = "",
)

@Serializable
enum class ClaudeServiceTier(val apiValue: String?) {
    DEFAULT(null),
    AUTO("auto"),
    STANDARD_ONLY("standard_only"),
}

@Serializable
enum class ClaudeInferenceGeo(val apiValue: String?) {
    DEFAULT(null),
    GLOBAL("global"),
    US("us"),
}

@Serializable
enum class ClaudeParallelToolCalls(val disableParallelToolUse: Boolean?) {
    AUTO(null),
    ENABLED(false),
    DISABLED(true),
}

@Serializable
enum class ClaudeToolChoice(val apiValue: String?) {
    DEFAULT(null),
    AUTO("auto"),
    ANY("any"),
    NONE("none"),
}

@Serializable
enum class ClaudeThinkingDisplay(val apiValue: String?) {
    DEFAULT(null),
    SUMMARIZED("summarized"),
    OMITTED("omitted"),
}

@Serializable
enum class ClaudeResponseFormat(val apiValue: String?) {
    AUTO(null),
    JSON_SCHEMA("json_schema"),
}

@Serializable
data class GrokGenerationOptions(
    val serviceTier: GrokServiceTier = GrokServiceTier.AUTO,
    val parallelToolCalls: GrokParallelToolCalls = GrokParallelToolCalls.AUTO,
    val toolChoice: GrokToolChoice = GrokToolChoice.DEFAULT,
    val seed: Long? = null,
    val stopSequences: List<String> = emptyList(),
    val responseFormat: GrokResponseFormat = GrokResponseFormat.AUTO,
    val responseJsonSchema: String = "",
    val responseSchemaName: String = "response",
    val presencePenalty: Float? = null,
    val frequencyPenalty: Float? = null,
    val minP: Float? = null,
    val topK: Int? = null,
    val maxTurns: Int? = null,
)

@Serializable
enum class GrokServiceTier(val apiValue: String?) {
    AUTO(null),
    DEFAULT("default"),
    PRIORITY("priority"),
}

@Serializable
enum class GrokParallelToolCalls(val apiValue: Boolean?) {
    AUTO(null),
    ENABLED(true),
    DISABLED(false),
}

@Serializable
enum class GrokToolChoice(val apiValue: String?) {
    DEFAULT(null),
    AUTO("auto"),
    NONE("none"),
    REQUIRED("required"),
}

@Serializable
enum class GrokResponseFormat(val apiValue: String?) {
    AUTO(null),
    TEXT("text"),
    JSON_OBJECT("json_object"),
    JSON_SCHEMA("json_schema"),
}

@Serializable
data class QwenGenerationOptions(
    val parallelToolCalls: QwenOptionalToggle = QwenOptionalToggle.DEFAULT,
    val toolChoice: QwenToolChoice = QwenToolChoice.DEFAULT,
    val topK: Int? = null,
    val repetitionPenalty: Float? = null,
    val presencePenalty: Float? = null,
    val seed: Long? = null,
    val stopSequences: List<String> = emptyList(),
    val preserveThinking: QwenOptionalToggle = QwenOptionalToggle.DEFAULT,
    val toolStream: QwenOptionalToggle = QwenOptionalToggle.DEFAULT,
    val highResolutionVision: QwenOptionalToggle = QwenOptionalToggle.DEFAULT,
    val responseFormat: QwenResponseFormat = QwenResponseFormat.AUTO,
    val responseSchemaName: String = "response",
    val responseJsonSchema: String = "",
)

@Serializable
enum class QwenOptionalToggle(val apiValue: Boolean?) {
    DEFAULT(null),
    ENABLED(true),
    DISABLED(false),
}

@Serializable
enum class QwenToolChoice(val apiValue: String?) {
    DEFAULT(null),
    AUTO("auto"),
    NONE("none"),
    REQUIRED("required"),
}

@Serializable
enum class QwenResponseFormat(val apiValue: String?) {
    AUTO(null),
    TEXT("text"),
    JSON_OBJECT("json_object"),
    JSON_SCHEMA("json_schema"),
}

@Serializable
data class DeepSeekGenerationOptions(
    val toolChoice: DeepSeekToolChoice = DeepSeekToolChoice.DEFAULT,
    val responseFormat: DeepSeekResponseFormat = DeepSeekResponseFormat.AUTO,
    val stopSequences: List<String> = emptyList(),
    val logProbabilities: DeepSeekOptionalToggle = DeepSeekOptionalToggle.DEFAULT,
    val topLogProbs: Int? = null,
    val userId: String = "",
    val imageDetail: DeepSeekImageDetail = DeepSeekImageDetail.AUTO,
)

@Serializable
enum class DeepSeekOptionalToggle(val apiValue: Boolean?) {
    DEFAULT(null),
    ENABLED(true),
    DISABLED(false),
}

@Serializable
enum class DeepSeekToolChoice(val apiValue: String?) {
    DEFAULT(null),
    AUTO("auto"),
    NONE("none"),
    REQUIRED("required"),
}

@Serializable
enum class DeepSeekResponseFormat(val apiValue: String?) {
    AUTO(null),
    TEXT("text"),
    JSON_OBJECT("json_object"),
}

@Serializable
enum class DeepSeekImageDetail(val apiValue: String?) {
    AUTO(null),
    LOW("low"),
    HIGH("high"),
    ORIGINAL("original"),
}

@Serializable
data class OpenAIGenerationOptions(
    val verbosity: OpenAITextVerbosity = OpenAITextVerbosity.AUTO,
    val serviceTier: OpenAIServiceTier = OpenAIServiceTier.AUTO,
    val parallelToolCalls: OpenAIParallelToolCalls = OpenAIParallelToolCalls.AUTO,
    val toolChoice: OpenAIToolChoice = OpenAIToolChoice.DEFAULT,
    val reasoningSummary: OpenAIReasoningSummary = OpenAIReasoningSummary.AUTO,
    val reasoningContext: OpenAIReasoningContext = OpenAIReasoningContext.AUTO,
    val reasoningMode: OpenAIReasoningMode = OpenAIReasoningMode.STANDARD,
    val maxToolCalls: Int? = null,
)

@Serializable
enum class OpenAITextVerbosity(val apiValue: String?) {
    AUTO(null),
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high"),
}

@Serializable
enum class OpenAIServiceTier(val apiValue: String?) {
    AUTO(null),
    DEFAULT("default"),
    FLEX("flex"),
    FAST("fast"),
    ULTRAFAST("ultrafast"),
}

@Serializable
enum class OpenAIParallelToolCalls(val apiValue: Boolean?) {
    AUTO(null),
    ENABLED(true),
    DISABLED(false),
}

@Serializable
enum class OpenAIToolChoice(val apiValue: String?) {
    DEFAULT(null),
    AUTO("auto"),
    NONE("none"),
    REQUIRED("required"),
}

@Serializable
enum class OpenAIReasoningSummary(val apiValue: String?) {
    DISABLED(null),
    AUTO("auto"),
    CONCISE("concise"),
    DETAILED("detailed"),
}

@Serializable
enum class OpenAIReasoningContext(val apiValue: String?) {
    AUTO(null),
    CURRENT_TURN("current_turn"),
    ALL_TURNS("all_turns"),
}

@Serializable
enum class OpenAIReasoningMode(val apiValue: String?) {
    STANDARD(null),
    PRO("pro"),
}

@Serializable
data class GeminiGenerationOptions(
    val includeThoughts: Boolean = true,
    val mediaResolution: GeminiMediaResolution = GeminiMediaResolution.AUTO,
    val seed: Int? = null,
    val stopSequences: List<String> = emptyList(),
    val responseMimeType: GeminiResponseMimeType = GeminiResponseMimeType.AUTO,
    val responseJsonSchema: String = "",
    val presencePenalty: Float? = null,
    val frequencyPenalty: Float? = null,
    val safetySettings: GeminiSafetySettings = GeminiSafetySettings(),
)

@Serializable
enum class GeminiMediaResolution(val apiValue: String?) {
    AUTO(null),
    LOW("MEDIA_RESOLUTION_LOW"),
    MEDIUM("MEDIA_RESOLUTION_MEDIUM"),
    HIGH("MEDIA_RESOLUTION_HIGH"),
    ULTRA_HIGH("MEDIA_RESOLUTION_ULTRA_HIGH"),
}

@Serializable
enum class GeminiResponseMimeType(val apiValue: String?) {
    AUTO(null),
    TEXT("text/plain"),
    JSON("application/json"),
    ENUM("text/x.enum"),
}

@Serializable
enum class GeminiSafetyThreshold(val apiValue: String?) {
    DEFAULT(null),
    OFF("OFF"),
    BLOCK_NONE("BLOCK_NONE"),
    BLOCK_ONLY_HIGH("BLOCK_ONLY_HIGH"),
    BLOCK_MEDIUM_AND_ABOVE("BLOCK_MEDIUM_AND_ABOVE"),
    BLOCK_LOW_AND_ABOVE("BLOCK_LOW_AND_ABOVE"),
}

@Serializable
data class GeminiSafetySettings(
    val harassment: GeminiSafetyThreshold = GeminiSafetyThreshold.OFF,
    val hateSpeech: GeminiSafetyThreshold = GeminiSafetyThreshold.OFF,
    val sexuallyExplicit: GeminiSafetyThreshold = GeminiSafetyThreshold.OFF,
    val dangerousContent: GeminiSafetyThreshold = GeminiSafetyThreshold.OFF,
)

@Serializable
data class GeminiImageGenerationOptions(
    val includeTextResponse: Boolean = true,
    val webSearchGrounding: Boolean = false,
    val imageSearchGrounding: Boolean = false,
    val safetySettings: GeminiSafetySettings = GeminiSafetySettings(
        harassment = GeminiSafetyThreshold.DEFAULT,
        hateSpeech = GeminiSafetyThreshold.DEFAULT,
        sexuallyExplicit = GeminiSafetyThreshold.DEFAULT,
        dangerousContent = GeminiSafetyThreshold.DEFAULT,
    ),
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
    val thinkingLevel: String? = null,
    val seed: Long? = null,
    val steps: Int? = null,
    val guidanceScale: Float? = null,
    val negativePrompt: String? = null,
    val promptEnhancement: Boolean? = null,
    val promptEnhancementMode: String? = null,
    val imageThinking: Boolean? = null,
    val watermark: Boolean? = null,
    val moderation: String? = null,
    val safetyTolerance: Int? = null,
    val sampler: String? = null,
    val stylePreset: String? = null,
    val sequentialImageGeneration: Boolean? = null,
    val sequentialMaxImages: Int = 15,
    val promptOptimizationMode: String? = null,
    val geminiOptions: GeminiImageGenerationOptions = GeminiImageGenerationOptions(),
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
    val thinkingLevel: String? = null,
    val seed: Long? = null,
    val steps: Int? = null,
    val guidanceScale: Float? = null,
    val negativePrompt: String? = null,
    val promptEnhancement: Boolean? = null,
    val promptEnhancementMode: String? = null,
    val imageThinking: Boolean? = null,
    val watermark: Boolean? = null,
    val moderation: String? = null,
    val inputFidelity: String? = null,
    val safetyTolerance: Int? = null,
    val sampler: String? = null,
    val stylePreset: String? = null,
    val sequentialImageGeneration: Boolean? = null,
    val sequentialMaxImages: Int = 15,
    val promptOptimizationMode: String? = null,
    val geminiOptions: GeminiImageGenerationOptions = GeminiImageGenerationOptions(),
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
