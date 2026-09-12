package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.VideoGenerationConstraints
import me.rerere.ai.provider.VideoGenerationInputMode
import me.rerere.ai.provider.VideoGenerationParams
import me.rerere.ai.provider.VideoReferenceImage
import me.rerere.ai.provider.constrained

/** Provider-neutral state shared by the future video page and persisted tasks. */
@Serializable
data class VideoGenerationRequestState(
    val prompt: String = "",
    val inputMode: VideoGenerationInputMode = VideoGenerationInputMode.TEXT_TO_VIDEO,
    val referenceImages: List<VideoReferenceImage> = emptyList(),
    val referenceVideoUrl: String? = null,
    val durationSeconds: Int? = null,
    val aspectRatio: String? = null,
    val resolution: String? = null,
    val frameRate: Int? = null,
    val seed: Long? = null,
    val negativePrompt: String = "",
    val generateAudio: Boolean? = null,
    val watermark: Boolean? = null,
    val cameraFixed: Boolean? = null,
    val returnLastFrame: Boolean? = null,
    val promptEnhancement: Boolean? = null,
    val fastPretreatment: Boolean? = null,
    val outputCount: Int = 1,
) {
    fun toParams(
        model: Model,
        constraints: VideoGenerationConstraints,
    ): VideoGenerationParams = VideoGenerationParams(
        model = model,
        prompt = prompt,
        inputMode = inputMode,
        referenceImages = referenceImages.map { reference ->
            if (reference.role == me.rerere.ai.provider.VideoReferenceImageRole.REFERENCE &&
                reference.role !in constraints.supportedReferenceImageRoles &&
                me.rerere.ai.provider.VideoReferenceImageRole.FIRST_FRAME in constraints.supportedReferenceImageRoles
            ) reference.copy(role = me.rerere.ai.provider.VideoReferenceImageRole.FIRST_FRAME) else reference
        },
        referenceVideoUrl = referenceVideoUrl,
        durationSeconds = durationSeconds,
        aspectRatio = aspectRatio,
        resolution = resolution,
        frameRate = frameRate,
        seed = seed,
        negativePrompt = negativePrompt,
        generateAudio = generateAudio,
        watermark = watermark,
        cameraFixed = cameraFixed,
        returnLastFrame = returnLastFrame,
        promptEnhancement = promptEnhancement,
        fastPretreatment = fastPretreatment,
        outputCount = outputCount,
        customHeaders = model.customHeaders,
        customBody = model.customBodies,
    ).constrained(constraints)
}
