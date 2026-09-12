package me.rerere.ai.provider

import kotlinx.serialization.Serializable
import kotlin.math.abs

@Serializable
enum class VideoGenerationInputMode {
    TEXT_TO_VIDEO,
    IMAGE_TO_VIDEO,
    KEYFRAMES_TO_VIDEO,
    VIDEO_TO_VIDEO,
}

@Serializable
enum class VideoReferenceImageRole {
    REFERENCE,
    FIRST_FRAME,
    LAST_FRAME,
}

@Serializable
data class VideoReferenceImage(
    val url: String,
    val role: VideoReferenceImageRole = VideoReferenceImageRole.REFERENCE,
)

data class VideoGenerationConstraints(
    val supportsGeneration: Boolean,
    val supportsCancellation: Boolean = false,
    val supportedInputModes: Set<VideoGenerationInputMode> = setOf(
        VideoGenerationInputMode.TEXT_TO_VIDEO,
    ),
    val supportedDurationsSeconds: Set<Int> = emptySet(),
    val customDurationRangeSeconds: IntRange? = null,
    val supportedAspectRatios: Set<String> = emptySet(),
    val supportedResolutions: Set<String> = emptySet(),
    val supportedFrameRates: Set<Int> = emptySet(),
    val maxReferenceImages: Int = 0,
    val supportedReferenceImageRoles: Set<VideoReferenceImageRole> = emptySet(),
    val supportsLastFrame: Boolean = false,
    val supportsReferenceVideo: Boolean = false,
    val supportsNegativePrompt: Boolean = false,
    val supportsSeed: Boolean = false,
    val supportsAudio: Boolean = false,
    val supportsWatermark: Boolean = false,
    val supportsCameraFixed: Boolean = false,
    val supportsReturnLastFrame: Boolean = false,
    val supportsPromptEnhancement: Boolean = false,
    val supportsFastPretreatment: Boolean = false,
    val supportsOutputCount: Boolean = false,
    val maxOutputVideos: Int = 1,
    val pollingIntervalMillis: LongRange = 2_000L..30_000L,
) {
    init {
        require(supportedDurationsSeconds.all { it > 0 }) {
            "Supported video durations must be positive"
        }
        require(customDurationRangeSeconds == null || customDurationRangeSeconds.first > 0) {
            "Custom video duration range must be positive"
        }
        require(maxReferenceImages >= 0) { "Maximum reference image count cannot be negative" }
        require(maxOutputVideos >= 1) { "Maximum output video count must be positive" }
        require(pollingIntervalMillis.first > 0L) { "Video polling interval must be positive" }
    }
}

@Serializable
data class VideoGenerationParams(
    val model: Model,
    val prompt: String,
    val inputMode: VideoGenerationInputMode = VideoGenerationInputMode.TEXT_TO_VIDEO,
    val referenceImages: List<VideoReferenceImage> = emptyList(),
    val referenceVideoUrl: String? = null,
    val durationSeconds: Int? = null,
    val aspectRatio: String? = null,
    val resolution: String? = null,
    val frameRate: Int? = null,
    val seed: Long? = null,
    val negativePrompt: String? = null,
    val generateAudio: Boolean? = null,
    val watermark: Boolean? = null,
    val cameraFixed: Boolean? = null,
    val returnLastFrame: Boolean? = null,
    val promptEnhancement: Boolean? = null,
    val fastPretreatment: Boolean? = null,
    val outputCount: Int = 1,
    val customHeaders: List<CustomHeader> = emptyList(),
    val customBody: List<CustomBody> = emptyList(),
)

@Serializable
enum class VideoGenerationTaskStatus {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELED,
    EXPIRED,
}

@Serializable
data class VideoGenerationOutput(
    val downloadUrl: String,
    val mimeType: String = "video/mp4",
    val fileName: String? = null,
    val thumbnailUrl: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val durationMillis: Long? = null,
    val downloadUrlExpiresAtEpochMillis: Long? = null,
) {
    init {
        require(downloadUrl.isNotBlank()) { "Video output URL cannot be blank" }
        require(mimeType.isNotBlank()) { "Video output MIME type cannot be blank" }
        require(width == null || width > 0) { "Video output width must be positive" }
        require(height == null || height > 0) { "Video output height must be positive" }
        require(durationMillis == null || durationMillis > 0L) {
            "Video output duration must be positive"
        }
    }
}

