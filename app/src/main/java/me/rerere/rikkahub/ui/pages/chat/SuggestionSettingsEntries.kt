package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.*

@Composable fun GlobalSuggestionSettingsEntry(settings: Settings, onChange: (ChatSuggestionConfig) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Text("全局默认设置可由助手和单个对话覆盖。", style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
    TextButton(onClick = { open = true }) { Text("生成时机、上下文与交互设置") }
    if (open) SuggestionSettingsDialog(settings.defaultSuggestionConfig(), settings.providers, onChange, { open = false })
}

@Composable fun AssistantSuggestionSettingsEntry(assistant: Assistant, settings: Settings, onUpdate: (Assistant) -> Unit) {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = true }) { Text("聊天建议：${if (assistant.suggestionSettings == null) "继承全局" else "独立设置"}") }
    if (open) SuggestionSettingsDialog(assistant.suggestionSettings ?: settings.defaultSuggestionConfig(), settings.providers,
        { onUpdate(assistant.copy(suggestionSettings = it)) }, { open = false }, { onUpdate(assistant.copy(suggestionSettings = null)) })
}
