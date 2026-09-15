package me.rerere.asr.providers

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.asr.ASRController
import me.rerere.asr.ASRProviderSetting
import me.rerere.asr.ASRState
import me.rerere.asr.ASRStatus
import me.rerere.asr.appendAmplitude
import me.rerere.asr.calculateRmsAmplitude
import me.rerere.tts.provider.normalized
import me.rerere.tts.provider.buildMiMoAsrRequest
import me.rerere.common.http.awaitAndUse
import me.rerere.common.http.SseEvent
import me.rerere.tts.provider.speechSseFlow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Collections

private const val TAG = "MiMoASR"

// MiMo 官方限制: 单次请求 base64 不超过 10MB ≈ 7.5MB raw。
// 16kHz / 16bit / mono 下 7.5MB ≈ 234 秒。提前在 6MB 触发自动 flush 留余量。
private const val MAX_SEGMENT_BYTES = 6 * 1024 * 1024

/**
 * 小米 MiMo ASR Controller。
 *
 * MiMo ASR 不是 WebSocket 流式接口, 而是 OpenAI 兼容的 chat/completions HTTP 一次性
 * 识别接口。本 Controller 在录音期间按时间或字节阈值把 PCM 切成段, 每段独立转 WAV
 * 后 POST 到 MiMo, 返回的文本拼接到 completedTranscripts 并通过 onTranscriptChange
 * 回调。stop() 时把剩余 PCM 做最后一次 flush。
 *
 * 官方文档: https://platform.xiaomimimo.com/docs/zh-CN/api/audio/Speech-Recognition
 */
