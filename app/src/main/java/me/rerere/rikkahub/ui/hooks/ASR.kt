package me.rerere.rikkahub.ui.hooks

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import me.rerere.asr.ASRStatus
import me.rerere.asr.ASRController
import me.rerere.asr.ASRProviderSetting
import me.rerere.asr.ASRState
import me.rerere.asr.providers.DashScopeASRController
import me.rerere.asr.providers.MiMoASRController
import me.rerere.asr.providers.OpenAIRealtimeASRController
import me.rerere.asr.providers.StepASRController
import me.rerere.asr.providers.VolcengineASRController
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getSelectedASRProvider
import okhttp3.OkHttpClient
import org.koin.compose.koinInject

@Composable
fun rememberCustomAsrState(): CustomAsrState {
    val context = LocalContext.current
    val settingsStore = koinInject<SettingsStore>()
    val httpClient = koinInject<OkHttpClient>()
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()

    val asrState = remember {
        CustomAsrStateImpl(context.applicationContext, httpClient)
    }

    DisposableEffect(settings.selectedASRProviderId, settings.asrProviders) {
        asrState.updateProvider(settings.getSelectedASRProvider())
        onDispose { }
    }

    DisposableEffect(asrState) {
        onDispose {
            asrState.cleanup()
        }
    }

    return asrState
}

interface CustomAsrState {
    val state: StateFlow<ASRState>
    fun start(onTranscriptChange: (String) -> Unit)
    fun stop()
    fun cancel()
    fun retry()
    fun cleanup()
}

private class CustomAsrStateImpl(
    private val context: Context,
    private val httpClient: OkHttpClient
) : CustomAsrState {
    private var controller: ASRController? = null
    private val idleState = MutableStateFlow(ASRState())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var collectJob: Job? = null
    private var selectedProvider: ASRProviderSetting? = null

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        .setAcceptsDelayedFocusGain(false)
        .setOnAudioFocusChangeListener { focus -> if (focus < 0) stop() }
        .build()

    override val state: StateFlow<ASRState>
        get() = idleState

    fun updateProvider(provider: ASRProviderSetting?) {
        if (selectedProvider == provider && controller != null) return
        selectedProvider = provider
        collectJob?.cancel()
        controller?.dispose()
        audioManager.abandonAudioFocusRequest(audioFocusRequest)
        controller = provider?.let { createController(it) }
        idleState.value = controller?.state?.value ?: ASRState()
        controller?.let { active ->
            collectJob = scope.launch {
                active.state.collect {
                    idleState.value = it
                    if (it.status == ASRStatus.Idle || it.status == ASRStatus.Error)
                        audioManager.abandonAudioFocusRequest(audioFocusRequest)
                }
            }
        }
    }

    override fun start(onTranscriptChange: (String) -> Unit) {
        // A new recording owns a fresh controller; callbacks from cancelled requests cannot edit its draft.
        cancel()
        val result = audioManager.requestAudioFocus(audioFocusRequest)
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            controller?.start(onTranscriptChange)
        } else {
            idleState.update { it.copy(status = ASRStatus.Error, errorMessage = "无法取得音频焦点，请暂停其他音频后重试。") }
        }
    }

    override fun stop() {
        controller?.stop()
        audioManager.abandonAudioFocusRequest(audioFocusRequest)
    }

    override fun cleanup() {
        collectJob?.cancel()
        controller?.dispose()
        controller = null
        scope.cancel()
        audioManager.abandonAudioFocusRequest(audioFocusRequest)
    }

    override fun cancel() {
        collectJob?.cancel()
        controller?.dispose()
        controller = null
        updateProvider(selectedProvider)
    }

    override fun retry() { controller?.retry() }

    private fun createController(provider: ASRProviderSetting): ASRController? {
        return when (provider) {
            is ASRProviderSetting.OpenAIRealtime -> {
                if (provider.apiKey.isBlank()) return null
                OpenAIRealtimeASRController(context, httpClient, provider)
            }

            is ASRProviderSetting.DashScope -> {
                if (provider.apiKey.isBlank()) return null
                DashScopeASRController(context, httpClient, provider)
            }

            is ASRProviderSetting.Volcengine -> {
                if (provider.apiKey.isBlank()) return null
                VolcengineASRController(context, httpClient, provider)
            }

            is ASRProviderSetting.MiMo -> {
                if (provider.apiKey.isBlank()) return null
                MiMoASRController(context, httpClient, provider)
            }

            is ASRProviderSetting.Step -> {
                if (provider.apiKey.isBlank()) return null
                StepASRController(context, httpClient, provider)
            }
        }
    }
}
