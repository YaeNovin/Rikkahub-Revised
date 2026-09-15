package me.rerere.rikkahub.ui.pages.setting.components

import me.rerere.rikkahub.ui.components.ui.appearanceOutlinedTextFieldColors
import me.rerere.rikkahub.ui.components.ui.appearanceTextButtonColors

import android.content.Intent
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.ui.components.ui.AppearanceFormItem as FormItem
import me.rerere.rikkahub.ui.components.ui.SelectTextField
import me.rerere.rikkahub.ui.components.ui.OutlinedNumberInput
import me.rerere.tts.provider.*
import me.rerere.tts.provider.providers.MiniMaxTTSProvider

@Composable
internal fun SpeechText(label: String, value: String, description: String = "", secret: Boolean = false, onChange: (String) -> Unit) {
    FormItem(label = { Text(label) }, description = { if (description.isNotEmpty()) Text(description) }) {
        OutlinedTextField(colors = appearanceOutlinedTextFieldColors(), value = value, onValueChange = onChange, modifier = Modifier.fillMaxWidth(),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None)
    }
}

@Composable
internal fun SpeechChoice(label: String, value: String, options: List<String>, description: String = "", onChange: (String) -> Unit) {
    FormItem(label = { Text(label) }, description = { if (description.isNotEmpty()) Text(description) }) {
        SelectTextField(colors = appearanceOutlinedTextFieldColors(), value = value, options = options, readOnly = true, onValueChange = {}, onOptionSelected = onChange,
            modifier = Modifier.fillMaxWidth())
    }
}

@Composable
internal fun SpeechSwitch(label: String, value: Boolean, description: String = "", onChange: (Boolean) -> Unit) {
    FormItem(label = { Text(label) }, description = { if (description.isNotEmpty()) Text(description) },
        tail = { Switch(checked = value, onCheckedChange = onChange) })
}

@Composable
private fun SpeechFloat(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    FormItem(label = { Text(label) }, description = { Text("范围 ${range.start}–${range.endInclusive}") }) {
        OutlinedNumberInput(colors = appearanceOutlinedTextFieldColors(), value = value, onValueChange = { if (it.isFinite() && it in range) onChange(it) },
            modifier = Modifier.fillMaxWidth(), label = label)
    }
}

@Composable
private fun SpeechInt(label: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    FormItem(label = { Text(label) }, description = { Text("范围 ${range.first}–${range.last}") }) {
        OutlinedNumberInput(colors = appearanceOutlinedTextFieldColors(), value = value, onValueChange = { if (it in range) onChange(it) },
            modifier = Modifier.fillMaxWidth(), label = label)
    }
}

@Composable
internal fun MiMoSpeechOptions(setting: TTSProviderSetting.MiMo, onChange: (TTSProviderSetting) -> Unit) {
    val s = setting.normalized()
    val c = setting.capabilities()
    SpeechText("API Key", setting.apiKey, secret = true) { onChange(setting.copy(apiKey = it)) }
    SpeechText("服务地址", setting.baseUrl) { onChange(setting.copy(baseUrl = it)) }
    TtsSpeechModelPicker(setting, onChange)
    if (!c.recognized) { SpeechInfoText("未识别此模型，暂不显示或发送未经确认的语音参数。"); return }
    when (c.voiceInput) {
        SpeechVoiceInput.BUILT_IN -> SpeechChoice("内置音色", s.voice, c.voices) { onChange(s.copy(voice = it)) }
        SpeechVoiceInput.DESCRIPTION -> SpeechText("音色设计", s.voiceDesign, "必填：描述声音特征、角色或说话方式；不会被朗读。") { onChange(s.copy(voiceDesign = it)) }
        SpeechVoiceInput.AUDIO_SAMPLE -> MiMoVoiceSample(s, onChange)
        else -> Unit
    }
    if (c.supportsStyle) SpeechText("风格指令", s.styleInstruction,
        "可描述语速、情绪、口音与停顿，作为独立指令发送，不写入朗读正文。") { onChange(s.copy(styleInstruction = it)) }
    SpeechSwitch("流式返回", s.streaming,
        if (c.lowLatencyStreaming) "音频分片返回。流式格式为 PCM16，24 kHz 单声道。"
        else "该模型的流式接口可能在合成完成后一次返回，不代表实时首声。") {
        onChange(s.copy(streaming = it, format = if (it) "pcm16" else "wav").normalized())
    }
    SpeechChoice("音频格式", s.format, c.formats) { onChange(s.copy(format = it).normalized()) }
    if (c.supportsOptimizeText) SpeechSwitch("优化朗读文本", s.optimizeText,
        "可能改写实际朗读内容；关闭时保持原文。") { onChange(s.copy(optimizeText = it)) }
    SpeechInfoText("MiMo TTS 未提供独立语言、采样率或时间戳参数。语言和风格可在指令中描述。")
}

