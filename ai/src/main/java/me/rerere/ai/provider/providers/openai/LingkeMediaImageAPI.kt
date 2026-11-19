package me.rerere.ai.provider.providers.openai

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.ImageGenerationConstraints
import me.rerere.ai.provider.parameterModelId
import me.rerere.common.http.await
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit

/** Keep model capabilities shared with GPT Image 2.6; only the transport differs. */
internal fun ImageGenerationConstraints.withLingkeMediaTransport() = copy(
    maxOutputImages = 1,
    supportsOutputCount = false,
    supportedGptImage26Versions = if (supportsGptImage26Options) linkedSetOf("flare", "sunburst") else emptySet(),
)

/** https://yk.lk666.ai/apidoc#model/tt-image-2.6 */
internal fun buildLingkeImageRequest(
    params: ImageGenerationParams,
    images: List<File> = emptyList(),
    constraints: ImageGenerationConstraints,
): JsonObject {
    require(params.numOfImages == 1) { "此媒体接口每次生成一张图片" }
    require(images.size <= 16 && images.all { it.isFile && it.length() <= 10 * 1024 * 1024L } &&
        images.sumOf { it.length() } <= 30 * 1024 * 1024L) { "参考图需每张不超过 10MB、合计不超过 30MB，最多 16 张" }
    val customFields = params.customBody.filter { it.enabled && it.key.isNotBlank() }.flatMap { custom ->
        if (custom.key.trim() == "params") {
            require(custom.value is JsonObject) { "params 需要 JSON 对象" }
            custom.value.map { (key, value) -> me.rerere.ai.provider.CustomBody(key, value) }
        } else listOf(custom.copy(key = custom.key.trim()))
    }
    val requestedFormat = params.outputFormat ?: customFields.lastOrNull { it.key == "output_format" }
        ?.value?.let { (it as? JsonPrimitive)?.contentOrNull } ?: "png"
    // The media API chooses GIF dimensions itself. Other options share the native builder.
    val body = buildOpenAIImageGenerationRequestBody(params.copy(
        size = if (requestedFormat == "gif") "auto" else params.size,
        customBody = customFields,
    ), constraints)
    val fields = body.toMutableMap().apply {
        listOf("model", "prompt", "n").forEach(::remove)
    }
    if (constraints.supportsGptImage26Options) {
        val version = fields["version"] ?: JsonPrimitive(
            if (params.model.parameterModelId().contains("sunburst", true)) "sunburst" else "flare",
        )
        require(version is JsonPrimitive && version.isString && version.content in setOf("flare", "sunburst")) {
            "模型版本需要 flare 或 sunburst"
        }
        fields["version"] = version
    }
    // These media-specific sizing options remain available through custom parameters.
    params.resolution?.let { fields["resolution"] = JsonPrimitive(it) }
    if (requestedFormat == "gif") {
        listOf("size", "aspect_ratio", "resolution").forEach(fields::remove)
    } else if (params.size == "auto" && (fields["resolution"] != null || fields["aspect_ratio"] != null)) {
        fields.remove("size")
        if (fields["resolution"] == JsonPrimitive("auto") || fields["aspect_ratio"] == JsonPrimitive("auto")) {
            fields["resolution"] = JsonPrimitive("auto")
            fields["aspect_ratio"] = JsonPrimitive("auto")
        }
    }
    if (images.isNotEmpty()) fields["images"] = JsonArray(images.map { file ->
        val mime = when (file.extension.lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"; "png" -> "image/png"; "webp" -> "image/webp"; "gif" -> "image/gif"
            else -> error("此媒体接口参考图片仅支持 JPEG、PNG、WebP 和 GIF")
        }
        JsonPrimitive("data:$mime;base64,${Base64.getEncoder().encodeToString(file.readBytes())}")
    })
    val notifyUrl = fields.remove("notify_url")
    return buildJsonObject {
        put("model", params.model.modelId); put("prompt", params.prompt); put("params", JsonObject(fields))
        notifyUrl?.let { put("notify_url", it) }
    }
}

internal class LingkeMediaImageAPI(client: OkHttpClient, private val pollIntervalMillis: Long = 5000,
    private val timeoutMillis: Long = TimeUnit.HOURS.toMillis(2)) {
    private val client = client.newBuilder().retryOnConnectionFailure(false)
        .followRedirects(false).followSslRedirects(false).build()

    suspend fun generate(request: Request, statusBaseUrl: String): List<String> {
        val created = call(request)
        val taskId = (created["data"] as? JsonObject)?.get("task_id")?.jsonPrimitive?.contentOrNull
            ?.takeIf(String::isNotBlank) ?: error("媒体接口未返回 data.task_id")
        val started = System.nanoTime()
        while ((System.nanoTime() - started) / 1_000_000 < timeoutMillis) {
            currentCoroutineContext().ensureActive()
            val statusUrl = imageApiUrl(statusBaseUrl, "/skills/task-status").toHttpUrl().newBuilder()
                .addQueryParameter("task_id", taskId).build()
            val status = call(request.newBuilder().url(statusUrl).get().removeHeader("Content-Type").build())
            val final = status["is_final"]?.jsonPrimitive?.booleanOrNull == true
            if (final) {
                val error = (status["error"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
                check(error == null && status["state"]?.jsonPrimitive?.contentOrNull == "success") {
                    "媒体任务 $taskId 失败：${error ?: status["state"]}"
                }
                val urls = (status["result_urls"] as? JsonArray)?.mapNotNull {
                    (it as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
                }.orEmpty().ifEmpty {
                    listOfNotNull((status["result_url"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank))
                }
                check(urls.isNotEmpty()) { "媒体任务 $taskId 已结束，但没有返回图片地址" }
                urls.forEach { require(it.toHttpUrl().scheme in setOf("http", "https")) }
                return urls.distinct()
            }
            delay(pollIntervalMillis)
        }
        error("媒体任务 $taskId 等待超时，请在供应商侧查询任务状态，避免重复提交")
    }

    private suspend fun call(request: Request): JsonObject = client.newCall(request).await().use { response ->
        check(response.isSuccessful) { "媒体接口请求失败：HTTP ${response.code}" }
        val source = response.body.source()
        check(!source.request(512 * 1024L + 1)) { "媒体接口响应过大" }
        val body = Json.parseToJsonElement(source.readUtf8()).jsonObject
        val code = (body["code"] as? JsonPrimitive)?.intOrNull
        check(code == null || code == 200) { "媒体接口失败：${body["msg"] ?: code}" }
        body
    }
}
