package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderRequestChannel
import me.rerere.ai.provider.ProviderRequestDiagnostics
import me.rerere.ai.provider.ProviderRequestOperation
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.VideoGenerationConstraints
import me.rerere.ai.provider.VideoGenerationError
import me.rerere.ai.provider.VideoGenerationInputMode
import me.rerere.ai.provider.VideoGenerationOutput
import me.rerere.ai.provider.VideoGenerationParams
import me.rerere.ai.provider.VideoGenerationTaskSnapshot
import me.rerere.ai.provider.VideoGenerationTaskStatus
import me.rerere.ai.provider.VideoReferenceImageRole
import me.rerere.ai.provider.constrained
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl

internal fun ProviderSetting.OpenAI.supportsArkSeedance(model: Model): Boolean =
    baseUrl.isVolcengineArkBaseUrl() && model.type == ModelType.VIDEO &&
        (model.modelId.isSeedanceVideoModelId() || model.modelId.isArkEndpointId())

internal fun seedanceVideoGenerationConstraints(model: Model): VideoGenerationConstraints {
    val normalized = model.modelId.lowercase()
    val isSeedance15Pro = normalized.contains("seedance-1-5-pro") ||
        normalized.contains("seedance-1.5-pro") || normalized.contains("seedance_1_5_pro")
    val isSeedance2 = Regex("seedance[-_.]?2(?:[-_.]|$)").containsMatchIn(normalized)
    return VideoGenerationConstraints(
        supportsGeneration = true,
        supportsCancellation = true,
        supportedInputModes = setOf(
            VideoGenerationInputMode.TEXT_TO_VIDEO,
            VideoGenerationInputMode.IMAGE_TO_VIDEO,
            VideoGenerationInputMode.KEYFRAMES_TO_VIDEO,
        ),
        customDurationRangeSeconds = if (isSeedance15Pro) 4..12 else 2..12,
        supportedAspectRatios = linkedSetOf(
            "adaptive", "16:9", "4:3", "1:1", "3:4", "9:16", "21:9",
        ),
        supportedResolutions = linkedSetOf("480p", "720p", "1080p"),
        maxReferenceImages = 2,
        supportedReferenceImageRoles = if (isSeedance2) {
            setOf(
                VideoReferenceImageRole.REFERENCE,
                VideoReferenceImageRole.FIRST_FRAME,
                VideoReferenceImageRole.LAST_FRAME,
            )
        } else {
            setOf(VideoReferenceImageRole.FIRST_FRAME, VideoReferenceImageRole.LAST_FRAME)
        },
        supportsLastFrame = true,
        supportsSeed = true,
        supportsAudio = isSeedance15Pro || isSeedance2,
        supportsWatermark = true,
        supportsCameraFixed = true,
        supportsReturnLastFrame = true,
        maxOutputVideos = 1,
        pollingIntervalMillis = 3_000L..30_000L,
    )
}

