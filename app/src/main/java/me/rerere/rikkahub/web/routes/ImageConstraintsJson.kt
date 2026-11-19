package me.rerere.rikkahub.web.routes

import kotlinx.serialization.json.*
import me.rerere.ai.provider.ImageGenerationConstraints

internal fun imageConstraintsJson(c: ImageGenerationConstraints) = buildJsonObject {
    put("supportsGeneration", c.supportsGeneration)
    put("supportsEdit", c.supportsEdit)
    put("supportsPartialImages", c.supportsPartialImages)
    put("maxOutputImages", c.maxOutputImages)
    put("supportsOutputCount", c.supportsOutputCount)
    put("maxReferenceImages", c.maxReferenceImages)
    put("supportsSize", c.supportsSize)
    put("supportedSizes", JsonArray(c.supportedSizes?.map { JsonPrimitive(it) } ?: emptyList()))
    put("supportsCustomSize", c.supportsCustomSize)
    put("groupSizesByAspectRatio", c.groupSizesByAspectRatio)
    c.customSizeMultiple?.let { put("customSizeMultiple", it) }
    c.customSizeMaxDimension?.let { put("customSizeMaxDimension", it) }
    c.customSizeMinPixels?.let { put("customSizeMinPixels", it) }
    c.customSizeMaxPixels?.let { put("customSizeMaxPixels", it) }
    c.customSizeMaxAspectRatio?.let { put("customSizeMaxAspectRatio", it) }
    put("sizeRequestField", c.sizeRequestField)
    put("supportedQualityValues", JsonArray(c.supportedQualityValues.map { JsonPrimitive(it) }))
    put("supportedOutputFormats", JsonArray(c.supportedOutputFormats.map { JsonPrimitive(it) }))
    put("supportedBackgroundValues", JsonArray(c.supportedBackgroundValues.map { JsonPrimitive(it) }))
    put("supportsOutputCompression", c.supportsOutputCompression)
    put("supportedResolutionValues", JsonArray(c.supportedResolutionValues.map { JsonPrimitive(it) }))
    put("supportedThinkingValues", JsonArray(c.supportedThinkingValues.map { JsonPrimitive(it) }))
    c.seedRange?.let { put("seedRange", buildJsonObject { put("min", it.first); put("max", it.last) }) }
    c.stepsRange?.let { put("stepsRange", buildJsonObject { put("min", it.first); put("max", it.last) }) }
    c.defaultSteps?.let { put("defaultSteps", it) }
    c.guidanceScaleRange?.let { put("guidanceScaleRange", buildJsonObject { put("min", it.start); put("max", it.endInclusive) }) }
    c.defaultGuidanceScale?.let { put("defaultGuidanceScale", it) }
    c.guidanceScaleRequestField?.let { put("guidanceScaleRequestField", it) }
    put("supportsNegativePrompt", c.supportsNegativePrompt)
    c.promptEnhancementRequestField?.let { put("promptEnhancementRequestField", it) }
    put("supportedPromptEnhancementModes", JsonArray(c.supportedPromptEnhancementModes.map { JsonPrimitive(it) }))
    put("supportsImageThinking", c.supportsImageThinking)
    put("supportsWatermark", c.supportsWatermark)
    put("supportedModerationValues", JsonArray(c.supportedModerationValues.map { JsonPrimitive(it) }))
    put("supportedInputFidelityValues", JsonArray(c.supportedInputFidelityValues.map { JsonPrimitive(it) }))
    c.safetyToleranceRange?.let { put("safetyToleranceRange", buildJsonObject { put("min", it.first); put("max", it.last) }) }
    c.defaultSafetyTolerance?.let { put("defaultSafetyTolerance", it) }
    put("supportedSamplerValues", JsonArray(c.supportedSamplerValues.map { JsonPrimitive(it) }))
    put("supportedStylePresetValues", JsonArray(c.supportedStylePresetValues.map { JsonPrimitive(it) }))
    c.sequentialImageMax?.let { put("sequentialImageMax", it) }
    put("supportedPromptOptimizationModes", JsonArray(c.supportedPromptOptimizationModes.map { JsonPrimitive(it) }))
    put("supportsTextResponse", c.supportsTextResponse)
    put("supportsSafetySettings", c.supportsSafetySettings)
    put("supportsWebSearchGrounding", c.supportsWebSearchGrounding)
    put("supportsImageSearchGrounding", c.supportsImageSearchGrounding)
    put("blockedImageOptionKeys", JsonArray(c.blockedImageOptionKeys.map { JsonPrimitive(it) }))
    put("usesJsonImageEdit", c.usesJsonImageEdit)
    put("usesGenerationEndpointForEdit", c.usesGenerationEndpointForEdit)
}
