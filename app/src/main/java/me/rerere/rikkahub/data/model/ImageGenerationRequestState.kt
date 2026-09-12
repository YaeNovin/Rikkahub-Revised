package me.rerere.rikkahub.data.model

import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.ImageGenerationConstraints
import me.rerere.ai.provider.GeminiImageGenerationOptions
import me.rerere.ai.provider.Model

/** Shared parameter state used by the full image page and compact chat panel. */
data class ImageGenerationRequestState(
    val prompt: String = "",
    val size: String = "auto",
    val quality: String? = null,
    val outputFormat: String? = null,
    val background: String? = null,
    val outputCompression: Int = 100,
    val resolution: String? = null,
    val thinkingLevel: String? = null,
    val count: Int = 1,
    val seed: Long? = null,
    val steps: Int? = null,
    val guidanceScale: Float? = null,
    val negativePrompt: String = "",
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
) {
    fun normalized() = copy(count = count.coerceAtLeast(1))
}

/** Remove options that the selected provider/model explicitly cannot accept. */
fun ImageGenerationRequestState.constrained(constraints: ImageGenerationConstraints): ImageGenerationRequestState {
    val sizes = constraints.supportedSizes
    val normalizedSize = when {
        !constraints.supportsSize -> "auto"
        sizes.isNullOrEmpty() || size in sizes || (constraints.supportsCustomSize && size != "auto") -> size
        else -> sizes.firstOrNull() ?: "auto"
    }
    return normalized().copy(
        count = if (constraints.supportsOutputCount) {
            count.coerceIn(1, constraints.maxOutputImages.coerceAtLeast(1))
        } else 1,
        size = normalizedSize,
        quality = quality.takeIf { it != null && it in constraints.supportedQualityValues },
        outputFormat = outputFormat.takeIf { it != null && it in constraints.supportedOutputFormats },
        background = background.takeIf { it != null && it in constraints.supportedBackgroundValues },
        outputCompression = outputCompression.coerceIn(0, 100).takeIf { constraints.supportsOutputCompression } ?: 100,
        resolution = resolution.takeIf { it != null && it in constraints.supportedResolutionValues },
        thinkingLevel = thinkingLevel.takeIf { it != null && it in constraints.supportedThinkingValues },
        seed = seed.takeIf { value -> value != null && constraints.seedRange?.contains(value) == true },
        steps = steps.takeIf { value -> value != null && constraints.stepsRange?.contains(value) == true },
        guidanceScale = guidanceScale.takeIf { value -> value != null && constraints.guidanceScaleRange?.contains(value) == true },
        negativePrompt = negativePrompt.takeIf { constraints.supportsNegativePrompt }.orEmpty(),
        promptEnhancement = promptEnhancement.takeIf { constraints.promptEnhancementRequestField != null },
        promptEnhancementMode = promptEnhancementMode.takeIf { it != null && it in constraints.supportedPromptEnhancementModes },
        imageThinking = imageThinking.takeIf { constraints.supportsImageThinking },
        watermark = watermark.takeIf { constraints.supportsWatermark },
        moderation = moderation.takeIf { it != null && it in constraints.supportedModerationValues },
        inputFidelity = inputFidelity.takeIf { it != null && it in constraints.supportedInputFidelityValues },
        safetyTolerance = safetyTolerance.takeIf { value -> value != null && constraints.safetyToleranceRange?.contains(value) == true },
        sampler = sampler.takeIf { it != null && it in constraints.supportedSamplerValues },
        stylePreset = stylePreset.takeIf { it != null && it in constraints.supportedStylePresetValues },
        sequentialImageGeneration = sequentialImageGeneration.takeIf { constraints.sequentialImageMax != null },
        sequentialMaxImages = sequentialMaxImages.coerceIn(1, constraints.sequentialImageMax ?: 15),
        promptOptimizationMode = promptOptimizationMode.takeIf { it != null && it in constraints.supportedPromptOptimizationModes },
        geminiOptions = geminiOptions.copy(
            includeTextResponse = geminiOptions.includeTextResponse && constraints.supportsTextResponse,
            webSearchGrounding = geminiOptions.webSearchGrounding && constraints.supportsWebSearchGrounding,
            imageSearchGrounding = geminiOptions.imageSearchGrounding && constraints.supportsImageSearchGrounding,
            safetySettings = geminiOptions.safetySettings,
        ),
    )
}

fun ImageGenerationRequestState.toParams(model: Model): ImageGenerationParams {
    val state = normalized()
    return ImageGenerationParams(
        model = model,
        prompt = state.prompt.trim(),
        numOfImages = state.count,
        size = state.size,
        quality = state.quality,
        outputFormat = state.outputFormat,
        background = state.background,
        outputCompression = state.outputCompression,
        resolution = state.resolution,
        thinkingLevel = state.thinkingLevel,
        seed = state.seed,
        steps = state.steps,
        guidanceScale = state.guidanceScale,
        negativePrompt = state.negativePrompt.takeIf(String::isNotBlank),
        promptEnhancement = state.promptEnhancement,
        promptEnhancementMode = state.promptEnhancementMode,
        imageThinking = state.imageThinking,
        watermark = state.watermark,
        moderation = state.moderation,
        safetyTolerance = state.safetyTolerance,
        sampler = state.sampler,
        stylePreset = state.stylePreset,
        sequentialImageGeneration = state.sequentialImageGeneration,
        sequentialMaxImages = state.sequentialMaxImages,
        promptOptimizationMode = state.promptOptimizationMode,
        geminiOptions = state.geminiOptions,
        customHeaders = model.customHeaders,
        customBody = model.customBodies,
    )
}

fun ImageGenerationRequestState.toEditParams(model: Model, images: List<String>): me.rerere.ai.provider.ImageEditParams {
    val state = normalized()
    return me.rerere.ai.provider.ImageEditParams(
        model = model,
        prompt = state.prompt.trim(),
        images = images,
        numOfImages = state.count,
        size = state.size,
        quality = state.quality,
        outputFormat = state.outputFormat,
        background = state.background,
        outputCompression = state.outputCompression,
        resolution = state.resolution,
        thinkingLevel = state.thinkingLevel,
        seed = state.seed,
        steps = state.steps,
        guidanceScale = state.guidanceScale,
        negativePrompt = state.negativePrompt.takeIf(String::isNotBlank),
        promptEnhancement = state.promptEnhancement,
        promptEnhancementMode = state.promptEnhancementMode,
        imageThinking = state.imageThinking,
        watermark = state.watermark,
        moderation = state.moderation,
        inputFidelity = state.inputFidelity,
        safetyTolerance = state.safetyTolerance,
        sampler = state.sampler,
        stylePreset = state.stylePreset,
        sequentialImageGeneration = state.sequentialImageGeneration,
        sequentialMaxImages = state.sequentialMaxImages,
        promptOptimizationMode = state.promptOptimizationMode,
        geminiOptions = state.geminiOptions,
        customHeaders = model.customHeaders,
        customBody = model.customBodies,
    )
}