internal fun buildSeedanceVideoRequestBody(
    params: VideoGenerationParams,
    constraints: VideoGenerationConstraints,
): JsonObject {
    val normalizedParams = params.copy(
        referenceImages = params.referenceImages.map { reference ->
            if (reference.role == VideoReferenceImageRole.REFERENCE &&
                VideoReferenceImageRole.REFERENCE !in constraints.supportedReferenceImageRoles &&
                VideoReferenceImageRole.FIRST_FRAME in constraints.supportedReferenceImageRoles
            ) {
                reference.copy(role = VideoReferenceImageRole.FIRST_FRAME)
            } else {
                reference
            }
        },
    )
    val constrained = normalizedParams.constrained(constraints)
    require(constrained.prompt.isNotBlank()) { "Video prompt cannot be empty" }
    when (constrained.inputMode) {
        VideoGenerationInputMode.IMAGE_TO_VIDEO -> require(constrained.referenceImages.isNotEmpty()) {
            "Image-to-video requires a reference image"
        }
        VideoGenerationInputMode.KEYFRAMES_TO_VIDEO -> require(
            constrained.referenceImages.any { it.role == VideoReferenceImageRole.FIRST_FRAME } &&
                constrained.referenceImages.any { it.role == VideoReferenceImageRole.LAST_FRAME },
        ) { "Keyframes-to-video requires both first and last frame images" }
        else -> Unit
    }
    val reserved = setOf(
        "model", "content", "resolution", "ratio", "duration", "seed", "generate_audio",
        "watermark", "camera_fixed", "return_last_frame",
    )
    val custom = constrained.customBody
        .filter { it.enabled && it.key.isNotBlank() && it.key.trim().lowercase() !in reserved }
        .associate { it.key.trim() to it.value }

    return buildJsonObject {
        custom.forEach { (key, value) -> put(key, value) }
        put("model", constrained.model.modelId.toArkModelId())
        putJsonArray("content") {
            add(buildJsonObject {
                put("type", "text")
                put("text", constrained.prompt)
            })
            constrained.referenceImages.forEach { reference ->
                add(buildJsonObject {
                    put("type", "image_url")
                    put("image_url", buildJsonObject { put("url", reference.url.trim()) })
                    put(
                        "role",
                        when (reference.role) {
                            VideoReferenceImageRole.REFERENCE -> "reference_image"
                            VideoReferenceImageRole.FIRST_FRAME -> "first_frame"
                            VideoReferenceImageRole.LAST_FRAME -> "last_frame"
                        },
                    )
                })
            }
        }
        constrained.resolution?.let { put("resolution", it) }
        constrained.aspectRatio?.let { put("ratio", it) }
        constrained.durationSeconds?.let { put("duration", it) }
        constrained.seed?.let { put("seed", it) }
        constrained.generateAudio?.let { put("generate_audio", it) }
        constrained.watermark?.let { put("watermark", it) }
        constrained.cameraFixed?.let { put("camera_fixed", it) }
        constrained.returnLastFrame?.let { put("return_last_frame", it) }
    }
}

internal fun parseSeedanceVideoTask(
    body: JsonObject,
    fallbackTaskId: String? = null,
    retryAfterMillis: Long? = null,
): VideoGenerationTaskSnapshot {
    val taskId = body.stringAt("id") ?: fallbackTaskId
    require(!taskId.isNullOrBlank()) { "Seedance response does not contain a task id" }
    val statusText = body.stringAt("status") ?: "queued"
    val content = body["content"] as? JsonObject
    val errorObject = body["error"] as? JsonObject
    val errorCode = errorObject?.stringAt("code") ?: body.stringAt("error_code")
    val errorMessage = errorObject?.stringAt("message") ?: body.stringAt("error_message")
    val status = statusText.toSeedanceTaskStatus()
    val videoUrl = content?.stringAt("video_url")
    val output = videoUrl?.takeIf(String::isNotBlank)?.let { url ->
        VideoGenerationOutput(
            downloadUrl = url,
            mimeType = "video/mp4",
            fileName = taskId.toSafeVideoFileName(),
            thumbnailUrl = content.stringAt("last_frame_url") ?: content.stringAt("cover_url"),
            width = content.intAt("width"),
            height = content.intAt("height"),
            durationMillis = content.durationMillis(),
            downloadUrlExpiresAtEpochMillis = content.epochMillisAt("video_url_expires_at"),
        )
    }
    val effectiveStatus = if (status == VideoGenerationTaskStatus.SUCCEEDED && output == null) {
        VideoGenerationTaskStatus.FAILED
    } else {
        status
    }
    val error = when {
        effectiveStatus != VideoGenerationTaskStatus.FAILED -> null
        output == null && status == VideoGenerationTaskStatus.SUCCEEDED -> VideoGenerationError(
            code = "missing_video_url",
            message = "Seedance task succeeded without a downloadable video URL",
            retryable = true,
        )
        else -> VideoGenerationError(
            code = errorCode,
            message = errorMessage?.takeIf(String::isNotBlank) ?: "Seedance video generation failed",
            retryable = errorCode.isRetryableSeedanceError(),
        )
    }
    return VideoGenerationTaskSnapshot(
        taskId = taskId,
        status = effectiveStatus,
        progressPercent = body.intAt("progress")?.coerceIn(0, 100),
        outputs = listOfNotNull(output),
        error = error,
        retryAfterMillis = retryAfterMillis,
        createdAtEpochMillis = body.epochMillisAt("created_at"),
        updatedAtEpochMillis = body.epochMillisAt("updated_at"),
    )
}