@Serializable
data class VideoGenerationError(
    val code: String? = null,
    val message: String,
    val retryable: Boolean = false,
)

@Serializable
data class VideoGenerationTaskSnapshot(
    val taskId: String,
    val status: VideoGenerationTaskStatus,
    val progressPercent: Int? = null,
    val outputs: List<VideoGenerationOutput> = emptyList(),
    val error: VideoGenerationError? = null,
    val retryAfterMillis: Long? = null,
    val createdAtEpochMillis: Long? = null,
    val updatedAtEpochMillis: Long? = null,
) {
    init {
        require(taskId.isNotBlank()) { "Video generation task id cannot be blank" }
        require(progressPercent == null || progressPercent in 0..100) {
            "Video generation progress must be between 0 and 100"
        }
        require(status != VideoGenerationTaskStatus.SUCCEEDED || outputs.isNotEmpty()) {
            "A successful video generation task must include an output"
        }
        require(status != VideoGenerationTaskStatus.FAILED || error != null) {
            "A failed video generation task must include an error"
        }
        require(retryAfterMillis == null || retryAfterMillis >= 0L) {
            "Video generation retry delay cannot be negative"
        }
    }
}

fun VideoGenerationParams.constrained(
    constraints: VideoGenerationConstraints,
): VideoGenerationParams {
    val mode = inputMode.takeIf { it in constraints.supportedInputModes }
        ?: constraints.supportedInputModes.firstOrNull()
        ?: VideoGenerationInputMode.TEXT_TO_VIDEO
    val duration = when {
        durationSeconds == null -> null
        durationSeconds in constraints.supportedDurationsSeconds -> durationSeconds
        constraints.customDurationRangeSeconds != null ->
            durationSeconds.coerceIn(constraints.customDurationRangeSeconds)
        constraints.supportedDurationsSeconds.isNotEmpty() ->
            constraints.supportedDurationsSeconds.minBy { abs(it - durationSeconds) }
        else -> null
    }
    val references = referenceImages
        .filterNot { it.role == VideoReferenceImageRole.LAST_FRAME && !constraints.supportsLastFrame }
        .filter {
            constraints.supportedReferenceImageRoles.isEmpty() ||
                it.role in constraints.supportedReferenceImageRoles
        }
        .filter { it.url.isNotBlank() }
        .take(constraints.maxReferenceImages.coerceAtLeast(0))
        .takeIf {
            mode == VideoGenerationInputMode.IMAGE_TO_VIDEO ||
                mode == VideoGenerationInputMode.KEYFRAMES_TO_VIDEO
        }
        .orEmpty()
    return copy(
        prompt = prompt.trim(),
        inputMode = mode,
        referenceImages = references,
        referenceVideoUrl = referenceVideoUrl
            ?.takeIf(String::isNotBlank)
            ?.takeIf {
                constraints.supportsReferenceVideo &&
                    mode == VideoGenerationInputMode.VIDEO_TO_VIDEO
            },
        durationSeconds = duration,
        aspectRatio = aspectRatio?.takeIf { it in constraints.supportedAspectRatios },
        resolution = resolution?.takeIf { it in constraints.supportedResolutions },
        frameRate = frameRate?.takeIf { it in constraints.supportedFrameRates },
        seed = seed.takeIf { constraints.supportsSeed },
        negativePrompt = negativePrompt?.trim()?.takeIf(String::isNotBlank)
            ?.takeIf { constraints.supportsNegativePrompt },
        generateAudio = generateAudio.takeIf { constraints.supportsAudio },
        watermark = watermark.takeIf { constraints.supportsWatermark },
        cameraFixed = cameraFixed.takeIf { constraints.supportsCameraFixed },
        returnLastFrame = returnLastFrame.takeIf { constraints.supportsReturnLastFrame },
        promptEnhancement = promptEnhancement.takeIf { constraints.supportsPromptEnhancement },
        fastPretreatment = fastPretreatment.takeIf { constraints.supportsFastPretreatment && promptEnhancement != false },
        outputCount = if (constraints.supportsOutputCount) {
            outputCount.coerceIn(1, constraints.maxOutputVideos.coerceAtLeast(1))
        } else {
            1
        },
    )
}
