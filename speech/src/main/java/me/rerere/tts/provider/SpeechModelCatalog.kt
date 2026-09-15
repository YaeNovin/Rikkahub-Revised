package me.rerere.tts.provider

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import me.rerere.asr.ASRProviderSetting
import me.rerere.common.http.awaitAndUse
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

enum class SpeechModelService { OPENAI, GOOGLE, MIMO, MINIMAX, ELEVENLABS, GROQ, STEP, QWEN, FISH }
enum class SpeechModelUse { SYNTHESIS, TRANSCRIPTION, REALTIME_TRANSCRIPTION }

data class SpeechCatalogModel(val id: String, val name: String = id)
data class SpeechCatalogResult(val models: List<SpeechCatalogModel>, val excludedCount: Int)

data class SpeechModelSource(
    val service: SpeechModelService,
    val use: SpeechModelUse,
    val baseUrl: String,
    val apiKey: String,
    val presets: List<String> = emptyList(),
) {
    // Never include credentials in debug output, exceptions or UI messages.
    override fun toString() = "SpeechModelSource(service=$service, use=$use)"

    val unavailableReason: String?
        get() = when {
            service == SpeechModelService.FISH -> "该服务的 Model API 查询音色，并非合成引擎。请选择引擎预设或手动填写。"
            service == SpeechModelService.MINIMAX && baseUrl.toHttpUrlOrNull()?.host in setOf(
                "api.minimaxi.com", "api.minimax.io", "api-uw.minimax.io"
            ) -> "MiniMax 官方暂未提供模型目录接口，可选择官方预设；自定义兼容服务可尝试获取模型。"
            else -> null
        }
}

fun TTSProviderSetting.speechModelSource(): SpeechModelSource? = when (this) {
    is TTSProviderSetting.OpenAI -> SpeechModelSource(SpeechModelService.OPENAI, SpeechModelUse.SYNTHESIS, baseUrl, apiKey,
        listOf("gpt-4o-mini-tts", "tts-1", "tts-1-hd"))
    is TTSProviderSetting.Gemini -> SpeechModelSource(SpeechModelService.GOOGLE, SpeechModelUse.SYNTHESIS, baseUrl, apiKey,
        listOf("gemini-2.5-flash-preview-tts", "gemini-2.5-pro-preview-tts"))
    is TTSProviderSetting.MiMo -> SpeechModelSource(SpeechModelService.MIMO, SpeechModelUse.SYNTHESIS, baseUrl, apiKey, SpeechModels.miMoTts)
    is TTSProviderSetting.MiniMax -> SpeechModelSource(SpeechModelService.MINIMAX, SpeechModelUse.SYNTHESIS, baseUrl, apiKey, SpeechModels.miniMax)
    is TTSProviderSetting.ElevenLabs -> SpeechModelSource(SpeechModelService.ELEVENLABS, SpeechModelUse.SYNTHESIS, baseUrl, apiKey,
        listOf("eleven_multilingual_v2", "eleven_v3", "eleven_flash_v2_5"))
    is TTSProviderSetting.Groq -> SpeechModelSource(SpeechModelService.GROQ, SpeechModelUse.SYNTHESIS, baseUrl, apiKey,
        listOf("canopylabs/orpheus-v1-english", "canopylabs/orpheus-arabic-saudi"))
    is TTSProviderSetting.Step -> SpeechModelSource(SpeechModelService.STEP, SpeechModelUse.SYNTHESIS, baseUrl, apiKey,
        listOf("step-tts-mini", "step-tts-vivid", "stepaudio-2.5-tts", "step-tts-2"))
    is TTSProviderSetting.Qwen -> SpeechModelSource(SpeechModelService.QWEN, SpeechModelUse.SYNTHESIS, baseUrl, apiKey,
        listOf("qwen3-tts-flash"))
    is TTSProviderSetting.FishAudio -> SpeechModelSource(SpeechModelService.FISH, SpeechModelUse.SYNTHESIS, baseUrl, apiKey,
        listOf("s2.1-pro", "s2.1-pro-free", "s2-pro", "s1"))
    is TTSProviderSetting.SystemTTS, is TTSProviderSetting.XAI -> null // No engine model field in these APIs.
}

fun ASRProviderSetting.speechModelSource(): SpeechModelSource? = when (this) {
    is ASRProviderSetting.OpenAIRealtime -> SpeechModelSource(SpeechModelService.OPENAI, SpeechModelUse.REALTIME_TRANSCRIPTION,
        websocketUrl, apiKey, listOf("gpt-4o-transcribe", "gpt-4o-mini-transcribe"))
    is ASRProviderSetting.DashScope -> SpeechModelSource(SpeechModelService.QWEN, SpeechModelUse.REALTIME_TRANSCRIPTION,
        websocketUrl, apiKey, listOf("qwen3-asr-flash-realtime"))
    is ASRProviderSetting.MiMo -> SpeechModelSource(SpeechModelService.MIMO, SpeechModelUse.TRANSCRIPTION,
        baseUrl, apiKey, SpeechModels.miMoAsr)
    is ASRProviderSetting.Step -> SpeechModelSource(SpeechModelService.STEP, SpeechModelUse.TRANSCRIPTION,
        baseUrl, apiKey, listOf("stepaudio-2.5-asr"))
    is ASRProviderSetting.Volcengine -> null // Resource ID is not a model ID.
}

