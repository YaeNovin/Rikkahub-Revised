package me.rerere.asr.providers

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.rerere.asr.ASRController
import me.rerere.asr.ASRProviderSetting
import me.rerere.asr.ASRState
import me.rerere.asr.ASRStatus
import me.rerere.asr.TranscriptAccumulator
import me.rerere.asr.appendAmplitude
import me.rerere.asr.calculateRmsAmplitude
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

private const val TAG = "OpenAIRealtimeASR"
private const val MAX_WEBSOCKET_QUEUE_BYTES = 2_000_000L

class OpenAIRealtimeASRController(
    private val context: Context,
    private val httpClient: OkHttpClient,
    private val provider: ASRProviderSetting.OpenAIRealtime
) : ASRController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(ASRState(isAvailable = true))
    override val state: StateFlow<ASRState> = _state.asStateFlow()

    private var webSocket: WebSocket? = null
    private var recorderJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private var onTranscriptChange: ((String) -> Unit)? = null
    private val transcripts = TranscriptAccumulator()
    private var stopJob: Job? = null
    @Volatile private var finishing = false
    @Volatile private var finishAcknowledged = false
    @Volatile private var sentAudio = false
    @Volatile private var awaitingCommit = false
    @Volatile private var generation = 0

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
        stopJob?.cancel()
        webSocket?.cancel()
        val session = ++generation
        transcripts.clear()
        finishing = false
        finishAcknowledged = false
        sentAudio = false
        awaitingCommit = false
        _state.update {
            ASRState(
                status = ASRStatus.Connecting,
                isAvailable = true
            )
        }

        val request = Request.Builder()
            .url(provider.websocketEndpoint())
            .addHeader("Authorization", "Bearer ${provider.apiKey}")
            .build()

        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (session != generation || finishing) { webSocket.cancel(); return }
                webSocket.send(provider.sessionUpdateEvent().toString())
                _state.update { it.copy(status = ASRStatus.Listening, errorMessage = null) }
                startRecorder(provider, webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (session != generation) return
                handleServerEvent(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (session != generation) return
                Log.e(TAG, "Realtime ASR websocket failed", t)
                releaseRecorder()
                setError(t.message ?: "ASR websocket failed")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (session != generation) return
                releaseRecorder()
                _state.update {
                    it.copy(
                        status = if (it.errorMessage == null) ASRStatus.Idle else ASRStatus.Error
                    )
                }
            }
        })
    }

    override fun stop() {
        if (finishing) return
        finishing = true
        val recording = recorderJob
        recording?.cancel()
        releaseRecorder()
        val socket = webSocket
        if (socket != null) {
            _state.update { it.copy(status = ASRStatus.Stopping) }
            stopJob = scope.launch {
                recording?.join()
                awaitingCommit = sentAudio
                if (sentAudio) socket.send(JSONObject().put("type", "input_audio_buffer.commit").toString()) else finishAcknowledged = true
                val deadline = android.os.SystemClock.elapsedRealtime() + 10_000
                while (isActive && !finishAcknowledged && android.os.SystemClock.elapsedRealtime() < deadline) delay(50)
                if (!finishAcknowledged) setError("语音识别收尾超时，已保留收到的文字；最后一段可能不完整。")
                socket.close(1000, "stop")
                if (webSocket === socket) {
                    webSocket = null
                    _state.update { it.copy(status = if (it.errorMessage == null) ASRStatus.Idle else ASRStatus.Error) }
                }
            }
        } else {
            _state.update { it.copy(status = ASRStatus.Idle) }
        }
    }

    override fun dispose() {
        generation++
        stopJob?.cancel()
        recorderJob?.cancel()
        releaseRecorder()
        webSocket?.cancel()
        webSocket = null
        scope.cancel()
    }

    @SuppressLint("MissingPermission")
    private fun startRecorder(
        provider: ASRProviderSetting.OpenAIRealtime,
        socket: WebSocket
    ) {
        recorderJob?.cancel()
        recorderJob = scope.launch(Dispatchers.IO) {
            val minBufferSize = AudioRecord.getMinBufferSize(
                provider.sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBufferSize <= 0) {
                setError("设备不支持当前录音采样率或格式，请更换采样率。")
                return@launch
            }
            val bufferSize = minBufferSize
                .coerceAtLeast(provider.sampleRate / 10 * 2)
                .coerceAtLeast(4096)

            val recorder = try { AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                provider.sampleRate,
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
                while (isActive) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        val amplitude = calculateRmsAmplitude(buffer, read)
                        _state.update { it.copy(amplitudes = it.amplitudes.appendAmplitude(amplitude)) }
                        if (socket.queueSize() < MAX_WEBSOCKET_QUEUE_BYTES) {
                            val encoded = Base64.encodeToString(buffer, 0, read, Base64.NO_WRAP)
                            val event = JSONObject()
                                .put("type", "input_audio_buffer.append")
                                .put("audio", encoded)
                            check(socket.send(event.toString())) { "语音连接已关闭，已保留收到的文字。" }
                            sentAudio = true
                        } else {
                            throw IllegalStateException("网络发送过慢，录音已停止以避免丢失音频。已保留收到的文字，请重试。")
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
                socket.cancel()
            } finally {
                releaseRecorder(recorder)
            }
        }
    }

    private fun handleServerEvent(text: String) {
        val event = runCatching { JSONObject(text) }.getOrElse {
            Log.w(TAG, "Invalid realtime event: $text", it)
            return
        }

        when (val type = event.optString("type")) {
            "input_audio_buffer.committed" -> {
                transcripts.register(event.optString("item_id"), event.optString("previous_item_id"))
                awaitingCommit = false
            }
            "session.finished" -> { finishAcknowledged = true }
            "conversation.item.input_audio_transcription.delta" -> {
                val itemId = event.optString("item_id", "default")
                val delta = event.optString("delta")
                if (delta.isNotEmpty()) {
                    transcripts.update(itemId, delta, append = true)
                    publishTranscript()
                }
            }

            "conversation.item.input_audio_transcription.completed" -> {
                val itemId = event.optString("item_id", "default")
                val transcript = event.optString("transcript").trim()
                transcripts.update(itemId, transcript, final = true)
                if (finishing && !awaitingCommit && !transcripts.hasPending()) finishAcknowledged = true
                publishTranscript()
            }

            "error" -> {
                val error = event.optJSONObject("error")
                if (finishing && error?.optString("code") == "input_audio_buffer_commit_empty") {
                    awaitingCommit = false
                    finishAcknowledged = !transcripts.hasPending()
                    return
                }
                setError(error?.optString("message") ?: "ASR realtime error")
                recorderJob?.cancel()
                releaseRecorder()
                finishAcknowledged = true
                webSocket?.close(1000, "error")
            }

            else -> {
                Log.v(TAG, "Ignored realtime event: $type")
            }
        }
    }

    private fun publishTranscript() {
        val transcript = transcripts.text()
        _state.update { it.copy(transcript = transcript, errorMessage = null) }
        scope.launch {
            onTranscriptChange?.invoke(transcript)
        }
    }

    private fun setError(message: String) {
        _state.update {
            it.copy(
                status = ASRStatus.Error,
                errorMessage = message
            )
        }
    }

    @Synchronized private fun releaseRecorder(expected: AudioRecord? = audioRecord) {
        if (expected !== audioRecord) return
        recorderJob = null
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        audioRecord = null
    }
}