@Composable
private fun MiMoVoiceSample(setting: TTSProviderSetting.MiMo, onChange: (TTSProviderSetting) -> Unit) {
    val context = LocalContext.current
    val latest by rememberUpdatedState(setting)
    var error by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) try {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: "音色样本"
            onChange(latest.copy(referenceAudioUri = uri.toString(), referenceAudioName = name))
            error = null
        } catch (e: Exception) { error = e.localizedMessage ?: "无法访问该样本，请重新选择。" }
    }
    FormItem(label = { Text("自定义音色样本") }, description = { Text("选择有权使用的 MP3/WAV 音频，编码后须小于 10 MB；合成时发送给 MiMo。") }) {
        Column {
            TextButton(colors = appearanceTextButtonColors(), onClick = { picker.launch(arrayOf("audio/mpeg", "audio/mp3", "audio/wav", "audio/x-wav")) }) {
                Text(setting.referenceAudioName.ifBlank { "选择音频文件" })
            }
            if (setting.referenceAudioUri.isNotBlank()) TextButton(colors = appearanceTextButtonColors(), onClick = {
                onChange(setting.copy(referenceAudioUri = "", referenceAudioName = ""))
            }) { Text("清除样本") }
            error?.let { SpeechInfoText(it, error = true) }
        }
    }
}

