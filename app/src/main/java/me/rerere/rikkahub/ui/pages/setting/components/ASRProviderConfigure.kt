package me.rerere.rikkahub.ui.pages.setting.components

import me.rerere.rikkahub.ui.components.ui.appearanceOutlinedTextFieldColors

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.asr.ASRProviderSetting
import me.rerere.tts.provider.normalized
import me.rerere.tts.provider.capabilities
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.ui.AppearanceFormItem as FormItem
import me.rerere.rikkahub.ui.components.ui.OutlinedNumberInput

@Composable
fun ASRProviderConfigure(
    setting: ASRProviderSetting,
    modifier: Modifier = Modifier,
    onValueChange: (ASRProviderSetting) -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.verticalScroll(rememberScrollState())
    ) {
        FormItem(
            label = { Text(stringResource(R.string.setting_asr_configure_provider_type)) },
            description = { Text(stringResource(R.string.setting_asr_configure_provider_type_desc)) }
        ) {
            OutlinedTextField(
                colors = appearanceOutlinedTextFieldColors(),
                value = when (setting) {
                    is ASRProviderSetting.OpenAIRealtime -> "OpenAI Realtime"
                    is ASRProviderSetting.DashScope -> "DashScope"
                    is ASRProviderSetting.Volcengine -> "Volcengine"
                    is ASRProviderSetting.MiMo -> "MiMo"
                    is ASRProviderSetting.Step -> "Step"
                },
                onValueChange = {},
                readOnly = true,
                modifier = Modifier.fillMaxWidth()
            )
        }

        FormItem(
            label = { Text(stringResource(R.string.setting_asr_configure_name)) },
            description = { Text(stringResource(R.string.setting_asr_configure_name_desc)) }
        ) {
            OutlinedTextField(
                colors = appearanceOutlinedTextFieldColors(),
                value = setting.name,
                onValueChange = { onValueChange(setting.copyProvider(name = it)) },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("OpenAI Realtime") }
            )
        }

        when (setting) {
            is ASRProviderSetting.OpenAIRealtime -> OpenAIRealtimeASRConfiguration(setting, onValueChange)
            is ASRProviderSetting.DashScope -> DashScopeASRConfiguration(setting, onValueChange)
            is ASRProviderSetting.Volcengine -> VolcengineASRConfiguration(setting, onValueChange)
            is ASRProviderSetting.MiMo -> MiMoASRConfiguration(setting, onValueChange)
            is ASRProviderSetting.Step -> StepASRConfiguration(setting, onValueChange)
        }
    }
}