private fun ASRProviderSetting.OpenAIRealtime.websocketEndpoint(): String {
    val endpoint = websocketUrl.trim()
    if (endpoint.contains("intent=transcription")) return endpoint
    if (endpoint.contains("model=")) return endpoint
    val separator = if (endpoint.contains("?")) "&" else "?"
    return "${endpoint.trimEnd('/')}${separator}intent=transcription"
}

private fun ASRProviderSetting.OpenAIRealtime.sessionUpdateEvent(): JSONObject {
    val transcription = JSONObject()
        .put("model", model)
    if (language.isNotBlank()) transcription.put("language", language)
    if (prompt.isNotBlank()) transcription.put("prompt", prompt)

    return JSONObject()
        .put("type", "session.update")
        .put(
            "session",
            JSONObject()
                .put("type", "transcription")
                .put(
                    "audio",
                    JSONObject()
                        .put(
                            "input",
                            JSONObject()
                                .put(
                                    "format",
                                    JSONObject()
                                        .put("type", "audio/pcm")
                                        .put("rate", sampleRate)
                                )
                                .put("transcription", transcription)
                                .put(
                                    "noise_reduction",
                                    JSONObject()
                                        .put("type", "near_field")
                                )
                                .put(
                                    "turn_detection",
                                    JSONObject()
                                        .put("type", "server_vad")
                                        .put("threshold", vadThreshold)
                                        .put("prefix_padding_ms", prefixPaddingMs)
                                        .put("silence_duration_ms", silenceDurationMs)
                                )
                        )
                )
        )
}
