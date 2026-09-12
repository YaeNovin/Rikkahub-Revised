package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.ui.components.ui.AppearanceAlertDialog

val LocalMessageSuggestionAction = staticCompositionLocalOf<((UIMessage, String) -> Unit)?> { null }

@Composable fun MessageSuggestionAction(message: UIMessage, onDismiss: () -> Unit) {
    val action = LocalMessageSuggestionAction.current ?: return
    val text = remember(message.id, message.parts) { message.toText() }
    if (text.isBlank()) return
    var showSelection by remember { mutableStateOf(false) }
    TextButton(onClick = { showSelection = true }) { Text("对此生成聊天建议") }
    if (showSelection) {
        var selected by remember(message.id) { mutableStateOf(TextFieldValue(text)) }
        AppearanceAlertDialog(onDismissRequest = { showSelection = false }, title = { Text("选择建议依据") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("可针对整条消息，或长按选择其中一段文字。", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(selected, { selected = it.copy(text = text) }, readOnly = true,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp))
            }
        }, confirmButton = { Row {
            TextButton(onClick = { action(message, ""); showSelection = false; onDismiss() }) { Text("整条消息") }
            TextButton(onClick = {
                action(message, text.substring(selected.selection.min, selected.selection.max))
                showSelection = false; onDismiss()
            }, enabled = !selected.selection.collapsed) { Text("选中内容") }
        } }, dismissButton = { TextButton(onClick = { showSelection = false }) { Text("取消") } })
    }
}