@Composable
private fun OpenAIRealtimeASRConfiguration(
    setting: ASRProviderSetting.OpenAIRealtime,
    onValueChange: (ASRProviderSetting) -> Unit
) {
    FormItem(
        label = { Text(stringResource(R.string.setting_asr_configure_api_key)) },
        description = { Text(stringResource(R.string.setting_asr_configure_openai_api_key_desc)) }
    ) {
        OutlinedTextField(
            colors = appearanceOutlinedTextFieldColors(),
            value = setting.apiKey,
            onValueChange = { onValueChange(setting.copy(apiKey = it)) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("sk-...") }
        )
    }

    FormItem(
        label = { Text(stringResource(R.string.setting_asr_configure_websocket_url)) },
        description = { Text(stringResource(R.string.setting_asr_configure_openai_websocket_desc)) }
    ) {
        OutlinedTextField(
            colors = appearanceOutlinedTextFieldColors(),
            value = setting.websocketUrl,
            onValueChange = { onValueChange(setting.copy(websocketUrl = it)) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("wss://api.openai.com/v1/realtime?intent=transcription") }
        )
    }

    AsrSpeechModelPicker(setting, onValueChange)

    FormItem(
        label = { Text(stringResource(R.string.setting_asr_configure_language)) },
        description = { Text(stringResource(R.string.setting_asr_configure_language_iso_desc)) }
    ) {
        OutlinedTextField(
            colors = appearanceOutlinedTextFieldColors(),
            value = setting.language,
            onValueChange = { onValueChange(setting.copy(language = it)) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("auto") }
        )
    }

    FormItem(
        label = { Text(stringResource(R.string.setting_asr_configure_prompt)) },
        description = { Text(stringResource(R.string.setting_asr_configure_prompt_desc)) }
    ) {
        OutlinedTextField(
            colors = appearanceOutlinedTextFieldColors(),
            value = setting.prompt,
            onValueChange = { onValueChange(setting.copy(prompt = it)) },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            placeholder = { Text(stringResource(R.string.optional)) }
        )
    }

    FormItem(
        label = { Text(stringResource(R.string.setting_asr_configure_vad_threshold)) },
        description = { Text(stringResource(R.string.setting_asr_configure_vad_desc)) }
    ) {
        OutlinedNumberInput(
            colors = appearanceOutlinedTextFieldColors(),
            value = setting.vadThreshold,
            onValueChange = { value ->
                if (value in 0.0f..1.0f) {
                    onValueChange(setting.copy(vadThreshold = value))
                }
            },
            modifier = Modifier.fillMaxWidth(),
            label = "VAD Threshold"
        )
    }

    FormItem(
        label = { Text(stringResource(R.string.setting_asr_configure_prefix_padding)) },
        description = { Text(stringResource(R.string.setting_asr_configure_prefix_padding_desc)) }
    ) {
        OutlinedNumberInput(
            colors = appearanceOutlinedTextFieldColors(),
            value = setting.prefixPaddingMs,
            onValueChange = { value ->
                if (value in 0..2000) {
                    onValueChange(setting.copy(prefixPaddingMs = value))
                }
            },
            modifier = Modifier.fillMaxWidth(),
            label = "Prefix Padding"
        )
    }

    FormItem(
        label = { Text(stringResource(R.string.setting_asr_configure_silence_duration)) },
        description = { Text(stringResource(R.string.setting_asr_configure_silence_duration_desc)) }
    ) {
        OutlinedNumberInput(
            colors = appearanceOutlinedTextFieldColors(),
            value = setting.silenceDurationMs,
            onValueChange = { value ->
                if (value in 100..5000) {
                    onValueChange(setting.copy(silenceDurationMs = value))
                }
            },
            modifier = Modifier.fillMaxWidth(),
            label = "Silence Duration"
        )
    }
}

@Composable
private fun DashScopeASRConfiguration(
    setting: ASRProviderSetting.DashScope,
    onValueChange: (ASRProviderSetting) -> Unit
) {
    FormItem(
        label = { Text(stringResource(R.string.setting_asr_configure_api_key)) },
        description = { Text(stringResource(R.string.setting_asr_configure_dashscope_api_key_desc)) }
    ) {
        OutlinedTextField(
            colors = appearanceOutlinedTextFieldColors(),
            value = setting.apiKey,
            onValueChange = { onValueChange(setting.copy(apiKey = it)) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("sk-...") }
        )
    }

    FormItem(
        label = { Text(stringResource(R.string.setting_asr_configure_websocket_url)) },
        description = { Text(stringResource(R.string.setting_asr_configure_dashscope_websocket_desc)) }
    ) {
        OutlinedTextField(
            colors = appearanceOutlinedTextFieldColors(),
            value = setting.websocketUrl,
            onValueChange = { onValueChange(setting.copy(websocketUrl = it)) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("wss://dashscope.aliyuncs.com/api-ws/v1/realtime") }
        )
    }

    AsrSpeechModelPicker(setting, onValueChange)

    FormItem(
        label = { Text(stringResource(R.string.setting_asr_configure_language)) },
        description = { Text(stringResource(R.string.setting_asr_configure_language_iso_desc)) }
    ) {
        OutlinedTextField(
            colors = appearanceOutlinedTextFieldColors(),
            value = setting.language,
            onValueChange = { onValueChange(setting.copy(language = it)) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("zh") }
        )
    }

    FormItem(
        label = { Text(stringResource(R.string.setting_asr_configure_vad_threshold)) },
        description = { Text(stringResource(R.string.setting_asr_configure_dashscope_vad_desc)) }
    ) {
        OutlinedNumberInput(
            colors = appearanceOutlinedTextFieldColors(),
            value = setting.vadThreshold,
            onValueChange = { value ->
                if (value in 0.0f..1.0f) {
                    onValueChange(setting.copy(vadThreshold = value))
                }
            },
            modifier = Modifier.fillMaxWidth(),
            label = "VAD Threshold"
        )
    }

    FormItem(
        label = { Text(stringResource(R.string.setting_asr_configure_silence_duration)) },
        description = { Text(stringResource(R.string.setting_asr_configure_silence_duration_desc)) }
    ) {
        OutlinedNumberInput(
            colors = appearanceOutlinedTextFieldColors(),
            value = setting.silenceDurationMs,
            onValueChange = { value ->
                if (value in 100..5000) {
                    onValueChange(setting.copy(silenceDurationMs = value))
                }
            },
            modifier = Modifier.fillMaxWidth(),
            label = "Silence Duration"
        )
    }
}

