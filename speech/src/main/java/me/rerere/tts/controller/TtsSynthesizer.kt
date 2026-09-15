package me.rerere.tts.controller

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import me.rerere.tts.model.AudioChunk
import me.rerere.tts.model.AudioFormat
import me.rerere.tts.model.TTSRequest
import me.rerere.tts.model.TTSResponse
import me.rerere.tts.provider.TTSManager
import me.rerere.tts.provider.TTSProviderSetting
import java.io.ByteArrayOutputStream

/**
 * Bridge TTS provider flow to a single audio buffer.
 */
class TtsSynthesizer(
    private val ttsManager: TTSManager
) {
    fun stream(setting: TTSProviderSetting, chunk: TtsChunk): Flow<AudioChunk> =
        ttsManager.generateSpeech(setting, TTSRequest(text = chunk.text))

    suspend fun synthesize(
        setting: TTSProviderSetting,
        chunk: TtsChunk
    ): TTSResponse = withContext(Dispatchers.IO) {
        collectToResponse(
            ttsManager.generateSpeech(setting, TTSRequest(text = chunk.text))
        )
    }

    private suspend fun collectToResponse(flow: Flow<AudioChunk>): TTSResponse {
        var format: AudioFormat? = null
        var sampleRate: Int? = null
        val output = ByteArrayOutputStream()
        val metadata = mutableMapOf<String, String>()
        flow.collect { chunk ->
            check(output.size().toLong() + chunk.data.size <= 32L * 1024 * 1024) { "单段语音过大，请缩短朗读内容或降低音频质量。" }
            check(format == null || chunk.format == format) { "语音流中途更换了音频格式。" }
            if (format == null) format = chunk.format
            if (sampleRate == null) sampleRate = chunk.sampleRate
            metadata.putAll(chunk.metadata)
            output.write(chunk.data)
        }
        check(output.size() > 0) { "语音服务未返回音频，请检查模型、音色及服务日志。" }
        return TTSResponse(
            audioData = output.toByteArray(),
            format = format ?: AudioFormat.MP3,
            sampleRate = sampleRate,
            metadata = metadata,
        )
    }
}
