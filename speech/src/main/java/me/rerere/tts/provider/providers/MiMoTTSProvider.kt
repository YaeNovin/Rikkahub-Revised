package me.rerere.tts.provider.providers

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.*
import me.rerere.common.http.awaitAndUse
import me.rerere.tts.provider.normalized
import me.rerere.tts.provider.capabilities
import me.rerere.tts.provider.buildMiMoSpeechRequest
import me.rerere.tts.provider.SpeechVoiceInput
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.common.http.SseEvent
import me.rerere.tts.provider.speechSseFlow
import me.rerere.tts.model.AudioChunk
import me.rerere.tts.model.AudioFormat
import me.rerere.tts.model.TTSRequest
import me.rerere.tts.provider.TTSProvider
import me.rerere.tts.provider.TTSProviderSetting
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Base64
import java.util.concurrent.TimeUnit

// MiMo 流式音频按文档示例使用 24kHz PCM16LE
private const val MIMO_SAMPLE_RATE = 24000
private val JSON_MEDIA_TYPE = "application/json".toMediaType()
// 只关心 delta.audio.data 其余字段忽略
private val mimoJson = Json { ignoreUnknownKeys = true }

@Serializable
private data class MiMoChunk(
    val choices: List<MiMoChoice> = emptyList(),
    val error: JsonElement? = null,
)

