package me.rerere.tts.provider

import kotlinx.serialization.json.*
import me.rerere.asr.ASRProviderSetting
import org.junit.Assert.*
import org.junit.Test

class SpeechCapabilitiesTest {
    @Test fun miniMaxSwitchDropsUnsupportedEmotionAndLanguage() {
        val original = TTSProviderSetting.MiniMax(model = "speech-2.6-hd", emotion = "whisper", languageBoost = "Tamil")
        assertEquals("whisper", original.normalized().emotion)
        val switched = original.copy(model = "speech-02-hd").normalized()
        assertEquals("", switched.emotion)
        assertEquals("auto", switched.languageBoost)
        assertFalse(buildMiniMaxSpeechRequest(switched, "Hello")["voice_setting"]!!.jsonObject.containsKey("emotion"))
    }
    @Test fun formatConstraintsRemoveInapplicableWireParameters() {
        val input = TTSProviderSetting.MiniMax(format = "pcm", forceCbr = true, voiceModifyPitch = 50, subtitleEnabled = true)
        val body = buildMiniMaxSpeechRequest(input, "你好")
        assertFalse(body.containsKey("voice_modify"))
        assertFalse(body["audio_setting"]!!.jsonObject.containsKey("bitrate"))
        assertFalse(body["audio_setting"]!!.jsonObject.containsKey("force_cbr"))
        assertEquals(true, body["subtitle_enable"]!!.jsonPrimitive.boolean)
    }
    @Test fun numericAndSubtitleValuesAreNormalizedTogether() {
        val s = TTSProviderSetting.MiniMax(speed = Float.NaN, volume = -9f, sampleRate = 1,
            pitch = 99, format = "pcmu_raw", streaming = false, subtitleType = "word_streaming").normalized()
        assertEquals(1f, s.speed)
        assertEquals(0.01f, s.volume)
        assertEquals(8000, s.sampleRate)
        assertEquals(12, s.pitch)
        assertEquals("sentence", s.subtitleType)
    }
    @Test fun latexReadingForcesChineseAndDictionaryIsStructured() {
        val s = TTSProviderSetting.MiniMax(latexRead = true, languageBoost = "English", pronunciationRules = "omg/oh my god\n重行/重新行走")
        val body = buildMiniMaxSpeechRequest(s, "公式")
        assertEquals("Chinese", body["language_boost"]!!.jsonPrimitive.content)
        assertEquals(2, body["pronunciation_dict"]!!.jsonObject["tone"]!!.jsonArray.size)
    }
    @Test fun unknownMiniMaxModelDoesNotInheritAdvancedParameters() {
        val body = buildMiniMaxSpeechRequest(TTSProviderSetting.MiniMax(model = "proxy-new-model", subtitleEnabled = true), "Hello")
        assertFalse(body.containsKey("audio_setting"))
        assertFalse(body.containsKey("subtitle_enable"))
        assertEquals(setOf("voice_id"), body["voice_setting"]!!.jsonObject.keys)
    }
    @Test fun mimoDesignUsesUserInstructionAndNoVoiceField() {
        val s = TTSProviderSetting.MiMo(model = "mimo-v2.5-tts-voicedesign", voice = "Mia",
            voiceDesign = "温柔的女声", styleInstruction = "缓慢朗读", optimizeText = true)
        val body = buildMiMoSpeechRequest(s, "你好")
        assertFalse(body["audio"]!!.jsonObject.containsKey("voice"))
        assertEquals("user", body["messages"]!!.jsonArray[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("你好", body["messages"]!!.jsonArray[1].jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals(true, body["audio"]!!.jsonObject["optimize_text_preview"]!!.jsonPrimitive.boolean)
    }
    @Test fun leavingDesignClearsExclusiveSettings() {
        val s = TTSProviderSetting.MiMo(voiceDesign = "voice", referenceAudioUri = "content://sample", optimizeText = true,
            voice = "unknown", format = "mp3", streaming = true).normalized()
        assertEquals("", s.voiceDesign)
        assertEquals("", s.referenceAudioUri)
        assertFalse(s.optimizeText)
        assertEquals("mimo_default", s.voice)
        assertEquals("pcm16", s.format)
    }
    @Test fun cloneUsesSampleAndOmitsBuiltInId() {
        val sample = "data:audio/wav;base64,YWJj"
        val body = buildMiMoSpeechRequest(TTSProviderSetting.MiMo(model = "mimo-v2.5-tts-voiceclone"), "text", sample)
        assertEquals(sample, body["audio"]!!.jsonObject["voice"]!!.jsonPrimitive.content)
        assertFalse(body["audio"]!!.jsonObject.containsKey("optimize_text_preview"))
    }
    @Test(expected = IllegalArgumentException::class) fun cloneRejectsMissingSample() {
        buildMiMoSpeechRequest(TTSProviderSetting.MiMo(model = "mimo-v2.5-tts-voiceclone"), "text")
    }
    @Test fun asrOnlySendsDocumentedOptions() {
        val body = buildMiMoAsrRequest(ASRProviderSetting.MiMo(language = "fr", sampleRate = 44100, streaming = true), "data:audio/wav;base64,YWJj")
        assertEquals(setOf("language"), body["asr_options"]!!.jsonObject.keys)
        assertEquals("auto", body["asr_options"]!!.jsonObject["language"]!!.jsonPrimitive.content)
        assertEquals(true, body["stream"]!!.jsonPrimitive.boolean)
        assertFalse(body.containsKey("sample_rate"))
    }
}
