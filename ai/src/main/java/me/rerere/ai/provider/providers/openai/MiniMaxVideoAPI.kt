package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.*
import me.rerere.ai.provider.*
import me.rerere.ai.util.KeyRoulette
import me.rerere.ai.util.json
import me.rerere.ai.util.toHeaders
import me.rerere.common.http.awaitAndUse
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

val MINIMAX_VIDEO_MODELS = setOf("MiniMax-Hailuo-2.3", "MiniMax-Hailuo-2.3-Fast", "MiniMax-Hailuo-02",
    "T2V-01", "T2V-01-Director", "I2V-01", "I2V-01-Director", "I2V-01-live")

fun ProviderSetting.miniMaxVideoSetting(model: Model): ProviderSetting.OpenAI? {
    if (model.type != ModelType.VIDEO || model.modelId.trim() !in MINIMAX_VIDEO_MODELS) return null
    val base = when (this) { is ProviderSetting.OpenAI -> baseUrl; is ProviderSetting.Claude -> baseUrl; else -> return null }
    val url = runCatching { base.trim().toHttpUrl() }.getOrNull() ?: return null
    if (!url.isHttps || url.host !in setOf("api.minimaxi.com", "api.minimax.io", "api.minimax.chat")) return null
    val key = when (this) { is ProviderSetting.OpenAI -> apiKey; is ProviderSetting.Claude -> apiKey; else -> return null }
    return ProviderSetting.OpenAI(id = id, name = name, enabled = enabled, apiKey = key,
        baseUrl = url.newBuilder().encodedPath("/v1").query(null).fragment(null).build().toString())
}

fun miniMaxVideoConstraints(model: Model): VideoGenerationConstraints {
    val id = model.modelId.trim()
    val modern = id.startsWith("MiniMax-Hailuo")
    val imageOnly = id.startsWith("I2V") || id.endsWith("Fast")
    return VideoGenerationConstraints(supportsGeneration = id in MINIMAX_VIDEO_MODELS,
        supportedInputModes = if (imageOnly) setOf(VideoGenerationInputMode.IMAGE_TO_VIDEO)
            else if (id.startsWith("T2V")) setOf(VideoGenerationInputMode.TEXT_TO_VIDEO)
            else setOf(VideoGenerationInputMode.TEXT_TO_VIDEO, VideoGenerationInputMode.IMAGE_TO_VIDEO),
        supportedDurationsSeconds = if (modern) setOf(6, 10) else setOf(6),
        supportedResolutions = if (modern) linkedSetOf("768P", "1080P") else setOf("720P"),
        maxReferenceImages = if (id.startsWith("T2V")) 0 else 1,
        supportedReferenceImageRoles = setOf(VideoReferenceImageRole.FIRST_FRAME),
        supportsPromptEnhancement = true, supportsFastPretreatment = modern)
}

internal fun miniMaxVideoBody(params: VideoGenerationParams): JsonObject {
    require(params.inputMode in miniMaxVideoConstraints(params.model).supportedInputModes) { "This MiniMax model does not support the selected input mode" }
    val p = params.constrained(miniMaxVideoConstraints(params.model))
    require(p.prompt.isNotBlank() && p.prompt.codePointCount(0, p.prompt.length) <= 2000) { "MiniMax video prompt must contain 1-2000 characters" }
    require(p.resolution != "1080P" || p.durationSeconds != 10) { "MiniMax 1080P video only supports 6 seconds" }
    if (p.inputMode == VideoGenerationInputMode.IMAGE_TO_VIDEO) require(p.referenceImages.size == 1) { "Select a first-frame image" }
    return buildJsonObject {
        put("model", p.model.modelId.trim()); put("prompt", p.prompt)
        put("duration", p.durationSeconds ?: 6)
        put("resolution", p.resolution ?: if (p.model.modelId.startsWith("MiniMax")) "768P" else "720P")
        p.promptEnhancement?.let { put("prompt_optimizer", it) }
        p.fastPretreatment?.let { put("fast_pretreatment", it) }
        p.referenceImages.firstOrNull()?.let { put("first_frame_image", it.url) }
    }
}

internal class MiniMaxVideoAPI(private val client: OkHttpClient) {
    suspend fun create(setting: ProviderSetting.OpenAI, params: VideoGenerationParams): VideoGenerationTaskSnapshot {
        val response = call(setting, params.model, "video_generation", body = miniMaxVideoBody(params))
        val id = response["task_id"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: error("MiniMax returned no task id")
        return VideoGenerationTaskSnapshot(id, VideoGenerationTaskStatus.QUEUED)
    }

    suspend fun get(setting: ProviderSetting.OpenAI, model: Model, taskId: String): VideoGenerationTaskSnapshot {
        val result = call(setting, model, "query/video_generation", "task_id" to taskId)
        val status = when (result["status"]?.jsonPrimitive?.content) {
            "Preparing", "Queueing" -> VideoGenerationTaskStatus.QUEUED
            "Processing" -> VideoGenerationTaskStatus.RUNNING
            "Success" -> VideoGenerationTaskStatus.SUCCEEDED
            "Fail" -> VideoGenerationTaskStatus.FAILED
            else -> error("Unknown MiniMax video task status")
        }
        val outputs = if (status == VideoGenerationTaskStatus.SUCCEEDED) {
            val fileId = result["file_id"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: error("MiniMax returned no file id")
            val file = call(setting, model, "files/retrieve", "file_id" to fileId)["file"]?.jsonObject ?: error("Missing MiniMax file")
            listOf(VideoGenerationOutput(file["download_url"]?.jsonPrimitive?.content ?: error("Missing MiniMax download URL")))
        } else emptyList()
        return VideoGenerationTaskSnapshot(taskId, status, outputs = outputs,
            error = if (status == VideoGenerationTaskStatus.FAILED) VideoGenerationError(message = "MiniMax video generation failed") else null,
            retryAfterMillis = 10_000)
    }

    private suspend fun call(setting: ProviderSetting.OpenAI, model: Model, path: String,
        query: Pair<String, String>? = null, body: JsonObject? = null): JsonObject {
        val url = setting.baseUrl.trimEnd('/').toHttpUrl().newBuilder().addPathSegments(path)
            .apply { query?.let { addQueryParameter(it.first, it.second) } }.build()
        val request = Request.Builder().url(url).headers(model.customHeaders.toHeaders())
            .header("Authorization", "Bearer ${KeyRoulette.default().next(setting.apiKey)}")
            .tag(ProviderRequestDiagnostics::class.java, ProviderRequestDiagnostics(setting.name, model.modelId,
                ProviderRequestChannel.COMPATIBLE_ENDPOINT,
                if (body != null) ProviderRequestOperation.VIDEO_GENERATION_CREATE else ProviderRequestOperation.VIDEO_GENERATION_STATUS,
                mapOf("api" to "minimax_video", "endpoint" to path)))
            .apply { if (body != null) post(body.toString().toRequestBody("application/json".toMediaType())) }.build()
        return client.newCall(request).awaitAndUse { response ->
            if (!response.isSuccessful) throw openAIVideoRequestFailure("MiniMax video", response)
            val source = response.body.source()
            source.request(1_048_577)
            require(source.buffer.size <= 1_048_576) { "MiniMax response too large" }
            val result = json.parseToJsonElement(source.readUtf8()).jsonObject
            val status = result["base_resp"]?.jsonObject
            require(status?.get("status_code")?.jsonPrimitive?.intOrNull == 0) {
                "MiniMax ${status?.get("status_code")}: ${status?.get("status_msg")?.jsonPrimitive?.contentOrNull}"
            }
            result
        }
    }
}
