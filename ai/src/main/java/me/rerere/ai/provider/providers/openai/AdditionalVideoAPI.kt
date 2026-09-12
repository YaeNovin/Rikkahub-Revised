package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.*
import me.rerere.ai.provider.*
import me.rerere.ai.util.json
import me.rerere.ai.util.toHeaders
import me.rerere.common.http.awaitAndUse
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal enum class VideoRoute { XAI, KLING, SUI_XIANG }

internal fun Model.grokVideoId(): String? = modelId.trim().lowercase()
    .removePrefix("xai/").removePrefix("x-ai/")
    .takeIf { it in setOf("grok-imagine-video", "grok-imagine-video-1.5") }

internal fun ProviderSetting.OpenAI.additionalVideoRoute(model: Model): VideoRoute? {
    if (model.type != ModelType.VIDEO) return null
    val url = runCatching { baseUrl.trim().toHttpUrl() }.getOrNull() ?: return null
    if (!url.isHttps) return null
    val host = url.host
    return when {
        model.grokVideoId() != null -> VideoRoute.XAI
        host in setOf("api.klingai.com", "api-singapore.klingai.com", "api-beijing.klingai.com") &&
            model.modelId in setOf("kling-v1", "kling-v1-6", "kling-v2-master", "kling-v2-1", "kling-v2-5-turbo", "kling-v2-6") -> VideoRoute.KLING
        host == "sui-xiang.com" && model.modelId in setOf("as-sd2.0-fast", "video-ds-2.0", "video-ds-2.0-fast") -> VideoRoute.SUI_XIANG
        else -> null
    }
}

internal fun additionalVideoConstraints(route: VideoRoute, model: Model) = when (route) {
    VideoRoute.SUI_XIANG -> VideoGenerationConstraints(supportsGeneration = true,
        supportedDurationsSeconds = setOf(15), supportedAspectRatios = linkedSetOf("9:16", "16:9"),
        pollingIntervalMillis = 10_000L..20_000L)
    VideoRoute.XAI -> VideoGenerationConstraints(supportsGeneration = true,
        supportedInputModes = setOf(VideoGenerationInputMode.TEXT_TO_VIDEO, VideoGenerationInputMode.IMAGE_TO_VIDEO),
        customDurationRangeSeconds = 1..15,
        supportedAspectRatios = linkedSetOf("16:9", "9:16", "1:1", "4:3", "3:4", "3:2", "2:3"),
        supportedResolutions = if (model.grokVideoId()?.endsWith("1.5") == true) linkedSetOf("480p", "720p", "1080p") else linkedSetOf("480p", "720p"),
        supportsAudio = true, maxReferenceImages = 1,
        supportedReferenceImageRoles = setOf(VideoReferenceImageRole.FIRST_FRAME))
    VideoRoute.KLING -> VideoGenerationConstraints(supportsGeneration = true,
        supportedInputModes = setOf(VideoGenerationInputMode.TEXT_TO_VIDEO, VideoGenerationInputMode.IMAGE_TO_VIDEO),
        supportedDurationsSeconds = setOf(5, 10), supportedAspectRatios = linkedSetOf("16:9", "9:16", "1:1"),
        supportsNegativePrompt = true, maxReferenceImages = 1,
        supportedReferenceImageRoles = setOf(VideoReferenceImageRole.FIRST_FRAME))
}

