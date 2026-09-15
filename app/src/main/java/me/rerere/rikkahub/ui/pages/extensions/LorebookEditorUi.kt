package me.rerere.rikkahub.ui.pages.extensions

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.model.*

internal fun lorebookSourceLabel(source: LorebookSourceFormat): String = when (source) {
    LorebookSourceFormat.NATIVE -> "应用原生"
    LorebookSourceFormat.SILLY_TAVERN -> "SillyTavern"
    LorebookSourceFormat.CHARACTER_CARD_V2 -> "角色卡 V2"
    LorebookSourceFormat.CHARACTER_CARD_V3 -> "角色卡 V3"
}

internal fun lorebookPositionLabel(position: InjectionPosition): String = when (position) {
    InjectionPosition.BEFORE_SYSTEM_PROMPT -> "系统提示词之前"
    InjectionPosition.AFTER_SYSTEM_PROMPT -> "系统提示词之后"
    InjectionPosition.TOP_OF_CHAT -> "聊天开头"
    InjectionPosition.BOTTOM_OF_CHAT -> "最新消息之前"
    InjectionPosition.AT_DEPTH -> "指定消息深度"
    InjectionPosition.OUTLET -> "命名插槽（Outlet）"
}

internal fun lorebookTriggerLabel(trigger: LorebookGenerationTrigger): String = when (trigger) {
    LorebookGenerationTrigger.NORMAL -> "正常回复"
    LorebookGenerationTrigger.CONTINUE -> "继续生成"
    LorebookGenerationTrigger.IMPERSONATE -> "代写用户消息"
    LorebookGenerationTrigger.SWIPE -> "另一个回答"
    LorebookGenerationTrigger.REGENERATE -> "重新生成"
    LorebookGenerationTrigger.QUIET -> "静默生成"
}

@Composable internal fun LorebookHint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable internal fun LorebookSectionTitle(title: String, description: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        LorebookHint(description)
    }
}

@Composable internal fun LorebookNumberField(
    label: String, value: String, range: IntRange?, description: String,
    enabled: Boolean = true, optional: Boolean = false, onChange: (String) -> Unit,
) {
    val number = value.toIntOrNull()
    val invalid = !(optional && value.isBlank()) && (number == null || (range != null && number !in range))
    OutlinedTextField(value, onChange, Modifier.fillMaxWidth(), enabled = enabled,
        label = { Text(label) }, singleLine = true, isError = enabled && invalid,
        keyboardOptions = KeyboardOptions(keyboardType = if (range == null) KeyboardType.Text else KeyboardType.Number),
        supportingText = { Text(if (enabled && invalid) "请输入${range?.let { " ${it.first}–${it.last} 范围内的" }.orEmpty()}整数。$description" else description) })
}
