package me.rerere.tts.controller

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.buffer
import me.rerere.tts.model.AudioChunk
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import me.rerere.tts.model.AudioFormat
import me.rerere.tts.model.PlaybackState
import me.rerere.tts.model.PlaybackStatus
import me.rerere.tts.model.TTSResponse
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class AudioPlayer(context: Context) {
    private val appContext = context.applicationContext
    private val noisyReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: android.content.Intent?) {
            if (intent?.action == android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY) pause()
        }
    }
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
    @Volatile private var streamTrack: android.media.AudioTrack? = null
    @Volatile private var streamPaused = false
    private var streamingFocus: android.media.AudioFocusRequest? = null
    private val player = ExoPlayer.Builder(context).build().apply {
        setAudioAttributes(androidx.media3.common.AudioAttributes.Builder()
            .setUsage(androidx.media3.common.C.USAGE_MEDIA)
            .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_SPEECH).build(), true)
        setHandleAudioBecomingNoisy(true)
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private var positionJob: Job? = null
    init {
        androidx.core.content.ContextCompat.registerReceiver(appContext, noisyReceiver,
            android.content.IntentFilter(android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    fun pause() {
        streamPaused = true
        runCatching { streamTrack?.pause() }
        player.pause()
        _playbackState.update { it.copy(status = PlaybackStatus.Paused) }
    }
    fun resume() {
        streamPaused = false
        if (streamTrack != null) runCatching {
            streamTrack?.playbackParams = android.media.PlaybackParams().setSpeed(_playbackState.value.speed)
            streamTrack?.play()
        } else player.play()
    }
    fun stop() {
        streamPaused = false
        runCatching { streamTrack?.pause(); streamTrack?.flush(); streamTrack?.stop() }
        player.stop()
    }
    fun clear() = player.clearMediaItems()
    fun release() {
        stopPositionUpdates(); scope.cancel(); player.release()
        runCatching { appContext.unregisterReceiver(noisyReceiver) }
    }
    fun seekBy(ms: Long) = player.seekTo(player.currentPosition + ms)
    fun setSpeed(speed: Float) {
        player.playbackParameters = PlaybackParameters(speed)
        if (!streamPaused) runCatching { streamTrack?.playbackParams = android.media.PlaybackParams().setSpeed(speed) }
        _playbackState.update { it.copy(speed = speed) }
    }

    /** Native PCM streaming. Backpressure comes from AudioTrack rather than buffering the whole response. */
    suspend fun playStreaming(flow: Flow<AudioChunk>) {
        val focus = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener { if (it < 0) pause() }
            .build()
        check(audioManager.requestAudioFocus(focus) == android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            "无法取得语音播放焦点，请暂停其他音频后重试。"
        }
        streamingFocus = focus
        _playbackState.update { it.copy(status = PlaybackStatus.Buffering, positionMs = 0, durationMs = 0, metadata = emptyMap()) }
        var track: android.media.AudioTrack? = null
        var bytesWritten = 0L
        var rate = 24000
        var channels = 1
        var remainder = byteArrayOf()
        try {
            withContext(Dispatchers.IO) {
                flow.buffer(2).flowOn(Dispatchers.IO).collect { chunk ->
                    currentCoroutineContext().ensureActive()
                    _playbackState.update { it.copy(metadata = it.metadata + chunk.metadata + ("streaming" to "true")) }
                    if (chunk.data.isNotEmpty()) {
                        check(chunk.format == AudioFormat.PCM) { "流式播放需要 PCM16 音频。" }
                        if (track == null) {
                            rate = chunk.sampleRate ?: 24000
                            channels = chunk.metadata["channels"]?.toIntOrNull()?.coerceIn(1, 2) ?: 1
                            val mask = if (channels == 2) android.media.AudioFormat.CHANNEL_OUT_STEREO else android.media.AudioFormat.CHANNEL_OUT_MONO
                            val minBuffer = android.media.AudioTrack.getMinBufferSize(rate, mask, android.media.AudioFormat.ENCODING_PCM_16BIT)
                            check(minBuffer > 0) { "设备不支持该语音采样率。" }
                            track = android.media.AudioTrack.Builder()
                                .setAudioAttributes(android.media.AudioAttributes.Builder()
                                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
                                .setAudioFormat(android.media.AudioFormat.Builder().setSampleRate(rate).setChannelMask(mask)
                                    .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT).build())
                                .setBufferSizeInBytes(minBuffer.coerceAtLeast(rate * channels / 5))
                                .setTransferMode(android.media.AudioTrack.MODE_STREAM).build()
                            streamTrack = track
                            runCatching { track!!.playbackParams = android.media.PlaybackParams().setSpeed(_playbackState.value.speed) }
                            if (streamPaused) track!!.pause() else track!!.play()
                        }
                        check(chunk.sampleRate == null || chunk.sampleRate == rate) { "音频流采样率发生变化。" }
                        val incoming = remainder + chunk.data
                        val alignedSize = incoming.size - incoming.size % (channels * 2)
                        val audioBytes = incoming.copyOfRange(0, alignedSize)
                        remainder = incoming.copyOfRange(alignedSize, incoming.size)
                        var offset = 0
                        while (offset < audioBytes.size) {
                            currentCoroutineContext().ensureActive()
                            while (streamPaused) { currentCoroutineContext().ensureActive(); delay(40) }
                            val count = track!!.write(audioBytes, offset, (audioBytes.size - offset).coerceAtMost(8192),
                                android.media.AudioTrack.WRITE_NON_BLOCKING)
                            check(count >= 0) { "语音播放失败：AudioTrack $count" }
                            if (count == 0) delay(10) else { offset += count; bytesWritten += count }
                            _playbackState.update { it.copy(status = if (streamPaused) PlaybackStatus.Paused else PlaybackStatus.Playing,
                                positionMs = (track!!.playbackHeadPosition.toLong() and 0xffffffffL) * 1000 / rate,
                                durationMs = bytesWritten * 1000 / (rate * channels * 2)) }
                        }
                    }
                }
                check(bytesWritten > 0) { "语音服务未返回音频。" }
                check(remainder.isEmpty()) { "语音服务返回了不完整的 PCM 音频帧。" }
                val frames = bytesWritten / (channels * 2)
                while ((track!!.playbackHeadPosition.toLong() and 0xffffffffL) < frames) {
                    currentCoroutineContext().ensureActive()
                    delay(20)
                    _playbackState.update { it.copy(
                        positionMs = (track!!.playbackHeadPosition.toLong() and 0xffffffffL) * 1000 / rate,
                        status = if (streamPaused) PlaybackStatus.Paused else PlaybackStatus.Playing) }
                }
            }
            _playbackState.update { it.copy(status = PlaybackStatus.Ended, positionMs = it.durationMs) }
        } finally {
            if (streamTrack === track) streamTrack = null
            runCatching { track?.stop(); track?.release() }
            audioManager.abandonAudioFocusRequest(focus)
            if (streamingFocus === focus) streamingFocus = null
        }
    }

    @OptIn(UnstableApi::class)
    suspend fun play(response: TTSResponse) = suspendCancellableCoroutine<Unit> { cont ->
        val bytes = if (response.format == AudioFormat.PCM) {
            pcmToWav(response.audioData, response.sampleRate ?: 24000, response.metadata["channels"]?.toIntOrNull() ?: 1)
        } else if (response.format == AudioFormat.MULAW) {
            pcmToWav(response.audioData, 8000, response.metadata["channels"]?.toIntOrNull() ?: 1, 8, 7)
        } else response.audioData

        val dataSourceFactory = DataSource.Factory { ByteArrayDataSource(bytes) }
        val mediaSource = ProgressiveMediaSource.Factory(dataSourceFactory)
            .createMediaSource(MediaItem.fromUri(Uri.EMPTY))

        player.setMediaSource(mediaSource)

        _playbackState.update {
            it.copy(
                status = PlaybackStatus.Buffering,
                positionMs = 0L,
                durationMs = (response.duration?.times(1000))?.toLong() ?: 0L,
                metadata = response.metadata,
                errorMessage = null,
            )
        }

        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_BUFFERING -> {
                        _playbackState.update { it.copy(status = PlaybackStatus.Buffering) }
                        stopPositionUpdates()
                    }
                    Player.STATE_READY -> {
                        val isPlaying = player.isPlaying
                        val duration = if (player.duration > 0) player.duration else playbackState.value.durationMs
                        _playbackState.update {
                            it.copy(
                                status = if (isPlaying) PlaybackStatus.Playing else PlaybackStatus.Paused,
                                durationMs = duration,
                                positionMs = player.currentPosition
                            )
                        }
                        if (isPlaying) startPositionUpdates() else stopPositionUpdates()
                    }
                    Player.STATE_ENDED -> {
                        stopPositionUpdates()
                        _playbackState.update {
                            it.copy(
                                status = PlaybackStatus.Ended,
                                positionMs = player.duration.coerceAtLeast(it.positionMs),
                                durationMs = if (player.duration > 0) player.duration else it.durationMs
                            )
                        }
                        player.removeListener(this)
                        if (cont.isActive) cont.resume(Unit)
                    }
                    Player.STATE_IDLE -> {
                        stopPositionUpdates()
                        _playbackState.update { it.copy(status = PlaybackStatus.Idle) }
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                player.removeListener(this)
                stopPositionUpdates()
                _playbackState.update { it.copy(status = PlaybackStatus.Error, errorMessage = error.message) }
                if (cont.isActive) cont.resumeWithException(error)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (player.playbackState == Player.STATE_ENDED || player.playbackState == Player.STATE_IDLE) return
                val status = if (isPlaying) PlaybackStatus.Playing else PlaybackStatus.Paused
                _playbackState.update { it.copy(status = status) }
                if (isPlaying) startPositionUpdates() else stopPositionUpdates()
            }
        }
        player.addListener(listener)
        player.prepare()
        player.play()
        cont.invokeOnCancellation {
            player.removeListener(listener)
            player.stop()
            stopPositionUpdates()
        }
    }

    private fun startPositionUpdates() {
        if (positionJob?.isActive == true) return
        positionJob = scope.launch(Dispatchers.Main.immediate) {
            while (true) {
                _playbackState.update {
                    it.copy(
                        positionMs = player.currentPosition,
                        durationMs = if (player.duration > 0) player.duration else it.durationMs
                    )
                }
                delay(100)
            }
        }
    }

    private fun stopPositionUpdates() {
        positionJob?.cancel()
        positionJob = null
    }

    private fun pcmToWav(
        pcm: ByteArray,
        sampleRate: Int,
        channels: Int = 1,
        bitsPerSample: Int = 16,
        encoding: Short = 1,
    ): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val out = ByteArrayOutputStream()
        with(out) {
            write("RIFF".toByteArray())
            write(intToBytes(36 + pcm.size))
            write("WAVE".toByteArray())
            write("fmt ".toByteArray())
            write(intToBytes(16))
            write(shortToBytes(encoding))
            write(shortToBytes(channels.toShort()))
            write(intToBytes(sampleRate))
            write(intToBytes(byteRate))
            write(shortToBytes((channels * bitsPerSample / 8).toShort()))
            write(shortToBytes(bitsPerSample.toShort()))
            write("data".toByteArray())
            write(intToBytes(pcm.size))
            write(pcm)
        }
        return out.toByteArray()
    }

    private fun intToBytes(value: Int) = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 24) and 0xFF).toByte()
    )

    private fun shortToBytes(value: Short) = byteArrayOf(
        (value.toInt() and 0xFF).toByte(),
        ((value.toInt() shr 8) and 0xFF).toByte()
    )
}