internal fun additionalVideoBody(route: VideoRoute, params: VideoGenerationParams): JsonObject {
    val p = params.constrained(additionalVideoConstraints(route, params.model))
    require(p.prompt.isNotBlank()) { "Video prompt cannot be empty" }
    if (p.inputMode == VideoGenerationInputMode.IMAGE_TO_VIDEO) require(p.referenceImages.size == 1) { "Select one first-frame image" }
    return buildJsonObject {
        if (route == VideoRoute.SUI_XIANG) {
            // This channel rejects every field except these four, including custom bodies.
            put("model", p.model.modelId); put("prompt", p.prompt)
            put("seconds", "15"); put("aspect_ratio", p.aspectRatio ?: "9:16")
        } else if (route == VideoRoute.XAI) {
            put("model", p.model.modelId.trim()); put("prompt", p.prompt)
            p.durationSeconds?.let { put("duration", it) }
            p.aspectRatio?.let { put("aspect_ratio", it) }
            p.resolution?.let { put("resolution", it) }
            p.generateAudio?.let { put("generate_audio", it) }
            p.referenceImages.firstOrNull()?.let { put("image", buildJsonObject { put("url", it.url) }) }
        } else {
            put("model_name", p.model.modelId); put("prompt", p.prompt)
            put("duration", (p.durationSeconds ?: 5).toString())
            put("mode", "pro")
            p.negativePrompt?.let { put("negative_prompt", it) }
            if (p.inputMode == VideoGenerationInputMode.TEXT_TO_VIDEO) put("aspect_ratio", p.aspectRatio ?: "16:9")
            p.referenceImages.firstOrNull()?.let { put("image", it.url.substringAfter("base64,", it.url)) }
        }
    }
}

internal fun klingBearer(credentials: String, nowSeconds: Long = System.currentTimeMillis() / 1000): String {
    val parts = credentials.trim().split(':', limit = 2)
    require(parts.size == 2 && parts.all { it.isNotBlank() }) { "Kling credentials must be AccessKey:SecretKey" }
    val encoder = Base64.getUrlEncoder().withoutPadding()
    fun encode(value: String) = encoder.encodeToString(value.toByteArray(Charsets.UTF_8))
    val header = encode("""{"alg":"HS256","typ":"JWT"}""")
    val payload = encode(buildJsonObject { put("iss", parts[0]); put("nbf", nowSeconds - 5); put("exp", nowSeconds + 1800) }.toString())
    val input = "$header.$payload"
    val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(parts[1].toByteArray(Charsets.UTF_8), "HmacSHA256")) }
    return "$input.${encoder.encodeToString(mac.doFinal(input.toByteArray(Charsets.UTF_8)))}"
}

internal class AdditionalVideoAPI(private val client: OkHttpClient) {
    suspend fun create(setting: ProviderSetting.OpenAI, route: VideoRoute, params: VideoGenerationParams, key: String): VideoGenerationTaskSnapshot {
        val kind = if (params.inputMode == VideoGenerationInputMode.TEXT_TO_VIDEO) "text2video" else "image2video"
        val path = when (route) { VideoRoute.XAI -> "videos/generations"; VideoRoute.KLING -> "videos/$kind"; VideoRoute.SUI_XIANG -> "videos" }
        val body = additionalVideoBody(route, params)
        val result = execute(setting, route, params.model, path, key, body)
        return parseAdditionalVideoTask(route, result, setting, kind = kind, creating = true)
    }

    suspend fun get(setting: ProviderSetting.OpenAI, route: VideoRoute, model: Model, id: String, key: String): VideoGenerationTaskSnapshot {
        val kind = if (route == VideoRoute.KLING) id.substringBefore(':') else ""
        require(route != VideoRoute.KLING || kind in setOf("text2video", "image2video")) { "Invalid Kling task type" }
        val remoteId = if (route == VideoRoute.KLING) id.substringAfter(':') else id
        val path = if (route == VideoRoute.KLING) "videos/$kind" else "videos"
        val result = execute(setting, route, model, path, key, taskId = remoteId)
        return parseAdditionalVideoTask(route, result, setting, id, kind)
    }