@Serializable
private data class MiMoChoice(
    val delta: MiMoDelta? = null,
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
private data class MiMoDelta(
    val audio: MiMoAudio? = null
)

@Serializable
private data class MiMoAudio(
    val data: String? = null
)

internal fun decodeMiMoAudioData(data: String): ByteArray? {
    val payload = data.trim()
    // [DONE] 表示流结束 不输出音频
    if (payload == "[DONE]") return null
    // 非 [DONE] 的 data 视为 JSON 片段 解析失败直接上抛
    val chunk = mimoJson.decodeFromString<MiMoChunk>(payload)
    check(chunk.error == null) { "MiMo TTS: ${chunk.error}" }
    check(chunk.choices.none { it.finishReason in listOf("length", "content_filter") }) { "MiMo 语音生成未完整完成。" }
    val encoded = chunk.choices.firstOrNull()?.delta?.audio?.data ?: return null
    // 空字符串视为无音频片段
    if (encoded.isBlank()) return null
    return Base64.getDecoder().decode(encoded)
}

internal class MiMoSseProcessor(
    private val model: String,
    private val voice: String
) {
    private var hasAudio = false
    private var finished = false
    // metadata 只构造一次 贯穿整个流
    private val metadata = mapOf(
        "provider" to "mimo",
        "model" to model,
        "voice" to voice
    )

    fun process(event: SseEvent): AudioChunk? {
        return when (event) {
            is SseEvent.Open -> null
            is SseEvent.Event -> {
                if (event.data.trim() == "[DONE]") { finished = true; return null }
                if (mimoJson.decodeFromString<MiMoChunk>(event.data).choices.any { it.finishReason == "stop" }) finished = true
                // 只处理包含 audio.data 的增量事件 其他事件忽略
                val pcmData = decodeMiMoAudioData(event.data) ?: return null
                hasAudio = true
                AudioChunk(
                    data = pcmData,
                    format = AudioFormat.PCM,
                    sampleRate = MIMO_SAMPLE_RATE,
                    metadata = metadata
                )
            }

            is SseEvent.Closed -> {
                // 如果整段流没有任何音频片段 直接报错
                if (!hasAudio) {
                    throw IllegalStateException("MiMo TTS returned no audio chunks")
                }
                check(finished) { "MiMo 音频流提前结束，未收到完成标志。" }
                // 流关闭时补一个终结 chunk 便于播放器收尾
                AudioChunk(
                    data = byteArrayOf(),
                    format = AudioFormat.PCM,
                    sampleRate = MIMO_SAMPLE_RATE,
                    isLast = true,
                    metadata = metadata
                )
            }

            is SseEvent.Failure -> throw event.throwable ?: Exception("MiMo TTS streaming failed")
        }
    }
}

class MiMoTTSProvider(client: OkHttpClient = OkHttpClient()) : TTSProvider<TTSProviderSetting.MiMo> {
    private val httpClient = client.newBuilder()
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    // MiMo 支持在朗读文本中嵌入风格/音频标签控制语气与情感
    // 官方文档: https://xiaomimimo.com (音频标签控制)
    override val promptGuidance: String = """
        The active text-to-speech engine (MiMo) supports emotion and style control via embedded tags.
        When you call the text_to_speech tool, you MAY enrich the "text" argument with these tags to make the speech more expressive.
        Put tags ONLY inside the tool's text argument — never in your visible reply to the user.

        Two kinds of tags:
        1. Overall style tag — place ONE at the very beginning of the text: (style) . Combine multiple styles with spaces inside the same brackets, e.g. (开心 磁性) . Brackets may be () , （） or [] .
           Common styles: 开心/悲伤/愤怒/恐惧/惊讶/兴奋/委屈/平静/冷漠/怅然/欣慰/无奈/释然/温柔/高冷/活泼/严肃/慵懒/俏皮/深沉/磁性/醇厚/清亮/空灵/甜美/沙哑/御姐音/正太音/大叔音/台湾腔/东北话/四川话/河南话/粤语 . Custom styles are also allowed.
           For singing, the text MUST start with (唱歌) followed by lyrics (Chinese lyrics work best).
        2. Inline audio tags — insert [tag] anywhere to fine-tune delivery, e.g. [吸气] [深呼吸] [叹气] [笑] [轻笑] [大笑] [冷笑] [抽泣] [哽咽] [颤抖] [气声] [撒娇] [疲惫] [震惊] .

        IMPORTANT constraints (required by this app's text pipeline):
        - Do NOT put any punctuation (，。！？、：；…) INSIDE a tag's brackets. Separate multiple styles with spaces only, e.g. write (紧张 深呼吸) NOT (紧张，深呼吸).
        - Keep inline audio tags standalone like [笑]; do not immediately follow a [tag] with a (…) group.
        - Do not use markdown emphasis (*, _) — it will be stripped.
        - Use tags naturally and sparingly; don't over-annotate.

        Example text argument: (磁性)夜已经深了[叹气]城市还在呼吸。我是今晚陪你的人。
    """.trimIndent()

    override fun generateSpeech(
        context: Context,
        providerSetting: TTSProviderSetting.MiMo,
        request: TTSRequest
    ): Flow<AudioChunk> = flow {
        val setting = providerSetting.normalized()
        val sample = if (setting.capabilities().voiceInput == SpeechVoiceInput.AUDIO_SAMPLE)
            loadMiMoVoiceSample(context, setting.referenceAudioUri) else null
        val requestBody = buildMiMoSpeechRequest(setting, request.text, sample)
        val httpRequest = Request.Builder()
            .url("${setting.baseUrl}/chat/completions")
            .header("api-key", setting.apiKey)
            .post(requestBody.toString().toRequestBody(JSON_MEDIA_TYPE)).build()
        if (setting.streaming) {
            val processor = MiMoSseProcessor(setting.model, setting.voice)
            httpClient.speechSseFlow(httpRequest).collect { event ->
                processor.process(event)?.let { emit(it) }
                if (event is SseEvent.Event && event.data.trim() != "[DONE]") {
                    val root = mimoJson.parseToJsonElement(event.data).jsonObject
                    val delta = (root["choices"] as? JsonArray)?.firstOrNull()?.jsonObject?.get("delta") as? JsonObject
                    val preview = delta?.get("final_text_preview")?.jsonPrimitive?.contentOrNull
                    if (!preview.isNullOrEmpty()) emit(AudioChunk(byteArrayOf(), AudioFormat.PCM, MIMO_SAMPLE_RATE,
                        metadata = mapOf("spoken_text" to preview)))
                }
            }
        } else {
            httpClient.newCall(httpRequest).awaitAndUse { response ->
                check(response.isSuccessful) { "MiMo TTS HTTP ${response.code}" }
                val root = mimoJson.parseToJsonElement(response.body.string()).jsonObject
                check(root["error"] == null) { "MiMo TTS: ${root["error"]}" }
                val choice = (root["choices"] as? JsonArray)?.firstOrNull()?.jsonObject
                check(choice?.get("finish_reason")?.jsonPrimitive?.contentOrNull == "stop") { "MiMo 语音生成未完整完成。" }
                val message = choice["message"]?.jsonObject ?: error("MiMo 未返回语音消息。")
                val encoded = message["audio"]?.jsonObject?.get("data")?.jsonPrimitive?.contentOrNull
                    ?: error("MiMo 未返回音频。")
                val bytes = Base64.getDecoder().decode(encoded)
                check(bytes.isNotEmpty()) { "MiMo 返回了空音频。" }
                emit(AudioChunk(bytes, speechAudioFormat(setting.format), MIMO_SAMPLE_RATE, isLast = true,
                    metadata = buildMap {
                        put("provider", "mimo"); put("model", setting.model)
                        message["final_text_preview"]?.jsonPrimitive?.contentOrNull?.let { put("spoken_text", it) }
                    }))
            }
        }
    }
}

private fun loadMiMoVoiceSample(context: Context, value: String): String {
    require(value.isNotBlank()) { "请选择 MP3/WAV 音色样本。" }
    val uri = android.net.Uri.parse(value)
    require(uri.scheme == "content") { "请通过文件选择器重新选择音色样本。" }
    val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val size = input.read(buffer)
            if (size < 0) break
            require(out.size() + size <= 7_800_000) { "音色样本过大，编码后须小于 10 MB。" }
            out.write(buffer, 0, size)
        }
        out.toByteArray()
    } ?: error("无法读取音色样本，请重新授权该文件。")
    val wav = bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
        String(bytes, 8, 4, Charsets.US_ASCII) == "WAVE"
    val mp3 = (bytes.size >= 3 && String(bytes, 0, 3, Charsets.US_ASCII) == "ID3") ||
        (bytes.size >= 2 && bytes[0].toInt() and 0xff == 0xff && bytes[1].toInt() and 0xe0 == 0xe0)
    require(wav || mp3) { "音色样本必须为有效的 MP3 或 WAV 音频。" }
    return "data:${if (wav) "audio/wav" else "audio/mpeg"};base64," + Base64.getEncoder().encodeToString(bytes)
}