class MiMoASRController(
    private val context: Context,
    private val httpClient: OkHttpClient,
    provider: ASRProviderSetting.MiMo
) : ASRController {
    private val provider = provider.normalized()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(ASRState(isAvailable = true))
    override val state: StateFlow<ASRState> = _state.asStateFlow()

    private var recorderJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private var onTranscriptChange: ((String) -> Unit)? = null

    // 同一时刻只允许一个 flush 协程在跑, 避免乱序拼结果
    private var flushJob: Job? = null
    private var finishJob: Job? = null
    private var failedSegment: ByteArray? = null
    private var partialTranscript: String = ""

    private val bufferLock = Any()
    private var currentBuffer = ByteArrayOutputStream()
    private var segmentStartElapsedMs = 0L
    private val completedTranscripts = Collections.synchronizedList(mutableListOf<String>())

    override fun start(onTranscriptChange: (String) -> Unit) {
        if (state.value.isRecording) return
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            setError("Microphone permission is required")
            return
        }

        this.onTranscriptChange = onTranscriptChange
        finishJob?.cancel()
        failedSegment = null
        partialTranscript = ""
        synchronized(bufferLock) {
            currentBuffer = ByteArrayOutputStream()
            segmentStartElapsedMs = SystemClock.elapsedRealtime()
        }
        completedTranscripts.clear()
        flushJob = null

        // MiMo 是 HTTP 一次性接口, 没有 WebSocket 连接阶段, 直接进入 Listening
        _state.update {
            ASRState(
                status = ASRStatus.Listening,
                isAvailable = true
            )
        }
        startRecorder()
    }

    override fun stop() {
        if (finishJob?.isActive == true) return
        val recording = recorderJob
        recording?.cancel()
        releaseRecorder()
        _state.update { it.copy(status = ASRStatus.Stopping) }

        // 把剩余 PCM 做最后一次 flush, 完成后切回 Idle
        finishJob = scope.launch(Dispatchers.IO) {
            try {
                recording?.join()
                // 等当前正在跑的 flushJob 完成, 避免并发 flush 导致结果乱序
                flushJob?.join()
                flushSegment()
                if (synchronized(bufferLock) { currentBuffer.size() > 0 }) flushSegment()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e(TAG, "Final flush failed", e)
                setError(e.message ?: "MiMo ASR final flush failed")
            } finally {
                _state.update { it.copy(status = if (it.errorMessage == null) ASRStatus.Idle else ASRStatus.Error) }
            }
        }
    }

    override fun dispose() {
        finishJob?.cancel()
        recorderJob?.cancel()
        flushJob?.cancel()
        releaseRecorder()
        scope.cancel()
    }

    @SuppressLint("MissingPermission")
    private fun startRecorder() {
        recorderJob?.cancel()
        recorderJob = scope.launch(Dispatchers.IO) {
            val sampleRate = provider.sampleRate
            val minBufferSize = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBufferSize <= 0) {
                setError("设备不支持当前录音采样率或格式，请更换采样率。")
                return@launch
            }
            val bufferSize = minBufferSize
                .coerceAtLeast(sampleRate / 10 * 2)
                .coerceAtLeast(4096)

            val recorder = try { AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize * 2
            ) } catch (e: Exception) {
                setError(e.message ?: "麦克风初始化失败。")
                return@launch
            }
            audioRecord = recorder

            try {
                recorder.startRecording()
                val buffer = ByteArray(bufferSize)
                val segmentMs = provider.segmentDurationSec.coerceAtLeast(0) * 1000L
                while (isActive) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        val amplitude = calculateRmsAmplitude(buffer, read)
                        _state.update { it.copy(amplitudes = it.amplitudes.appendAmplitude(amplitude)) }

                        val shouldFlush = synchronized(bufferLock) {
                            currentBuffer.write(buffer, 0, read)
                            check(currentBuffer.size() <= MAX_SEGMENT_BYTES + buffer.size) { "网络上传过慢，录音已停止，待处理音频可重试。" }
                            if (segmentMs <= 0) {
                                currentBuffer.size() >= MAX_SEGMENT_BYTES
                            } else {
                                val elapsed = SystemClock.elapsedRealtime() - segmentStartElapsedMs
                                currentBuffer.size() >= MAX_SEGMENT_BYTES || elapsed >= segmentMs
                            }
                        }

                        if (shouldFlush) {
                            // 用单独协程异步 flush, 不阻塞录音主循环
                            triggerFlush()
                        }
                    } else if (read < 0) {
                        throw IllegalStateException("AudioRecord read error: $read")
                    }
                }
            } catch (e: Exception) {
                if (!isActive) return@launch
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e(TAG, "Audio recording failed", e)
                setError(e.message ?: "Audio recording failed")
            } finally {
                releaseRecorder()
            }
        }
    }

    private fun triggerFlush() {
        // 同一时刻只跑一个 flush, 避免后发先至导致结果乱序
        if (flushJob?.isActive == true) return
        flushJob = scope.launch(Dispatchers.IO) {
            try { flushSegment() } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                setError(e.message ?: "语音识别失败，可重试。")
                recorderJob?.cancel()
                releaseRecorder()
            }
        }
    }

    /**
     * 取出当前缓冲区里的 PCM, 转 WAV 后 POST 到 MiMo; 把识别结果加到 completedTranscripts。
     * 在 bufferLock 内拷贝出 PCM 并立刻重置缓冲区, 不持有锁等待网络, 避免阻塞录音写。
     */
    private suspend fun flushSegment() {
        val pcmBytes = failedSegment ?: synchronized(bufferLock) {
            if (currentBuffer.size() == 0) return
            val bytes = currentBuffer.toByteArray()
            currentBuffer = ByteArrayOutputStream()
            segmentStartElapsedMs = SystemClock.elapsedRealtime()
            bytes
        }

        try {
        val wavBytes = pcm16ToWav(
            pcm = pcmBytes,
            sampleRate = provider.sampleRate,
            channels = 1,
            bitsPerSample = 16
        )
        val b64 = Base64.encodeToString(wavBytes, Base64.NO_WRAP)

        val body = buildMiMoAsrRequest(provider, "data:audio/wav;base64,$b64")
        val request = Request.Builder()
            .url("${provider.baseUrl}/chat/completions")
            .header("api-key", provider.apiKey)
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE)).build()
        partialTranscript = ""
        val text = if (provider.streaming) {
            var finished = false
            httpClient.speechSseFlow(request).collect { event ->
                when (event) {
                    is SseEvent.Event -> if (event.data.trim() != "[DONE]") {
                        val root = JSONObject(event.data)
                        check(!root.has("error")) { root.optJSONObject("error")?.optString("message") ?: "MiMo ASR 返回错误。" }
                        val choice = root.optJSONArray("choices")?.optJSONObject(0)
                        partialTranscript += choice?.optJSONObject("delta")?.optString("content").orEmpty()
                        publishTranscript()
                        val reason = choice?.optString("finish_reason").orEmpty()
                        check(reason !in listOf("length", "content_filter")) { "MiMo 转写未完整完成，可重试。" }
                        if (reason == "stop") finished = true
                    }
                    is SseEvent.Failure -> throw event.throwable ?: IOException("语音连接中断。")
                    else -> Unit
                }
            }
            check(finished) { "转写连接提前关闭，可重试。" }
            partialTranscript.trim()
        } else httpClient.newCall(request).awaitAndUse { response ->
            check(response.isSuccessful) { "MiMo ASR HTTP ${response.code}" }
            val root = JSONObject(response.body.string())
            check(!root.has("error")) { root.optJSONObject("error")?.optString("message") ?: "MiMo ASR 返回错误。" }
            val choice = root.optJSONArray("choices")?.optJSONObject(0) ?: error("MiMo 未返回转写结果。")
            check(choice.optString("finish_reason") == "stop") { "MiMo 转写未完整完成，可重试。" }
            choice.optJSONObject("message")?.optString("content")?.trim().orEmpty()
        }
        if (text.isNotEmpty()) completedTranscripts.add(text)
        failedSegment = null
        partialTranscript = ""
        _state.update { it.copy(canRetry = false, errorMessage = null) }
        publishTranscript()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            failedSegment = pcmBytes
            _state.update { it.copy(canRetry = true) }
            throw e
        }
    }

    override fun retry() {
        if (state.value.isRecording) return
        _state.update { it.copy(errorMessage = null) }
        stop()
    }

    private fun publishTranscript() {
        val transcript = (completedTranscripts + partialTranscript)
            .filter { it.isNotBlank() }
            .joinToString(" ")
        _state.update { it.copy(transcript = transcript, errorMessage = null) }
        scope.launch { onTranscriptChange?.invoke(transcript) }
    }

    private fun setError(message: String) {
        _state.update {
            it.copy(
                status = ASRStatus.Error,
                errorMessage = message,
                canRetry = failedSegment != null || synchronized(bufferLock) { currentBuffer.size() > 0 },
            )
        }
    }

    private fun releaseRecorder() {
        recorderJob = null
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        audioRecord = null
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()

        /**
         * 把 raw PCM16 little-endian 数据封装成最小 WAV (RIFF/WAVE/fmt/data)。
         * MiMo 官方只接受 WAV/MP3, AudioRecord 输出的是 PCM, 必须自己包 WAV 头。
         */
        private fun pcm16ToWav(
            pcm: ByteArray,
            sampleRate: Int,
            channels: Int,
            bitsPerSample: Int
        ): ByteArray {
            val byteRate = sampleRate * channels * bitsPerSample / 8
            val blockAlign = channels * bitsPerSample / 8
            val dataSize = pcm.size
            val out = ByteArrayOutputStream(44 + dataSize)

            // RIFF header
            out.write("RIFF".toByteArray(Charsets.US_ASCII))
            writeIntLE(out, 36 + dataSize) // chunk size = file size - 8
            out.write("WAVE".toByteArray(Charsets.US_ASCII))
            // fmt chunk
            out.write("fmt ".toByteArray(Charsets.US_ASCII))
            writeIntLE(out, 16)            // PCM fmt chunk size
            writeShortLE(out, 1)           // audio format = PCM
            writeShortLE(out, channels)
            writeIntLE(out, sampleRate)
            writeIntLE(out, byteRate)
            writeShortLE(out, blockAlign)
            writeShortLE(out, bitsPerSample)
            // data chunk
            out.write("data".toByteArray(Charsets.US_ASCII))
            writeIntLE(out, dataSize)
            out.write(pcm)
            return out.toByteArray()
        }

        private fun writeIntLE(out: ByteArrayOutputStream, value: Int) {
            out.write(value and 0xFF)
            out.write((value shr 8) and 0xFF)
            out.write((value shr 16) and 0xFF)
            out.write((value shr 24) and 0xFF)
        }

        private fun writeShortLE(out: ByteArrayOutputStream, value: Int) {
            out.write(value and 0xFF)
            out.write((value shr 8) and 0xFF)
        }
    }
}
