package me.rerere.rikkahub.ui.components.ai

import android.content.ClipData
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Favourite
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Zap
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.QuickMessageSortMode
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.QuickMessage
import me.rerere.rikkahub.data.model.automaticQuickMessageValues
import me.rerere.rikkahub.data.model.matchesQuery
import me.rerere.rikkahub.data.model.placeholderNames
import me.rerere.rikkahub.data.model.render
import me.rerere.rikkahub.data.model.sortedForDisplay
import me.rerere.rikkahub.ui.components.ui.AppearanceAlertDialog as AlertDialog
import me.rerere.rikkahub.ui.components.ui.AppearanceDropdownMenu as DropdownMenu
import me.rerere.rikkahub.ui.components.ui.RikkaConfirmDialog
import me.rerere.rikkahub.ui.hooks.ChatInputState
import org.koin.compose.koinInject

private data class QuickMessageSection(
    val title: String?,
    val messages: List<QuickMessage>,
)

private data class QuickMessageVariableTarget(
    val quickMessage: QuickMessage,
    val automaticValues: Map<String, String>,
)

@Composable
internal fun EntertainmentQuickMessageButton(
    quickMessages: List<QuickMessage>,
    assistant: Assistant,
    sortMode: QuickMessageSortMode,
    state: ChatInputState,
) {
    val settingsStore: SettingsStore = koinInject()
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    val haptic = LocalHapticFeedback.current
    var expanded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var variableTarget by remember { mutableStateOf<QuickMessageVariableTarget?>(null) }
    var actionTarget by remember { mutableStateOf<QuickMessage?>(null) }
    var deleteTarget by remember { mutableStateOf<QuickMessage?>(null) }
    val visibleMessages = quickMessages
        .filter { it.matchesQuery(query) }
        .sortedForDisplay(sortMode)
    val sections = buildQuickMessageSections(visibleMessages, assistant)

    IconButton(onClick = { expanded = !expanded }) {
        Icon(HugeIcons.Zap, null)
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.widthIn(min = 280.dp, max = 380.dp),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                singleLine = true,
                label = { Text(stringResource(R.string.quick_messages_page_search)) },
                leadingIcon = { Icon(HugeIcons.Search01, null) },
                trailingIcon = if (query.isNotEmpty()) {
                    {
                        IconButton(onClick = { query = "" }) {
                            Icon(HugeIcons.Cancel01, stringResource(R.string.extensions_page_clear_search))
                        }
                    }
                } else null,
            )
            if (sections.isEmpty()) {
                Text(
                    text = stringResource(R.string.quick_messages_page_no_matches),
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                sections.forEach { section ->
                    section.title?.let { title ->
                        Text(
                            text = title,
                            modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 2.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    section.messages.forEach { quickMessage ->
                        Surface(
                            color = androidx.compose.ui.graphics.Color.Transparent,
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = {
                                        expanded = false
                                        val automaticValues = automaticQuickMessageValues(
                                            settings = settingsStore.settingsFlow.value,
                                            assistant = assistant,
                                        )
                                        val unresolvedNames = quickMessage.placeholderNames()
                                            .filterNot(automaticValues::containsKey)
                                        if (unresolvedNames.isEmpty()) {
                                            state.appendText(quickMessage.render(automaticValues))
                                            scope.launch {
                                                settingsStore.recordQuickMessageUse(quickMessage.id)
                                            }
                                        } else {
                                            variableTarget = QuickMessageVariableTarget(
                                                quickMessage = quickMessage,
                                                automaticValues = automaticValues,
                                            )
                                        }
                                    },
                                    onLongClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        expanded = false
                                        actionTarget = quickMessage
                                    },
                                ),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = if (quickMessage.favorite) HugeIcons.Favourite else HugeIcons.Zap,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = if (quickMessage.favorite) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    Text(
                                        text = quickMessage.title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = quickMessage.content,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    variableTarget?.let { target ->
        QuickMessageVariablesDialog(
            quickMessage = target.quickMessage,
            automaticValues = target.automaticValues,
            onDismiss = { variableTarget = null },
            onConfirm = { rendered ->
                state.appendText(rendered)
                scope.launch { settingsStore.recordQuickMessageUse(target.quickMessage.id) }
                variableTarget = null
            },
        )
    }

    actionTarget?.let { quickMessage ->
        AlertDialog(
            onDismissRequest = { actionTarget = null },
            title = { Text(quickMessage.title) },
            text = { Text(quickMessage.content, maxLines = 6, overflow = TextOverflow.Ellipsis) },
            confirmButton = {
                TextButton(onClick = { actionTarget = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
            dismissButton = {
                Row {
                    TextButton(
                        onClick = {
                            scope.launch {
                                clipboard.setClipEntry(
                                    ClipEntry(ClipData.newPlainText(null, quickMessage.content))
                                )
                            }
                            actionTarget = null
                        }
                    ) {
                        Icon(HugeIcons.Copy01, null)
                        Text(stringResource(R.string.copy))
                    }
                    TextButton(
                        onClick = {
                            actionTarget = null
                            deleteTarget = quickMessage
                        }
                    ) {
                        Icon(HugeIcons.Delete01, null, tint = MaterialTheme.colorScheme.error)
                        Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                    }
                }
            },
        )
    }

    RikkaConfirmDialog(
        show = deleteTarget != null,
        title = stringResource(R.string.quick_messages_page_delete_title),
        confirmText = stringResource(R.string.delete),
        dismissText = stringResource(R.string.cancel),
        onConfirm = {
            deleteTarget?.let { quickMessage ->
                scope.launch { settingsStore.deleteQuickMessage(quickMessage.id) }
            }
            deleteTarget = null
        },
        onDismiss = { deleteTarget = null },
    ) {
        Text(stringResource(R.string.quick_messages_page_delete_message, deleteTarget?.title.orEmpty()))
    }
}

private fun buildQuickMessageSections(
    messages: List<QuickMessage>,
    assistant: Assistant,
): List<QuickMessageSection> {
    val assigned = linkedSetOf<kotlin.uuid.Uuid>()
    val sections = mutableListOf<QuickMessageSection>()
    assistant.quickMessageGroups.forEach { group ->
        val groupMessages = messages.filter { it.id in group.quickMessageIds }
        if (groupMessages.isNotEmpty()) {
            sections += QuickMessageSection(group.name, groupMessages)
            assigned += groupMessages.map { it.id }
        }
    }
    messages.filterNot { it.id in assigned }
        .groupBy { it.category.trim().takeIf(String::isNotEmpty) }
        .forEach { (category, categoryMessages) ->
            sections += QuickMessageSection(category, categoryMessages)
        }
    return sections
}

@Composable
private fun QuickMessageVariablesDialog(
    quickMessage: QuickMessage,
    automaticValues: Map<String, String>,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val names = quickMessage.placeholderNames().filterNot(automaticValues::containsKey)
    val values = remember(quickMessage.id) {
        mutableStateMapOf<String, String>().apply { names.forEach { put(it, "") } }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.quick_message_variables_title)) },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(names, key = { it }) { name ->
                    OutlinedTextField(
                        value = values[name].orEmpty(),
                        onValueChange = { values[name] = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(name) },
                        singleLine = true,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(quickMessage.render(automaticValues + values)) },
                enabled = names.all { values[it]?.isNotBlank() == true },
            ) {
                Text(stringResource(R.string.quick_message_variables_insert))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
