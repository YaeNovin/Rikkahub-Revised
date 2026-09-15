package me.rerere.rikkahub.ui.pages.setting.components

import me.rerere.rikkahub.ui.components.ui.appearanceOutlinedTextFieldColors
import me.rerere.rikkahub.ui.components.ui.appearanceTextButtonColors

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import me.rerere.asr.ASRProviderSetting
import me.rerere.rikkahub.ui.components.ui.AppearanceFormItem as FormItem
import me.rerere.rikkahub.ui.components.ui.SelectTextField
import me.rerere.rikkahub.ui.components.ui.AppearanceOptionSurface
import me.rerere.tts.provider.*

@Composable
internal fun TtsSpeechModelPicker(setting: TTSProviderSetting, onChange: (TTSProviderSetting) -> Unit) {
    val source = setting.speechModelSource() ?: return
    val model = when (setting) {
        is TTSProviderSetting.OpenAI -> setting.model
        is TTSProviderSetting.Gemini -> setting.model
        is TTSProviderSetting.MiMo -> setting.model
        is TTSProviderSetting.MiniMax -> setting.model
        is TTSProviderSetting.ElevenLabs -> setting.model
        is TTSProviderSetting.Groq -> setting.model
        is TTSProviderSetting.Step -> setting.model
        is TTSProviderSetting.Qwen -> setting.model
        is TTSProviderSetting.FishAudio -> setting.model
        else -> return
    }
    SpeechModelPicker(source, model) { value, selected ->
        onChange(when (setting) {
            is TTSProviderSetting.OpenAI -> setting.copy(model = value)
            is TTSProviderSetting.Gemini -> setting.copy(model = value)
            is TTSProviderSetting.MiMo -> setting.copy(model = value).let {
                if (selected || it.capabilities().recognized) it.normalized() else it
            }
            is TTSProviderSetting.MiniMax -> setting.copy(model = value).let {
                if (selected || it.capabilities().recognized) it.normalized() else it
            }
            is TTSProviderSetting.ElevenLabs -> setting.copy(model = value)
            is TTSProviderSetting.Groq -> setting.copy(model = value)
            is TTSProviderSetting.Step -> setting.copy(model = value)
            is TTSProviderSetting.Qwen -> setting.copy(model = value)
            is TTSProviderSetting.FishAudio -> setting.copy(model = value)
            else -> setting
        })
    }
}

@Composable
internal fun AsrSpeechModelPicker(setting: ASRProviderSetting, onChange: (ASRProviderSetting) -> Unit) {
    val source = setting.speechModelSource() ?: return
    val model = when (setting) {
        is ASRProviderSetting.OpenAIRealtime -> setting.model
        is ASRProviderSetting.DashScope -> setting.model
        is ASRProviderSetting.MiMo -> setting.model
        is ASRProviderSetting.Step -> setting.model
        else -> return
    }
    SpeechModelPicker(source, model) { value, selected ->
        onChange(when (setting) {
            is ASRProviderSetting.OpenAIRealtime -> setting.copy(model = value)
            is ASRProviderSetting.DashScope -> setting.copy(model = value)
            is ASRProviderSetting.MiMo -> setting.copy(model = value).let {
                if (selected || it.capabilities().recognized) it.normalized() else it
            }
            is ASRProviderSetting.Step -> setting.copy(model = value)
            else -> setting
        })
    }
}

@Composable
private fun SpeechModelPicker(
    source: SpeechModelSource,
    model: String,
    onChange: (String, Boolean) -> Unit,
) {
    val catalog = remember { SpeechModelCatalog() }
    val scope = rememberCoroutineScope()
    val latestSource by rememberUpdatedState(source)
    var result by remember(source) { mutableStateOf<SpeechCatalogResult?>(null) }
    var error by remember(source) { mutableStateOf<String?>(null) }
    var fetching by remember(source) { mutableStateOf(false) }
    var job by remember(source) { mutableStateOf<Job?>(null) }
    var requestVersion by remember(source) { mutableIntStateOf(0) }
    DisposableEffect(source) { onDispose { job?.cancel() } }
    val choices = result?.models ?: source.presets.map { SpeechCatalogModel(it) }
    AppearanceOptionSurface(modifier = Modifier.fillMaxWidth()) {
        FormItem(
            modifier = Modifier.padding(12.dp),
            label = { Text(if (source.use == SpeechModelUse.SYNTHESIS) "语音合成模型" else "语音识别模型") },
            description = { Text("填写 API Key 后获取模型，再从下拉框选择；支持手动填写。") },
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                SelectTextField(
                    colors = appearanceOutlinedTextFieldColors(),
                    value = model, options = choices,
                    onValueChange = { onChange(it, false) },
                    onOptionSelected = { onChange(it.id, true) },
                    optionToString = { if (it.name == it.id) it.id else "${it.name} (${it.id})" },
                    modifier = Modifier.fillMaxWidth(),
                )
                FlowRow(itemVerticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    TextButton(
                        colors = appearanceTextButtonColors(),
                        enabled = !fetching && source.apiKey.isNotBlank() && source.unavailableReason == null,
                        onClick = {
                            fetching = true; error = null
                            val requested = source
                            val version = ++requestVersion
                            job = scope.launch {
                                try {
                                    val fetched = catalog.fetch(requested)
                                    if (latestSource == requested && requestVersion == version) result = fetched
                                } catch (e: CancellationException) { throw e }
                                catch (e: Exception) {
                                    // Provider bodies/URLs can echo credentials; show only controlled messages.
                                    if (latestSource == requested && requestVersion == version) error = if (e is IllegalStateException) {
                                        e.message?.takeIf { !it.contains(requested.apiKey) && it.length < 200 } ?: "模型列表格式无效，请检查接口。"
                                    } else "获取失败，请检查网络与服务地址后重试。"
                                } finally {
                                    if (latestSource == requested && requestVersion == version) fetching = false
                                }
                            }
                        },
                    ) {
                        if (fetching) {
                            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(if (fetching) "正在获取…" else "获取模型")
                    }
                    if (fetching) TextButton(colors = appearanceTextButtonColors(), onClick = { requestVersion++; job?.cancel(); fetching = false }) { Text("取消") }
                }
                source.unavailableReason?.let { SpeechInfoText(it) }
                error?.let { SpeechInfoText(it, error = true) }
                result?.let {
                    SpeechInfoText(
                        text = if (it.models.isEmpty()) "接口未返回适用于此语音服务的模型，当前配置保持不变。"
                        else "已获取 ${it.models.size} 个语音模型，已过滤 ${it.excludedCount} 个非语音或不兼容模型。",
                    )
                } ?: if (source.presets.isNotEmpty()) {
                    SpeechInfoText("当前下拉选项为内置预设。")
                } else Unit
            }
        }
    }
}
