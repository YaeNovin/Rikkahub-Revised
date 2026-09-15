package me.rerere.tts.provider

import me.rerere.asr.ASRProviderSetting

enum class SpeechVoiceInput { ID, BUILT_IN, DESCRIPTION, AUDIO_SAMPLE, NONE }

/** Shared by settings and request serializers. Documented provider capabilities, not chat modalities. */
data class SpeechCapabilities(
    val recognized: Boolean,
    val voiceInput: SpeechVoiceInput = SpeechVoiceInput.NONE,
    val voices: List<String> = emptyList(),
    val languages: List<String> = emptyList(),
    val formats: List<String> = emptyList(),
    val sampleRates: List<Int> = emptyList(),
    val emotions: List<String> = emptyList(),
    val supportsStyle: Boolean = false,
    val supportsSubtitles: Boolean = false,
    val supportsOptimizeText: Boolean = false,
    val supportsVoiceEffects: Boolean = false,
    val lowLatencyStreaming: Boolean = false,
)

object SpeechModels {
    val miniMax = listOf("speech-2.8-hd", "speech-2.8-turbo", "speech-2.6-hd", "speech-2.6-turbo",
        "speech-02-hd", "speech-02-turbo", "speech-01-hd", "speech-01-turbo")
    val miMoTts = listOf("mimo-v2.5-tts", "mimo-v2.5-tts-voicedesign", "mimo-v2.5-tts-voiceclone")
    val miMoAsr = listOf("mimo-v2.5-asr")
    val miniMaxLanguages = listOf("auto", "Chinese", "Chinese,Yue", "English", "Arabic", "Russian",
        "Spanish", "French", "Portuguese", "German", "Turkish", "Dutch", "Ukrainian", "Vietnamese",
        "Indonesian", "Japanese", "Italian", "Korean", "Thai", "Polish", "Romanian", "Greek", "Czech",
        "Finnish", "Hindi", "Bulgarian", "Danish", "Hebrew", "Malay", "Persian", "Slovak", "Swedish",
        "Croatian", "Filipino", "Hungarian", "Norwegian", "Slovenian", "Catalan", "Nynorsk", "Tamil", "Afrikaans")
    val miniMaxVoices = listOf("female-shaonv", "female-yujie", "female-chengshu", "female-tianmei",
        "male-qn-qingse", "male-qn-jingying", "English_expressive_narrator")
}

fun TTSProviderSetting.MiniMax.capabilities(): SpeechCapabilities {
    val id = model.trim().substringAfterLast('/').lowercase()
    val known = id in SpeechModels.miniMax
    val legacy = id.startsWith("speech-01") || id.startsWith("speech-02")
    return SpeechCapabilities(
        recognized = known, voiceInput = SpeechVoiceInput.ID, voices = SpeechModels.miniMaxVoices,
        languages = if (!known) emptyList() else SpeechModels.miniMaxLanguages.filterNot {
            legacy && it in listOf("Persian", "Filipino", "Tamil")
        },
        formats = if (known) listOf("mp3", "pcm", "flac", "wav", "pcmu_raw", "pcmu_wav", "opus") else listOf("mp3"),
        sampleRates = if (known) listOf(8000, 16000, 22050, 24000, 32000, 44100) else emptyList(),
        emotions = if (!known) emptyList() else listOf("happy", "sad", "angry", "fearful", "disgusted",
            "surprised", "calm") + if (id.startsWith("speech-2.6-")) listOf("fluent", "whisper") else emptyList(),
        supportsSubtitles = known,
        supportsVoiceEffects = known && (if (streaming) format == "mp3" else format in listOf("mp3", "wav", "flac")),
        lowLatencyStreaming = known,
    )
}

fun TTSProviderSetting.MiMo.capabilities(): SpeechCapabilities {
    val id = model.trim().substringAfterLast('/').lowercase()
    val known = id in SpeechModels.miMoTts || id == "mimo-v2-tts"
    val voiceMode = when (id) {
        "mimo-v2.5-tts-voicedesign" -> SpeechVoiceInput.DESCRIPTION
        "mimo-v2.5-tts-voiceclone" -> SpeechVoiceInput.AUDIO_SAMPLE
        "mimo-v2.5-tts", "mimo-v2-tts" -> SpeechVoiceInput.BUILT_IN
        else -> SpeechVoiceInput.NONE
    }
    return SpeechCapabilities(
        recognized = known, voiceInput = voiceMode,
        voices = if (voiceMode != SpeechVoiceInput.BUILT_IN) emptyList() else if (id == "mimo-v2-tts")
            listOf("mimo_default") else listOf("mimo_default", "冰糖", "茉莉", "苏打", "白桦", "Mia", "Chloe", "Milo", "Dean"),
        formats = if (!known || streaming) listOf("pcm16") else listOf("wav", "mp3", "pcm16"),
        supportsStyle = known, supportsOptimizeText = voiceMode == SpeechVoiceInput.DESCRIPTION,
        lowLatencyStreaming = id == "mimo-v2.5-tts",
    )
}