@Composable
private fun VolcengineASRConfiguration(
    setting: ASRProviderSetting.Volcengine,
    onValueChange: (ASRProviderSetting) -> Unit
) {
    FormItem(
        label = { Text(stringResource(R.string.setting_asr_configure_api_key)) },
        description = { Text(stringResource(R.string.setting_asr_configure_volcengine_api_key_desc)) }
    ) {
        OutlinedTextField(
            colors = appearanceOutlinedTextFieldColors(),
            value = setting.apiKey,
            onValueChange = { onValueChange(setting.copy(apiKey = it)) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("your-api-key") }
        )
    }

    FormItem(
        label = { Text(stringResource(R.string.setting_asr_configure_websocket_url)) },
        description = { Text(stringResource(R.string.setting_asr_configure_volcengine_websocket_desc)) }
    ) {
        OutlinedTextField(
            colors = appearanceOutlinedTextFieldColors(),
            value = setting.websocketUrl,
            onValueChange = { onValueChange(setting.copy(websocketUrl = it)) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("wss://openspeech.bytedance.com/api/v3/sauc/bigmodel") }
        )
    }

    FormItem(
        label = { Text(stringResource(R.string.setting_asr_configure_resource_id)) },
        description = { Text(stringResource(R.string.setting_asr_configure_resource_id_desc)) }
    ) {
        OutlinedTextField(
            colors = appearanceOutlinedTextFieldColors(),
            value = setting.resourceId,
            onValueChange = { onValueChange(setting.copy(resourceId = it)) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("volc.bigasr.sauc.duration") }
        )
    }

    FormItem(
        label = { Text(stringResource(R.string.setting_asr_configure_language)) },
        description = { Text(stringResource(R.string.setting_asr_configure_language_code_desc)) }
    ) {
        OutlinedTextField(
            colors = appearanceOutlinedTextFieldColors(),
            value = setting.language,
            onValueChange = { onValueChange(setting.copy(language = it)) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("auto") }
        )
    }
}

@Composable
private fun MiMoASRConfiguration(setting: ASRProviderSetting.MiMo, onValueChange: (ASRProviderSetting) -> Unit) {
    val s = setting.normalized()
    val c = setting.capabilities()
    SpeechText("API Key", setting.apiKey, secret = true) { onValueChange(setting.copy(apiKey = it)) }
    SpeechText("服务地址", setting.baseUrl) { onValueChange(setting.copy(baseUrl = it)) }
    AsrSpeechModelPicker(setting, onValueChange)
    if (!c.recognized) { SpeechInfoText("请重新选择支持的 MiMo ASR 型号。"); return }
    SpeechChoice("识别语言", s.language, c.languages, "auto 自动检测，zh 中文，en 英文。") {
        onValueChange(s.copy(language = it).normalized())
    }
    SpeechSwitch("流式返回文字", s.streaming, "每段录音上传后逐步显示识别结果，不是实时音频上传。") {
        onValueChange(s.copy(streaming = it))
    }
    SpeechChoice("本地录音采样率", s.sampleRate.toString(), c.sampleRates.map(Int::toString),
        "仅影响本地 WAV 录音，不作为模型参数发送。设备必须支持所选采样率。") {
        onValueChange(s.copy(sampleRate = it.toInt()).normalized())
    }
    FormItem(label = { Text("录音分段（秒）") }, description = { Text("0 表示停止时提交；任何模式均会按大小限制自动切段。") }) {
        OutlinedNumberInput(colors = appearanceOutlinedTextFieldColors(), value = s.segmentDurationSec, onValueChange = {
            if (it in 0..180) onValueChange(s.copy(segmentDurationSec = it))
        }, modifier = Modifier.fillMaxWidth(), label = "0–180 秒")
    }
    SpeechInfoText("支持 MP3/WAV 输入。官方尚未提供时间戳、说话人区分、热词或风格指令参数。")
}

