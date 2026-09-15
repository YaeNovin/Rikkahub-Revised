package me.rerere.tts.provider.providers

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.*
import me.rerere.common.http.SseEvent
import me.rerere.tts.provider.speechSseFlow
import me.rerere.common.http.awaitAndUse
import me.rerere.tts.model.*
import me.rerere.tts.provider.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

internal fun speechAudioFormat(format: String): AudioFormat = when (format.lowercase()) {
    "pcm", "pcm16" -> AudioFormat.PCM
    "pcmu_raw" -> AudioFormat.MULAW
    "wav", "pcmu_wav" -> AudioFormat.WAV
    "flac" -> AudioFormat.FLAC
    "opus" -> AudioFormat.OPUS
    else -> AudioFormat.MP3
}

internal class MiniMaxSpeechDecoder(private val setting: TTSProviderSetting.MiniMax) {
    private val json = Json { ignoreUnknownKeys = true }
    private var hasAudio = false
    private var complete = false

    fun decode(payload: String): AudioChunk? {
        if (payload.trim() == "[DONE]") return null
        val root = json.parseToJsonElement(payload).jsonObject
        val status = root["base_resp"] as? JsonObject
        val code = status?.get("status_code")?.jsonPrimitive?.intOrNull ?: 0
        check(code == 0) { "MiniMax TTS $code: " + status?.get("status_msg")?.jsonPrimitive?.contentOrNull.orEmpty() }
        val data = root["data"] as? JsonObject ?: return null
        val hex = data["audio"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val bytes = decodeSpeechHex(hex)
        hasAudio = hasAudio || bytes.isNotEmpty()
        complete = complete || data["status"]?.jsonPrimitive?.intOrNull == 2
        val info = root["extra_info"] as? JsonObject
        val metadata = buildMap {
            put("provider", "minimax"); put("model", setting.model); put("voice", setting.voiceId)
            put("channels", (info?.get("audio_channel")?.jsonPrimitive?.intOrNull ?: setting.channels).toString())
            root["trace_id"]?.jsonPrimitive?.contentOrNull?.let { put("request_id", it) }
            data["subtitle_file"]?.jsonPrimitive?.contentOrNull?.let { put("subtitle_url", it) }
            data["subtitle"]?.let { put("subtitle", it.toString()) }
            data["subtitles"]?.let { put("subtitle", it.toString()) }
            info?.let { put("usage", it.toString()) }
        }
        return AudioChunk(bytes, speechAudioFormat(setting.format),
            info?.get("audio_sample_rate")?.jsonPrimitive?.intOrNull ?: setting.sampleRate,
            isLast = complete, metadata = metadata)
    }

    fun finish() {
        check(hasAudio) { "MiniMax 未返回音频，请检查音色、模型与服务状态。" }
        check(complete) { "MiniMax 音频流提前结束，未收到完成状态。" }
    }
}

internal fun decodeSpeechHex(hex: String): ByteArray {
    val text = hex.filterNot(Char::isWhitespace)
    require(text.length % 2 == 0) { "MiniMax 返回了不完整的音频编码。" }
    return ByteArray(text.length / 2) { index ->
        text.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}

class MiniMaxTTSProvider(client: OkHttpClient = OkHttpClient()) : TTSProvider<TTSProviderSetting.MiniMax> {
    private val httpClient = client.newBuilder().readTimeout(120, TimeUnit.SECONDS).build()

    override fun generateSpeech(context: Context, providerSetting: TTSProviderSetting.MiniMax,
        request: TTSRequest): Flow<AudioChunk> = flow {
        val setting = providerSetting.normalized()
        val httpRequest = Request.Builder().url("${setting.baseUrl}/t2a_v2")
            .header("Authorization", "Bearer ${setting.apiKey}")
            .post(buildMiniMaxSpeechRequest(setting, request.text).toString().toRequestBody("application/json".toMediaType()))
            .build()
        val decoder = MiniMaxSpeechDecoder(setting)
        if (setting.streaming) {
            httpClient.speechSseFlow(httpRequest).collect { event ->
                when (event) {
                    is SseEvent.Event -> decoder.decode(event.data)?.let { emit(it) }
                    is SseEvent.Failure -> throw event.throwable ?: IllegalStateException("MiniMax 语音连接失败。")
                    else -> Unit
                }
            }
        } else {
            httpClient.newCall(httpRequest).awaitAndUse { response ->
                check(response.isSuccessful) { "MiniMax TTS HTTP ${response.code}" }
                decoder.decode(response.body.string())?.let { emit(it) }
            }
        }
        decoder.finish()
    }

    suspend fun listVoices(setting: TTSProviderSetting.MiniMax): List<String> {
        val request = Request.Builder().url("${setting.baseUrl.trimEnd('/')}/get_voice")
            .header("Authorization", "Bearer ${setting.apiKey}")
            .post("""{"voice_type":"all"}""".toRequestBody("application/json".toMediaType())).build()
        return httpClient.newCall(request).awaitAndUse { response ->
            check(response.isSuccessful) { "获取音色失败：HTTP ${response.code}" }
            val root = Json.parseToJsonElement(response.body.string()).jsonObject
            val status = root["base_resp"] as? JsonObject
            check((status?.get("status_code")?.jsonPrimitive?.intOrNull ?: 0) == 0) {
                status?.get("status_msg")?.jsonPrimitive?.contentOrNull ?: "获取音色失败"
            }
            listOf("system_voice", "voice_cloning", "voice_generation").flatMap { key ->
                (root[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.get("voice_id")?.jsonPrimitive?.contentOrNull }
            }.distinct()
        }
    }
}