/** Build on the configured origin; pagination may only change query parameters, never the host. */
internal fun SpeechModelSource.modelListUrl(): HttpUrl {
    val converted = baseUrl.trim().replaceFirst(Regex("^wss://"), "https://").replaceFirst(Regex("^ws://"), "http://")
    val base = converted.toHttpUrlOrNull() ?: error("服务地址无效，请检查地址格式。")
    require(base.username.isEmpty() && base.password.isEmpty()) { "请把 API Key 填入密钥字段。" }
    val segments = base.pathSegments.filter(String::isNotEmpty).toMutableList()
    if (service == SpeechModelService.QWEN) {
        // DashScope's realtime and HTTP synthesis APIs share its OpenAI-compatible catalog.
        val apiIndex = segments.indexOfFirst { it == "api-ws" || it == "api" }
        if (apiIndex >= 0) {
            while (segments.size > apiIndex) segments.removeAt(segments.lastIndex)
            segments.addAll(listOf("compatible-mode", "v1"))
        }
    } else if (use == SpeechModelUse.REALTIME_TRANSCRIPTION) {
        if (segments.lastOrNull() == "realtime") segments.removeAt(segments.lastIndex)
    }
    if (service in setOf(SpeechModelService.STEP, SpeechModelService.ELEVENLABS, SpeechModelService.MINIMAX) &&
        segments.lastOrNull() !in setOf("v1", "models")) segments.add("v1")
    if (segments.lastOrNull() != "models") segments.add("models")
    return base.newBuilder().encodedPath("/").query(null).fragment(null).apply {
        segments.forEach { addPathSegment(it) }
        if (service == SpeechModelService.GOOGLE) addQueryParameter("pageSize", "1000")
    }.build()
}

internal fun SpeechModelSource.modelListRequest(url: HttpUrl = modelListUrl()): Request {
    val key = apiKey.trim()
    require(key.isNotEmpty()) { "请先填写 API Key。" }
    require(!key.contains('\n') && !key.contains('\r')) { "API Key 格式无效。" }
    return Request.Builder().url(url).header("Accept", "application/json").apply {
        when (service) {
            SpeechModelService.MIMO -> header("api-key", key)
            SpeechModelService.GOOGLE -> header("x-goog-api-key", key)
            SpeechModelService.ELEVENLABS -> header("xi-api-key", key)
            else -> header("Authorization", "Bearer $key")
        }
    }.get().build()
}

internal data class SpeechCatalogPage(val entries: List<JsonObject>, val cursor: String?, val hasMore: Boolean)

