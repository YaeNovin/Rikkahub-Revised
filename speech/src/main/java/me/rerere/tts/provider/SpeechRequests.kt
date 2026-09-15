package me.rerere.tts.provider

import kotlinx.serialization.json.*
import me.rerere.asr.ASRProviderSetting

fun buildMiniMaxSpeechRequest(raw: TTSProviderSetting.MiniMax, text: String): JsonObject {
    val s = raw.normalized()
    val c = s.capabilities()
    require(text.isNotBlank() && text.length < 10_000) { "MiniMax 朗读文本需要为 1–9999 个字符。" }
    return buildJsonObject {
        put("model", s.model); put("text", text); put("stream", s.streaming); put("output_format", "hex")
        if (s.streaming) putJsonObject("stream_options") { put("exclude_aggregated_audio", true) }
        putJsonObject("voice_setting") {
            put("voice_id", s.voiceId)
            if (c.recognized) {
                put("speed", s.speed); put("vol", s.volume); put("pitch", s.pitch)
                if (s.emotion.isNotEmpty()) put("emotion", s.emotion)
                put("text_normalization", s.textNormalization); put("latex_read", s.latexRead)
            }
        }
        if (c.recognized) {
            put("language_boost", s.languageBoost)
            putJsonObject("audio_setting") {
                put("format", s.format); put("sample_rate", s.sampleRate); put("channel", s.channels)
                if (s.format == "mp3") put("bitrate", s.bitrate)
                if (s.format == "mp3" && s.streaming) put("force_cbr", s.forceCbr)
            }
            val rules = s.pronunciationRules.lines().map(String::trim).filter(String::isNotEmpty)
            require(rules.all { it.substringBefore('/').isNotBlank() && it.contains('/') && it.substringAfter('/').isNotBlank() }) {
                "发音词典每行使用 原文/读法，例如 重行/重新行走。"
            }
            if (rules.isNotEmpty()) putJsonObject("pronunciation_dict") { putJsonArray("tone") { rules.forEach { add(it) } } }
            if (s.subtitleEnabled) {
                put("subtitle_enable", true); put("subtitle_type", s.subtitleType)
            }
            if (c.supportsVoiceEffects && (s.voiceModifyPitch != 0 || s.voiceModifyIntensity != 0 ||
                    s.voiceModifyTimbre != 0 || s.soundEffect.isNotEmpty())) {
                putJsonObject("voice_modify") {
                    put("pitch", s.voiceModifyPitch); put("intensity", s.voiceModifyIntensity); put("timbre", s.voiceModifyTimbre)
                    if (s.soundEffect.isNotEmpty()) put("sound_effects", s.soundEffect)
                }
            }
        }
    }
}

fun buildMiMoSpeechRequest(raw: TTSProviderSetting.MiMo, text: String, referenceAudio: String? = null): JsonObject {
    val s = raw.normalized()
    val c = s.capabilities()
    require(c.recognized) { "未识别的 MiMo TTS 模型，请选择支持的语音模型。" }
    require(text.isNotBlank()) { "朗读文本不能为空。" }
    if (c.voiceInput == SpeechVoiceInput.DESCRIPTION) require(s.voiceDesign.isNotBlank()) { "请填写音色设计描述。" }
    if (c.voiceInput == SpeechVoiceInput.AUDIO_SAMPLE) {
        require(!referenceAudio.isNullOrBlank()) { "请先选择 MP3/WAV 音色样本。" }
        require(referenceAudio.length <= 10 * 1024 * 1024) { "音色样本编码后不能超过 10 MB。" }
    }
    return buildJsonObject {
        put("model", s.model); put("stream", s.streaming)
        putJsonArray("messages") {
            val instruction = listOf(s.voiceDesign, s.styleInstruction).filter(String::isNotBlank).joinToString("\n")
            if (instruction.isNotBlank()) add(buildJsonObject { put("role", "user"); put("content", instruction) })
            add(buildJsonObject { put("role", "assistant"); put("content", text) })
        }
        putJsonObject("audio") {
            put("format", s.format)
            when (c.voiceInput) {
                SpeechVoiceInput.BUILT_IN -> put("voice", s.voice)
                SpeechVoiceInput.AUDIO_SAMPLE -> put("voice", referenceAudio)
                else -> Unit
            }
            if (c.supportsOptimizeText) put("optimize_text_preview", s.optimizeText)
        }
    }
}

fun buildMiMoAsrRequest(raw: ASRProviderSetting.MiMo, data: String, format: String = "wav"): JsonObject {
    val s = raw.normalized()
    require(s.capabilities().recognized) { "未识别的 MiMo ASR 模型。" }
    require(format in listOf("mp3", "wav")) { "MiMo 识别仅支持 MP3/WAV。" }
    require(data.length <= 10 * 1024 * 1024) { "音频编码后不能超过 10 MB，请缩短录音分段。" }
    return buildJsonObject {
        put("model", s.model); put("stream", s.streaming)
        putJsonArray("messages") {
            add(buildJsonObject {
                put("role", "user")
                putJsonArray("content") { add(buildJsonObject {
                    put("type", "input_audio")
                    putJsonObject("input_audio") { put("data", data); put("format", format) }
                }) }
            })
        }
        putJsonObject("asr_options") { put("language", s.language) }
    }
}