@Composable
private fun StepASRConfiguration(
    setting: ASRProviderSetting.Step,
    onValueChange: (ASRProviderSetting) -> Unit
) {
    FormItem(
        label = { Text(stringResource(R.string.setting_asr_configure_api_key)) },
        description = { Text(stringResource(R.string.setting_asr_configure_step_api_key_desc)) }
    ) {
        OutlinedTextField(
            colors = appearanceOutlinedTextFieldColors(),
            value = setting.apiKey,
            onValueChange = { onValueChange(setting.copy(apiKey = it)) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("your-stepfun-api-key") }
        )
    }

    FormItem(
        label = { Text(stringResource(R.string.setting_asr_configure_base_url)) },
        description = { Text(stringResource(R.string.setting_asr_configure_step_base_url_desc)) }
    ) {
        OutlinedTextField(
            colors = appearanceOutlinedTextFieldColors(),
            value = setting.baseUrl,
            onValueChange = { onValueChange(setting.copy(baseUrl = it)) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("https://api.stepfun.com") }
        )
    }

    AsrSpeechModelPicker(setting, onValueChange)

    FormItem(
        label = { Text(stringResource(R.string.setting_asr_configure_language)) },
        description = { Text(stringResource(R.string.setting_asr_configure_step_language_desc)) }
    ) {
        OutlinedTextField(
            colors = appearanceOutlinedTextFieldColors(),
            value = setting.language,
            onValueChange = { onValueChange(setting.copy(language = it)) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("auto") }
        )
    }

    FormItem(
        label = { Text(stringResource(R.string.setting_asr_configure_sample_rate)) },
        description = { Text(stringResource(R.string.setting_asr_configure_step_sample_rate_desc)) }
    ) {
        OutlinedNumberInput(
            colors = appearanceOutlinedTextFieldColors(),
            value = setting.sampleRate,
            onValueChange = { value ->
                if (value in 8000..48000) {
                    onValueChange(setting.copy(sampleRate = value))
                }
            },
            modifier = Modifier.fillMaxWidth(),
            label = "Sample Rate"
        )
    }

    FormItem(
        label = { Text(stringResource(R.string.setting_asr_configure_segment_duration)) },
        description = { Text(stringResource(R.string.setting_asr_configure_step_segment_desc)) }
    ) {
        OutlinedNumberInput(
            colors = appearanceOutlinedTextFieldColors(),
            value = setting.segmentDurationSec,
            onValueChange = { value ->
                if (value in 0..300) {
                    onValueChange(setting.copy(segmentDurationSec = value))
                }
            },
            modifier = Modifier.fillMaxWidth(),
            label = "Segment Duration (s)"
        )
    }

    FormItem(
        label = { Text(stringResource(R.string.setting_asr_configure_step_itn)) },
        description = { Text(stringResource(R.string.setting_asr_configure_step_itn_desc)) }
    ) {
        androidx.compose.material3.Switch(
            checked = setting.enableItn,
            onCheckedChange = { onValueChange(setting.copy(enableItn = it)) }
        )
    }

    FormItem(
        label = { Text(stringResource(R.string.setting_asr_configure_step_timestamp)) },
        description = { Text(stringResource(R.string.setting_asr_configure_step_timestamp_desc)) }
    ) {
        androidx.compose.material3.Switch(
            checked = setting.enableTimestamp,
            onCheckedChange = { onValueChange(setting.copy(enableTimestamp = it)) }
        )
    }

    FormItem(
        label = { Text(stringResource(R.string.setting_asr_configure_step_hotwords)) },
        description = { Text(stringResource(R.string.setting_asr_configure_step_hotwords_desc)) }
    ) {
        OutlinedTextField(
            colors = appearanceOutlinedTextFieldColors(),
            // 用逗号分隔展示, 输入时按逗号 split 回 List
            value = setting.hotwords.joinToString(","),
            onValueChange = { text ->
                val list = text.split(",")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                onValueChange(setting.copy(hotwords = list))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.setting_asr_configure_hotwords_placeholder)) }
        )
    }
}