fun ASRProviderSetting.MiMo.capabilities() = SpeechCapabilities(
    recognized = model.trim().substringAfterLast('/').lowercase() in SpeechModels.miMoAsr,
    languages = if (model.trim().substringAfterLast('/').lowercase() in SpeechModels.miMoAsr) listOf("auto", "zh", "en") else emptyList(),
    // These are locally recorded WAV rates, not an ASR server parameter.
    sampleRates = listOf(8000, 16000, 24000, 48000),
)

fun TTSProviderSetting.MiniMax.normalized(): TTSProviderSetting.MiniMax {
    val caps = capabilities()
    val safeFormat = format.lowercase().takeIf { it in caps.formats } ?: "mp3"
    val effects = caps.recognized && if (streaming) safeFormat == "mp3" else safeFormat in listOf("mp3", "wav", "flac")
    return copy(
        model = model.trim(), baseUrl = baseUrl.trim().trimEnd('/'), voiceId = voiceId.trim().ifBlank { "female-shaonv" },
        speed = speed.takeIf(Float::isFinite)?.coerceIn(0.5f, 2f) ?: 1f,
        volume = volume.takeIf(Float::isFinite)?.coerceIn(0.01f, 10f) ?: 1f, pitch = pitch.coerceIn(-12, 12),
        emotion = emotion.takeIf { it in caps.emotions }.orEmpty(),
        languageBoost = if (latexRead && caps.recognized) "Chinese" else languageBoost.takeIf { it in caps.languages } ?: "auto",
        format = safeFormat, sampleRate = if (safeFormat.startsWith("pcmu")) 8000 else sampleRate.takeIf { it in caps.sampleRates } ?: 32000,
        bitrate = bitrate.takeIf { it in listOf(32000, 64000, 128000, 256000) } ?: 128000,
        channels = channels.coerceIn(1, 2), forceCbr = forceCbr && streaming && safeFormat == "mp3",
        subtitleEnabled = subtitleEnabled && caps.supportsSubtitles,
        subtitleType = subtitleType.takeIf { it in if (streaming) listOf("sentence", "word", "word_streaming") else listOf("sentence", "word") } ?: "sentence",
        voiceModifyPitch = if (effects) voiceModifyPitch.coerceIn(-100, 100) else 0,
        voiceModifyIntensity = if (effects) voiceModifyIntensity.coerceIn(-100, 100) else 0,
        voiceModifyTimbre = if (effects) voiceModifyTimbre.coerceIn(-100, 100) else 0,
        soundEffect = soundEffect.takeIf { effects && it in listOf("spacious_echo", "auditorium_echo", "lofi_telephone", "robotic") }.orEmpty(),
    )
}

fun TTSProviderSetting.MiMo.normalized(): TTSProviderSetting.MiMo {
    val caps = capabilities()
    return copy(
        model = model.trim(), baseUrl = baseUrl.trim().trimEnd('/'),
        voice = if (caps.voiceInput == SpeechVoiceInput.BUILT_IN) voice.takeIf { it in caps.voices } ?: "mimo_default" else "",
        format = format.lowercase().let { if (it == "pcm") "pcm16" else it }.takeIf { it in caps.formats } ?: caps.formats.first(),
        styleInstruction = if (caps.supportsStyle) styleInstruction else "",
        voiceDesign = if (caps.voiceInput == SpeechVoiceInput.DESCRIPTION) voiceDesign else "",
        referenceAudioUri = if (caps.voiceInput == SpeechVoiceInput.AUDIO_SAMPLE) referenceAudioUri else "",
        referenceAudioName = if (caps.voiceInput == SpeechVoiceInput.AUDIO_SAMPLE) referenceAudioName else "",
        optimizeText = optimizeText && caps.supportsOptimizeText,
    )
}

fun ASRProviderSetting.MiMo.normalized() = copy(
    model = model.trim(), baseUrl = baseUrl.trim().trimEnd('/'),
    language = language.trim().lowercase().takeIf { it in capabilities().languages } ?: "auto",
    sampleRate = sampleRate.takeIf { it in capabilities().sampleRates } ?: 16000,
    segmentDurationSec = segmentDurationSec.coerceIn(0, 180),
)