internal fun JsonObject.seedanceVideoDiagnostics(
    providerSetting: ProviderSetting.OpenAI,
    operation: ProviderRequestOperation,
    taskId: String? = null,
): ProviderRequestDiagnostics {
    val parameters = linkedMapOf("api" to "ark_video_generation")
    listOf(
        "resolution", "ratio", "duration", "seed", "generate_audio", "watermark",
        "camera_fixed", "return_last_frame",
    ).forEach { key ->
        (this[key] as? JsonPrimitive)?.contentOrNull?.let { parameters[key] = it }
    }
    (this["content"] as? JsonArray)?.let { content ->
        parameters["content.count"] = content.size.toString()
        parameters["reference_images.count"] = content.count { item ->
            (item as? JsonObject)?.stringAt("type") == "image_url"
        }.toString()
    }
    taskId?.let { parameters["task_id"] = it }
    return ProviderRequestDiagnostics(
        provider = providerSetting.name.ifBlank { "Volcengine Ark" },
        model = stringAt("model").orEmpty(),
        channel = ProviderRequestChannel.COMPATIBLE_ENDPOINT,
        operation = operation,
        parameters = parameters,
    )
}

private fun String.isSeedanceVideoModelId(): Boolean {
    val normalized = lowercase()
    return normalized.contains("seedance") || normalized.contains("jimeng")
}

private fun String.isArkEndpointId(): Boolean =
    trim().substringAfterLast('/').startsWith("ep-", ignoreCase = true)

internal fun String.isVolcengineArkBaseUrl(): Boolean = runCatching {
    val host = trim().trimEnd('/').toHttpUrl().host.lowercase()
    host == "volces.com" || host.endsWith(".volces.com")
}.getOrDefault(false)

internal fun ProviderSetting.OpenAI.arkVideoTaskUrl(taskId: String? = null): HttpUrl =
    baseUrl.trim().trimEnd('/').toHttpUrl().newBuilder()
        .addPathSegments("contents/generations/tasks")
        .apply { taskId?.let(::addPathSegment) }
        .build()

private fun String.toArkModelId(): String = trim().substringAfterLast('/').trim()

private fun String.toSeedanceTaskStatus(): VideoGenerationTaskStatus = when (lowercase()) {
    "queued", "pending" -> VideoGenerationTaskStatus.QUEUED
    "running", "processing", "in_progress" -> VideoGenerationTaskStatus.RUNNING
    "succeeded", "success", "completed" -> VideoGenerationTaskStatus.SUCCEEDED
    "failed", "error" -> VideoGenerationTaskStatus.FAILED
    "cancelled", "canceled" -> VideoGenerationTaskStatus.CANCELED
    "expired" -> VideoGenerationTaskStatus.EXPIRED
    else -> error("Unsupported Seedance task status: $this")
}

private fun String?.isRetryableSeedanceError(): Boolean {
    val normalized = this?.lowercase().orEmpty()
    return normalized.contains("rate") || normalized.contains("limit") ||
        normalized.contains("timeout") || normalized.contains("internal") ||
        normalized.contains("unavailable") || normalized.contains("overload")
}

private fun JsonObject.stringAt(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.intAt(key: String): Int? =
    (this[key] as? JsonPrimitive)?.intOrNull

private fun JsonObject.longAt(key: String): Long? =
    (this[key] as? JsonPrimitive)?.longOrNull

private fun JsonObject.epochMillisAt(key: String): Long? = longAt(key)?.let { value ->
    if (value in 1..9_999_999_999L) value * 1_000L else value
}

private fun JsonObject.durationMillis(): Long? {
    longAt("duration_ms")?.let { return it.takeIf { value -> value > 0L } }
    val seconds = (this["duration"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
    return seconds?.takeIf { it > 0.0 }?.times(1_000.0)?.toLong()
}

private fun String.toSafeVideoFileName(): String =
    replace(Regex("[^A-Za-z0-9._-]"), "_").take(80).ifBlank { "seedance-video" } + ".mp4"
