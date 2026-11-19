package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.rerere.ai.provider.*
import me.rerere.rikkahub.ui.components.ui.FormItem
import me.rerere.rikkahub.ui.components.ui.Select
import me.rerere.rikkahub.ui.theme.CustomColors

@Composable
internal fun OpenAIOutputSettings(
    options: OpenAIGenerationOptions,
    formats: List<OpenAIResponseFormat>,
    enabled: Boolean,
    mimo: Boolean,
    chat: Boolean,
    update: ((OpenAIGenerationOptions) -> OpenAIGenerationOptions) -> Unit,
) {
    Card(colors = CustomColors.cardColorsOnSurfaceContainer) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("输出格式", style = MaterialTheme.typography.titleMedium)
            Select(options = formats, selectedOption = options.responseFormat.takeIf { it in formats } ?: OpenAIResponseFormat.AUTO,
                enabled = enabled, optionToString = {
                    when (it) { OpenAIResponseFormat.AUTO -> "默认"; OpenAIResponseFormat.TEXT -> "文本"
                        OpenAIResponseFormat.JSON_OBJECT -> "JSON 对象"; OpenAIResponseFormat.JSON_SCHEMA -> "JSON Schema" }
                }, onOptionSelected = { value -> update { it.copy(responseFormat = value) } }, modifier = Modifier.fillMaxWidth())
            if (options.responseFormat == OpenAIResponseFormat.JSON_SCHEMA && OpenAIResponseFormat.JSON_SCHEMA in formats) {
                val valid = remember(options.responseJsonSchema) { runCatching {
                    parseOutputSchema(options.responseJsonSchema).closedOutputSchema(requireAll = true)
                }.isSuccess }
                OutlinedTextField(value = options.responseSchemaName, onValueChange = { raw -> update { it.copy(responseSchemaName = raw) } },
                    label = { Text("结构名称") }, enabled = enabled, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = options.responseJsonSchema, onValueChange = { raw -> update { it.copy(responseJsonSchema = raw) } },
                    label = { Text("JSON Schema") }, enabled = enabled, isError = !valid, minLines = 4, modifier = Modifier.fillMaxWidth())
                Text("根节点使用 object；严格输出会关闭额外对象键，可选值允许返回 null。")
                if (!valid) Text("请填写有效的 Schema；任意对象键请改用 key/value 数组。", color = MaterialTheme.colorScheme.error)
            }
            if (OpenAIResponseFormat.JSON_SCHEMA !in formats) Text("此接口仅开放官方文档已声明的输出格式。JSON 对象的字段结构由提示词描述。")
            if (formats.size == 1) Text("当前供应商未声明支持独立的结构化输出参数，可在提示词中描述所需格式。")
        }
    }
    if (mimo) Card(colors = CustomColors.cardColorsOnSurfaceContainer) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("MiMo V2.6", style = MaterialTheme.typography.titleMedium)
            Text("思考仅支持开启或关闭；开启后温度和 Top P 使用供应商固定值。工具由模型自动选择。V2.5 已退役，请选用 V2.6 系列。")
            if (chat) {
            MimoPenalty("存在惩罚", options.presencePenalty, enabled) { value -> update { it.copy(presencePenalty = value) } }
            MimoPenalty("频率惩罚", options.frequencyPenalty, enabled) { value -> update { it.copy(frequencyPenalty = value) } }
            OutlinedTextField(value = options.stopSequences.joinToString("\n"),
                onValueChange = { raw -> update { it.copy(stopSequences = raw.lines().take(4)) } },
                label = { Text("停止序列（最多 4 项，每行一项）") }, enabled = enabled, minLines = 2, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun MimoPenalty(label: String, value: Float?, enabled: Boolean, update: (Float?) -> Unit) {
    var raw by remember(value) { mutableStateOf(value?.toString().orEmpty()) }
    val number = raw.toFloatOrNull()
    OutlinedTextField(value = raw, onValueChange = { input ->
        raw = input
        if (input.isBlank()) update(null) else input.toFloatOrNull()?.takeIf { it in -2f..2f }?.let(update)
    }, label = { Text("$label（-2 到 2）") }, enabled = enabled,
        isError = raw.isNotBlank() && (number == null || number !in -2f..2f), singleLine = true, modifier = Modifier.fillMaxWidth())
}