internal fun parseSpeechCatalogPage(body: String): SpeechCatalogPage {
    val root = Json.parseToJsonElement(body)
    if (root is JsonArray) return SpeechCatalogPage(root.mapNotNull { it as? JsonObject }, null, false)
    val obj = root as? JsonObject ?: error("模型列表格式无效。")
    check(obj["error"] == null || obj["error"] == JsonNull) { "供应商返回模型查询错误，请检查密钥权限。" }
    val business = obj["base_resp"] as? JsonObject
    check((business?.get("status_code") as? JsonPrimitive)?.intOrNull in listOf(null, 0)) { "供应商拒绝了模型查询，请检查密钥权限。" }
    val entries = (obj["data"] ?: obj["models"]) as? JsonArray ?: error("响应中没有模型列表。")
    return SpeechCatalogPage(
        entries.mapNotNull { it as? JsonObject },
        (obj["nextPageToken"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank),
        (obj["has_more"] as? JsonPrimitive)?.booleanOrNull == true,
    )
}

private val ttsToken = Regex("(^|[-_/])tts($|[-_/])")
private val asrToken = Regex("(^|[-_/])(asr|transcribe|transcription|whisper)($|[-_/])")
private val miniMaxSpeech = Regex("^speech-(?:\\d+\\.)?\\d+-(?:hd|turbo)(?:-|$)")

internal fun speechModelId(entry: JsonObject, service: SpeechModelService): String? {
    val identifier = if (service == SpeechModelService.GOOGLE) entry["name"] ?: entry["id"] else entry["id"] ?: entry["model_id"]
    val raw = (identifier as? JsonPrimitive)?.contentOrNull?.trim()
        ?.takeIf(String::isNotEmpty) ?: return null
    return if (service == SpeechModelService.GOOGLE) raw.removePrefix("models/") else raw
}

internal fun isUsableSpeechModel(entry: JsonObject, source: SpeechModelSource): Boolean {
    val id = speechModelId(entry, source.service)?.lowercase() ?: return false
    if ((entry["active"] as? JsonPrimitive)?.booleanOrNull == false) return false
    val leaf = id.substringAfterLast('/')
    val caps = entry["capabilities"] as? JsonObject
    val tasks = listOfNotNull(entry["task"], entry["type"], caps?.get("task")).mapNotNull {
        (it as? JsonPrimitive)?.contentOrNull?.lowercase()?.replace('_', '-')
    }
    val synthesisFlag = ((entry["can_do_text_to_speech"] ?: caps?.get("text_to_speech")) as? JsonPrimitive)?.booleanOrNull
    val recognitionFlag = ((entry["can_do_speech_to_text"] ?: caps?.get("speech_to_text")) as? JsonPrimitive)?.booleanOrNull
    val isTts = synthesisFlag ?: (ttsToken.containsMatchIn(id) || miniMaxSpeech.containsMatchIn(leaf) ||
        leaf.startsWith("orpheus-") || tasks.any { it in setOf("tts", "text-to-speech", "speech-synthesis") } ||
        (source.service == SpeechModelService.ELEVENLABS && !leaf.contains("sts") &&
            Regex("^eleven_(?:(?:multilingual|monolingual|flash|turbo)_|v[0-9]+(?:$|_))").containsMatchIn(leaf)) ||
        (source.service == SpeechModelService.FISH && leaf in source.presets))
    val isAsr = recognitionFlag ?: (asrToken.containsMatchIn(id) || tasks.any {
        it in setOf("asr", "speech-to-text", "automatic-speech-recognition", "transcription")
    })
    when (source.use) {
        SpeechModelUse.SYNTHESIS -> {
            if (!isTts) return false
            if (source.service == SpeechModelService.QWEN && ("realtime" in id || !leaf.startsWith("qwen"))) return false
            if (source.service == SpeechModelService.GOOGLE) {
                val methods = (entry["supportedGenerationMethods"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                if (!methods.isNullOrEmpty() && "generateContent" !in methods) return false
            }
        }
        SpeechModelUse.TRANSCRIPTION -> if (!isAsr || "realtime" in id) return false
        SpeechModelUse.REALTIME_TRANSCRIPTION -> {
            if (!isAsr || "diarize" in id || "whisper" in id) return false
            if (source.service == SpeechModelService.QWEN) return leaf.startsWith("qwen") && "realtime" in leaf
            val endpoints = (entry["supported_endpoints"] as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            return "realtime" in id || leaf.startsWith("gpt-live-transcribe") ||
                Regex("^gpt-4o(?:-mini)?-transcribe(?:-|$)").containsMatchIn(leaf) ||
                endpoints.any { it.trimEnd('/').endsWith("/realtime") || it == "realtime" }
        }
    }
    return true
}

internal fun filterSpeechModels(entries: List<JsonObject>, source: SpeechModelSource): SpeechCatalogResult {
    val models = entries.filter { isUsableSpeechModel(it, source) }.mapNotNull {
        val id = speechModelId(it, source.service) ?: return@mapNotNull null
        val name = ((it["displayName"] ?: it["name"]) as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
        SpeechCatalogModel(id, name?.removePrefix("models/") ?: id)
    }.distinctBy { it.id }.sortedBy { it.id.lowercase() }
    return SpeechCatalogResult(models, entries.count { !isUsableSpeechModel(it, source) })
}

class SpeechModelCatalog(client: OkHttpClient = OkHttpClient()) {
    private val http = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS).build()

    suspend fun fetch(source: SpeechModelSource): SpeechCatalogResult = withContext(Dispatchers.IO) {
        source.unavailableReason?.let { error(it) }
        val initial = source.modelListUrl()
        var url = initial
        val all = mutableListOf<JsonObject>()
        val seenPages = mutableSetOf<String>()
        repeat(20) {
            check(seenPages.add(url.toString())) { "供应商返回了重复的模型列表分页。" }
            val page = http.newCall(source.modelListRequest(url)).awaitAndUse { response ->
                check(response.isSuccessful) {
                    when (response.code) {
                        401, 403 -> "模型查询未获授权（HTTP ${response.code}），请检查 API Key 与权限。"
                        404, 405 -> "该服务不支持此模型目录接口，可使用预设或手动填写。"
                        429 -> "模型查询过于频繁，请稍后重试。"
                        in 300..399 -> "模型接口发生重定向，请检查服务地址。"
                        else -> "获取模型失败：HTTP ${response.code}。"
                    }
                }
                val bytes = ByteArrayOutputStream()
                response.body.byteStream().use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val size = input.read(buffer)
                        if (size < 0) break
                        check(bytes.size().toLong() + size <= 5L * 1024 * 1024) { "模型列表响应过大。" }
                        bytes.write(buffer, 0, size)
                    }
                }
                parseSpeechCatalogPage(bytes.toString(Charsets.UTF_8.name()))
            }
            all.addAll(page.entries)
            val token = page.cursor ?: if (page.hasMore) page.entries.lastOrNull()?.let {
                speechModelId(it, source.service)
            } ?: error("供应商模型分页缺少游标。") else null
            if (token == null) return@withContext filterSpeechModels(all, source)
            url = initial.newBuilder().addQueryParameter(if (page.cursor != null) "pageToken" else "after", token).build()
        }
        error("模型列表分页过多，请检查供应商接口。")
    }
}