    private suspend fun execute(setting: ProviderSetting.OpenAI, route: VideoRoute, model: Model,
        path: String, key: String, body: JsonObject? = null, taskId: String? = null): JsonObject {
        val base = setting.baseUrl.trim().trimEnd('/').toHttpUrl()
        val builder = if (route == VideoRoute.XAI) {
            base.newBuilder().apply {
                if (base.encodedPath == "/") addPathSegment("v1")
                addPathSegments(path)
            }
        } else base.newBuilder().encodedPath("/v1/$path")
        val url = builder
            .apply { taskId?.let { addPathSegment(it) } }.query(null).build()
        val request = Request.Builder().url(url).headers(model.customHeaders.toHeaders())
            .header("Authorization", "Bearer ${if (route == VideoRoute.KLING) klingBearer(setting.apiKey) else key}")
            .tag(ProviderRequestDiagnostics::class.java, ProviderRequestDiagnostics(setting.name, model.modelId,
                if (route == VideoRoute.XAI && base.host == "api.x.ai") ProviderRequestChannel.XAI_API else ProviderRequestChannel.COMPATIBLE_ENDPOINT,
                if (body == null) ProviderRequestOperation.VIDEO_GENERATION_STATUS else ProviderRequestOperation.VIDEO_GENERATION_CREATE,
                mapOf("api" to route.name, "task_id" to taskId.orEmpty())))
            .apply { if (body != null) post(body.toString().toRequestBody("application/json".toMediaType())) else get() }.build()
        return client.newCall(request).awaitAndUse { response ->
            if (!response.isSuccessful) throw openAIVideoRequestFailure("video task", response)
            val source = response.body.source()
            source.request(1024L * 1024L + 1)
            require(source.buffer.size <= 1024L * 1024L) { "Video task response is too large" }
            json.parseToJsonElement(source.readUtf8()).jsonObject
        }
    }
}

internal fun parseAdditionalVideoTask(route: VideoRoute, root: JsonObject, setting: ProviderSetting.OpenAI,
    fallbackId: String? = null, kind: String = "text2video", creating: Boolean = false): VideoGenerationTaskSnapshot {
    fun JsonObject.str(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull
    if (route == VideoRoute.KLING) require(root.str("code") == "0") { "Kling ${root.str("code")}: ${root.str("message")}" }
    val data = if (route == VideoRoute.KLING) root["data"]?.jsonObject ?: error("Missing Kling data") else root
    val rawId = data.str(if (route == VideoRoute.XAI) "request_id" else if (route == VideoRoute.KLING) "task_id" else "id")
    val id = rawId?.let { if (route == VideoRoute.KLING) "$kind:$it" else it } ?: fallbackId ?: error("Missing video task id")
    val statusText = data.str(if (route == VideoRoute.KLING) "task_status" else "status") ?: if (creating) "queued" else error("Missing video task status")
    val status = when (statusText) {
        "queued", "submitted" -> VideoGenerationTaskStatus.QUEUED
        "pending", "processing", "running" -> VideoGenerationTaskStatus.RUNNING
        "done", "completed", "succeed" -> VideoGenerationTaskStatus.SUCCEEDED
        "failed" -> VideoGenerationTaskStatus.FAILED
        "expired" -> VideoGenerationTaskStatus.EXPIRED
        else -> error("Unknown video status: $statusText")
    }
    val outputs = if (status != VideoGenerationTaskStatus.SUCCEEDED) emptyList() else when (route) {
        VideoRoute.XAI -> listOf(VideoGenerationOutput((data["video"] as? JsonObject)?.str("url") ?: error("Missing video URL")))
        VideoRoute.SUI_XIANG -> listOf(VideoGenerationOutput(setting.baseUrl.toHttpUrl().newBuilder()
            .encodedPath("/v1/videos").addPathSegment(id).addPathSegment("content").query(null).build().toString()))
        VideoRoute.KLING -> ((data["task_result"] as? JsonObject)?.get("videos") as? JsonArray).orEmpty().map {
            VideoGenerationOutput(it.jsonObject.str("url") ?: error("Missing Kling video URL"))
        }
    }
    val error = if (status == VideoGenerationTaskStatus.FAILED) VideoGenerationError(
        code = (data["error"] as? JsonObject)?.str("code"),
        message = (data["error"] as? JsonObject)?.str("message") ?: data.str("task_status_msg") ?: "Video generation failed") else null
    return VideoGenerationTaskSnapshot(id, status, outputs = outputs, error = error,
        progressPercent = (data["progress"] as? JsonPrimitive)?.intOrNull?.coerceIn(0, 100),
        retryAfterMillis = if (route == VideoRoute.SUI_XIANG) 15_000L else 5_000L)
}
