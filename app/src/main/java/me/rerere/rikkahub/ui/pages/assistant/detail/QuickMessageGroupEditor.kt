package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Edit01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.QuickMessage
import me.rerere.rikkahub.data.model.QuickMessageGroup
import me.rerere.rikkahub.data.model.removeQuickMessageGroup
import me.rerere.rikkahub.data.model.upsertQuickMessageGroup
import me.rerere.rikkahub.ui.components.ui.AppearanceAlertDialog as AlertDialog

@Composable
internal fun QuickMessageGroupsButton(
    assistant: Assistant,
    quickMessages: List<QuickMessage>,
    onUpdate: (Assistant) -> Unit,
) {
    var managerOpen by rememberSaveable { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<QuickMessageGroup?>(null) }
    var creating by rememberSaveable { mutableStateOf(false) }

    TextButton(
        onClick = { managerOpen = true },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            stringResource(
                R.string.assistant_extensions_quick_message_groups,
                assistant.quickMessageGroups.size,
            )
        )
    }

    if (managerOpen) {
        AlertDialog(
            onDismissRequest = { managerOpen = false },
            title = { Text(stringResource(R.string.quick_message_groups_title)) },
            text = {
                if (assistant.quickMessageGroups.isEmpty()) {
                    Text(stringResource(R.string.quick_message_groups_empty))
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                        items(assistant.quickMessageGroups, key = { it.id }) { group ->
                            ListItem(
                                headlineContent = {
                                    Text(
                                        group.name,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                supportingContent = {
                                    Text(
                                        stringResource(
                                            R.string.quick_message_groups_item_count,
                                            group.quickMessageIds.size,
                                        )
                                    )
                                },
                                trailingContent = {
                                    Row {
                                        IconButton(
                                            onClick = {
                                                managerOpen = false
                                                editTarget = group
                                            }
                                        ) {
                                            Icon(HugeIcons.Edit01, stringResource(R.string.edit))
                                        }
                                        IconButton(
                                            onClick = {
                                                onUpdate(assistant.removeQuickMessageGroup(group.id))
                                            }
                                        ) {
                                            Icon(
                                                HugeIcons.Delete01,
                                                stringResource(R.string.delete),
                                                tint = MaterialTheme.colorScheme.error,
                                            )
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { managerOpen = false }) {
                    Text(stringResource(R.string.quick_message_groups_done))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        managerOpen = false
                        creating = true
                    }
                ) {
                    Icon(HugeIcons.Add01, null)
                    Text(stringResource(R.string.quick_message_groups_add))
                }
            },
        )
    }

    val groupToEdit = when {
        creating -> QuickMessageGroup()
        editTarget != null -> editTarget
        else -> null
    }
    groupToEdit?.let { group ->
        QuickMessageGroupEditDialog(
            group = group,
            existingGroups = assistant.quickMessageGroups,
            quickMessages = quickMessages.filter { it.id in assistant.quickMessageIds },
            onDismiss = {
                creating = false
                editTarget = null
                managerOpen = true
            },
            onConfirm = {
                onUpdate(assistant.upsertQuickMessageGroup(it))
                creating = false
                editTarget = null
                managerOpen = true
            },
        )
    }
}

@Composable
private fun QuickMessageGroupEditDialog(
    group: QuickMessageGroup,
    existingGroups: List<QuickMessageGroup>,
    quickMessages: List<QuickMessage>,
    onDismiss: () -> Unit,
    onConfirm: (QuickMessageGroup) -> Unit,
) {
    var name by rememberSaveable(group.id) { mutableStateOf(group.name) }
    var selectedIds by remember(group.id) { mutableStateOf(group.quickMessageIds) }
    val duplicateName = name.isNotBlank() && existingGroups.any {
        it.id != group.id && it.name.trim().equals(name.trim(), ignoreCase = true)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (group.name.isBlank()) {
                        R.string.quick_message_groups_add
                    } else {
                        R.string.quick_message_groups_edit
                    }
                )
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.quick_message_groups_name)) },
                    singleLine = true,
                    isError = duplicateName,
                    supportingText = if (duplicateName) {
                        { Text(stringResource(R.string.quick_message_groups_duplicate_name)) }
                    } else null,
                )
                if (quickMessages.isEmpty()) {
                    Text(stringResource(R.string.quick_message_groups_no_messages))
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                        items(quickMessages, key = { it.id }) { quickMessage ->
                            ListItem(
                                headlineContent = {
                                    Text(
                                        quickMessage.title,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                supportingContent = if (quickMessage.category.isNotBlank()) {
                                    { Text(quickMessage.category) }
                                } else null,
                                trailingContent = {
                                    Checkbox(
                                        checked = quickMessage.id in selectedIds,
                                        onCheckedChange = { checked ->
                                            selectedIds = if (checked) {
                                                selectedIds + quickMessage.id
                                            } else {
                                                selectedIds - quickMessage.id
                                            }
                                        },
                                    )
                                },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        group.copy(name = name.trim(), quickMessageIds = selectedIds)
                    )
                },
                enabled = name.isNotBlank() && !duplicateName,
            ) {
                Text(stringResource(R.string.assistant_page_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