@Composable
internal fun MiniMaxSpeechOptions(setting: TTSProviderSetting.MiniMax, onChange: (TTSProviderSetting) -> Unit) {
    val s = setting.normalized()
    val c = s.capabilities()
    var advanced by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var fetchedVoices by remember(setting.id, setting.baseUrl, setting.apiKey) { mutableStateOf(emptyList<String>()) }
    var fetching by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val latest by rememberUpdatedState(setting)
    SpeechText("API Key", setting.apiKey, secret = true) { onChange(setting.copy(apiKey = it)) }
    SpeechText("服务地址", setting.baseUrl) { onChange(setting.copy(baseUrl = it)) }
    TtsSpeechModelPicker(setting, onChange)
    FormItem(label = { Text("音色 ID") }, description = { Text("可使用系统音色、已创建的克隆音色或设计音色；可手动填写账号下的音色 ID。") }) {
        Column {
            SelectTextField(colors = appearanceOutlinedTextFieldColors(), value = setting.voiceId, options = (fetchedVoices + c.voices).distinct(),
                onValueChange = { onChange(setting.copy(voiceId = it)) }, onOptionSelected = { onChange(s.copy(voiceId = it)) },
                modifier = Modifier.fillMaxWidth())
            TextButton(colors = appearanceTextButtonColors(), enabled = !fetching && s.apiKey.isNotBlank(), onClick = {
                val requested = s
                fetching = true; error = null
                scope.launch {
                    try {
                        val voices = withContext(Dispatchers.IO) { MiniMaxTTSProvider().listVoices(requested) }
                        if (latest.apiKey == requested.apiKey && latest.baseUrl.trimEnd('/') == requested.baseUrl) fetchedVoices = voices
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (e: Exception) { error = e.localizedMessage ?: "音色获取失败，可手动输入。" }
                    finally { fetching = false }
                }
            }) { Text(if (fetching) "正在获取…" else "获取账号音色") }
            error?.let { SpeechInfoText(it, error = true) }
        }
    }
    if (!c.recognized) { SpeechInfoText("未识别此型号，仅发送基础语音请求；高级参数暂不开放。"); return }
    SpeechFloat("合成语速", s.speed, 0.5f..2f) { onChange(s.copy(speed = it)) }
    SpeechChoice("语言增强", s.languageBoost, c.languages,
        if (s.latexRead) "公式朗读开启时使用中文。" else "auto 自动识别；粤语使用 Chinese,Yue。") { onChange(s.copy(languageBoost = it).normalized()) }
    SpeechSwitch("流式返回", s.streaming) { onChange(s.copy(streaming = it).normalized()) }
    SpeechChoice("音频格式", s.format, c.formats) { onChange(s.copy(format = it).normalized()) }
    SpeechChoice("采样率", s.sampleRate.toString(),
        (if (s.format.startsWith("pcmu")) listOf(8000) else c.sampleRates).map(Int::toString),
        "μ-law 格式固定为 8000 Hz。") { onChange(s.copy(sampleRate = it.toInt()).normalized()) }
    TextButton(colors = appearanceTextButtonColors(), onClick = { advanced = !advanced }) { Text(if (advanced) "收起高级选项" else "高级选项") }
    if (!advanced) return
    SpeechFloat("合成音量", s.volume, 0.01f..10f) { onChange(s.copy(volume = it)) }
    SpeechInt("音调", s.pitch, -12..12) { onChange(s.copy(pitch = it)) }
    SpeechChoice("情绪", s.emotion.ifBlank { "自动" }, listOf("自动") + c.emotions) { onChange(s.copy(emotion = if (it == "自动") "" else it)) }
    SpeechChoice("声道", s.channels.toString(), listOf("1", "2"), "1 为单声道，2 为立体声。") { onChange(s.copy(channels = it.toInt())) }
    if (s.format == "mp3") {
        SpeechChoice("码率", s.bitrate.toString(), listOf("32000", "64000", "128000", "256000")) { onChange(s.copy(bitrate = it.toInt())) }
        if (s.streaming) SpeechSwitch("固定码率", s.forceCbr) { onChange(s.copy(forceCbr = it)) }
    }
    SpeechSwitch("数字文本规范化", s.textNormalization, "改善数字读法，可能略增加合成等待。") { onChange(s.copy(textNormalization = it)) }
    SpeechSwitch("朗读 LaTeX 公式", s.latexRead, "仅支持中文，公式需要用双美元符号包裹；开启后语言自动设为中文。") { onChange(s.copy(latexRead = it).normalized()) }
    SpeechText("发音词典", s.pronunciationRules, "每行 原文/读法，例如 omg/oh my god。") { onChange(s.copy(pronunciationRules = it)) }
    if (c.supportsSubtitles) {
        SpeechSwitch("返回字幕时间戳", s.subtitleEnabled, "保留供应商返回的字幕信息，可从播放详情查看。") { onChange(s.copy(subtitleEnabled = it)) }
        if (s.subtitleEnabled) SpeechChoice("字幕粒度", s.subtitleType,
            if (s.streaming) listOf("sentence", "word", "word_streaming") else listOf("sentence", "word"),
            "sentence 按句，word 按词；word_streaming 仅流式可用。") { onChange(s.copy(subtitleType = it).normalized()) }
    }
    if (c.supportsVoiceEffects) {
        SpeechInt("音色明亮度", s.voiceModifyPitch, -100..100) { onChange(s.copy(voiceModifyPitch = it)) }
        SpeechInt("音色柔和度", s.voiceModifyIntensity, -100..100) { onChange(s.copy(voiceModifyIntensity = it)) }
        SpeechInt("音色清脆度", s.voiceModifyTimbre, -100..100) { onChange(s.copy(voiceModifyTimbre = it)) }
        SpeechChoice("声音效果", s.soundEffect.ifBlank { "无" }, listOf("无", "spacious_echo", "auditorium_echo", "lofi_telephone", "robotic")) {
            onChange(s.copy(soundEffect = if (it == "无") "" else it))
        }
    }
}
