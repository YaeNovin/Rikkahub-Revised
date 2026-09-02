package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.PencilEdit01
import me.rerere.hugeicons.stroke.Search01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.model.MemoryType
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.AppearanceAlertDialog as AlertDialog
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.RikkaConfirmDialog
import me.rerere.rikkahub.ui.hooks.EditStateContent
import me.rerere.rikkahub.ui.hooks.useEditState
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.toLocalDateTime
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

private enum class MemoryFilter {
    ALL,
    FACT,
    EPISODIC,
}

@Composable
fun AssistantMemoryPage(id: String) {
    val vm: AssistantDetailVM = koinViewModel(parameters = { parametersOf(id) })
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val memories by vm.memories.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.assistant_page_tab_memory)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        AssistantMemoryContent(
            innerPadding = innerPadding,
            assistant = assistant,
            memories = memories,
            onUpdateAssistant = vm::update,
            onDeleteMemory = vm::deleteMemory,
            onAddMemory = vm::addMemory,
            onUpdateMemory = vm::updateMemory,
        )
    }
}

@Composable
private fun AssistantMemoryContent(
    innerPadding: PaddingValues,
    assistant: Assistant,
    memories: List<AssistantMemory>,
    onUpdateAssistant: (Assistant) -> Unit,
    onAddMemory: (AssistantMemory) -> Unit,
    onUpdateMemory: (AssistantMemory) -> Unit,
    onDeleteMemory: (AssistantMemory) -> Unit,
) {
    val memoryDialogState = useEditState<AssistantMemory> { memory ->
        if (memory.id == 0) onAddMemory(memory) else onUpdateMemory(memory)
    }
    var pendingDeleteMemory by remember { mutableStateOf<AssistantMemory?>(null) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var memoryFilter by rememberSaveable { mutableStateOf(MemoryFilter.ALL) }

    val factCount = memories.count { it.type == MemoryType.FACT }
    val episodicCount = memories.size - factCount
    val filteredMemories = remember(memories, searchQuery, memoryFilter) {
        memories.asSequence()
            .filter { memory ->
                when (memoryFilter) {
                    MemoryFilter.ALL -> true
                    MemoryFilter.FACT -> memory.type == MemoryType.FACT
                    MemoryFilter.EPISODIC -> memory.type == MemoryType.EPISODIC
                }
            }
            .filter { memory ->
                searchQuery.isBlank() ||
                    memory.content.contains(searchQuery, ignoreCase = true) ||
                    memory.id.toString().contains(searchQuery) ||
                    memory.sourceConversationId?.contains(searchQuery, ignoreCase = true) == true
            }
            .sortedWith(
                compareByDescending<AssistantMemory> { it.createdAt }
                    .thenByDescending { it.id }
            )
            .toList()
    }

    memoryDialogState.EditStateContent { memory, update ->
        val canSelectEpisodic = assistant.enableEpisodicMemory ||
            (memory.id != 0 && memory.type == MemoryType.EPISODIC)
        AlertDialog(
            onDismissRequest = memoryDialogState::dismiss,
            title = {
                Text(
                    stringResource(
                        if (memory.id == 0) R.string.assistant_page_add_memory
                        else R.string.assistant_page_edit_memory
                    )
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextField(
                        value = memory.content,
                        onValueChange = { update(memory.copy(content = it)) },
                        label = { Text(stringResource(R.string.assistant_page_memory_content_label)) },
                        minLines = 3,
                        maxLines = 8,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = stringResource(R.string.assistant_page_memory_type_label),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        MemoryType.entries.forEachIndexed { index, type ->
                            SegmentedButton(
                                selected = memory.type == type,
                                onClick = { update(memory.copy(type = type)) },
                                enabled = type == MemoryType.FACT || canSelectEpisodic,
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = index,
                                    count = MemoryType.entries.size,
                                ),
                            ) {
                                Text(
                                    stringResource(
                                        if (type == MemoryType.FACT) {
                                            R.string.assistant_page_memory_filter_fact
                                        } else {
                                            R.string.assistant_page_memory_filter_episodic
                                        }
                                    )
                                )
                            }
                        }
                    }
                    if (!assistant.enableEpisodicMemory) {
                        Text(
                            text = stringResource(R.string.assistant_page_episodic_memory_disabled_hint),
                            color = MaterialTheme.colorScheme.tertiary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = memoryDialogState::confirm,
                    enabled = memory.content.isNotBlank(),
                ) {
                    Text(stringResource(R.string.assistant_page_save))
                }
            },
            dismissButton = {
                TextButton(onClick = memoryDialogState::dismiss) {
                    Text(stringResource(R.string.assistant_page_cancel))
                }
            },
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
            .imePadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "memory_settings") {
            CardGroup {
                item(
                    headlineContent = { Text(stringResource(R.string.assistant_page_memory)) },
                    supportingContent = { Text(stringResource(R.string.assistant_page_memory_desc)) },
                    trailingContent = {
                        Switch(
                            checked = assistant.enableMemory,
                            onCheckedChange = { enabled ->
                                onUpdateAssistant(assistant.copy(enableMemory = enabled))
                            },
                        )
                    },
                )
                item(
                    headlineContent = { Text(stringResource(R.string.assistant_page_global_memory)) },
                    supportingContent = { Text(stringResource(R.string.assistant_page_global_memory_desc)) },
                    trailingContent = {
                        Switch(
                            checked = assistant.useGlobalMemory,
                            onCheckedChange = { enabled ->
                                onUpdateAssistant(assistant.copy(useGlobalMemory = enabled))
                            },
                            enabled = assistant.enableMemory,
                        )
                    },
                )
                item(
                    headlineContent = { Text(stringResource(R.string.assistant_page_recent_chats)) },
                    supportingContent = { Text(stringResource(R.string.assistant_page_recent_chats_desc)) },
                    trailingContent = {
                        Switch(
                            checked = assistant.enableRecentChatsReference,
                            onCheckedChange = { enabled ->
                                onUpdateAssistant(assistant.copy(enableRecentChatsReference = enabled))
                            },
                        )
                    },
                )
                item(
                    headlineContent = { Text(stringResource(R.string.assistant_page_time_reminder)) },
                    supportingContent = { Text(stringResource(R.string.assistant_page_time_reminder_desc)) },
                    trailingContent = {
                        Switch(
                            checked = assistant.enableTimeReminder,
                            onCheckedChange = { enabled ->
                                onUpdateAssistant(assistant.copy(enableTimeReminder = enabled))
                            },
                        )
                    },
                )
            }
        }

        item(key = "advanced_memory_settings") {
            CardGroup(title = { Text(stringResource(R.string.assistant_page_advanced_memory)) }) {
                item(
                    headlineContent = { Text(stringResource(R.string.assistant_page_memory_mode_title)) },
                    supportingContent = {
                        Text(
                            when {
                                !assistant.enableMemory -> stringResource(R.string.assistant_page_memory_mode_disabled)
                                assistant.enableMemoryRag -> stringResource(R.string.assistant_page_memory_mode_rag)
                                else -> stringResource(R.string.assistant_page_memory_mode_basic)
                            }
                        )
                    },
                )
                item(
                    headlineContent = { Text(stringResource(R.string.assistant_page_memory_rag)) },
                    supportingContent = { Text(stringResource(R.string.assistant_page_memory_rag_desc)) },
                    trailingContent = {
                        Switch(
                            checked = assistant.enableMemoryRag,
                            onCheckedChange = { enabled ->
                                onUpdateAssistant(assistant.copy(enableMemoryRag = enabled))
                            },
                            enabled = assistant.enableMemory,
                        )
                    },
                )
                item(
                    headlineContent = { Text(stringResource(R.string.assistant_page_episodic_memory)) },
                    supportingContent = { Text(stringResource(R.string.assistant_page_episodic_memory_desc)) },
                    trailingContent = {
                        Switch(
                            checked = assistant.enableEpisodicMemory,
                            onCheckedChange = { enabled ->
                                onUpdateAssistant(assistant.copy(enableEpisodicMemory = enabled))
                            },
                            enabled = assistant.enableMemory,
                        )
                    },
                )
            }
        }

        item(key = "memory_manager_title") {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
            ) {
                Text(
                    text = stringResource(R.string.assistant_page_manage_memory_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.align(Alignment.CenterStart),
                )
                IconButton(
                    onClick = { memoryDialogState.open(AssistantMemory(id = 0)) },
                    modifier = Modifier.align(Alignment.CenterEnd),
                ) {
                    Icon(
                        imageVector = HugeIcons.Add01,
                        contentDescription = stringResource(R.string.assistant_page_add_memory),
                    )
                }
            }
        }

        item(key = "memory_search") {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text(stringResource(R.string.assistant_page_memory_search_hint)) },
                leadingIcon = { Icon(HugeIcons.Search01, contentDescription = null) },
                trailingIcon = if (searchQuery.isNotEmpty()) {
                    {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(
                                HugeIcons.Cancel01,
                                contentDescription = stringResource(R.string.assistant_page_memory_search_clear),
                            )
                        }
                    }
                } else {
                    null
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        item(key = "memory_filters") {
            val filters = listOf(
                Triple(MemoryFilter.ALL, R.string.assistant_page_memory_filter_all, memories.size),
                Triple(MemoryFilter.FACT, R.string.assistant_page_memory_filter_fact, factCount),
                Triple(MemoryFilter.EPISODIC, R.string.assistant_page_memory_filter_episodic, episodicCount),
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                filters.forEachIndexed { index, (filter, label, count) ->
                    SegmentedButton(
                        selected = memoryFilter == filter,
                        onClick = { memoryFilter = filter },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = filters.size),
                    ) {
                        Text(
                            text = stringResource(
                                R.string.assistant_page_memory_filter_count,
                                stringResource(label),
                                count,
                            ),
                            maxLines = 1,
                        )
                    }
                }
            }
        }

        if (filteredMemories.isEmpty()) {
            item(key = "memory_empty") {
                Text(
                    text = stringResource(
                        if (memories.isEmpty()) R.string.assistant_page_memory_empty
                        else R.string.assistant_page_memory_no_results
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 32.dp),
                )
            }
        } else {
            items(items = filteredMemories, key = AssistantMemory::id) { memory ->
                MemoryItem(
                    memory = memory,
                    onEditMemory = { memoryDialogState.open(it) },
                    onDeleteMemory = { pendingDeleteMemory = it },
                )
            }
        }
    }

    RikkaConfirmDialog(
        show = pendingDeleteMemory != null,
        title = stringResource(R.string.confirm_delete),
        confirmText = stringResource(R.string.confirm),
        dismissText = stringResource(R.string.cancel),
        onConfirm = {
            pendingDeleteMemory?.let(onDeleteMemory)
            pendingDeleteMemory = null
        },
        onDismiss = { pendingDeleteMemory = null },
        text = {
            Text(
                text = pendingDeleteMemory?.content.orEmpty(),
                maxLines = 8,
                overflow = TextOverflow.Ellipsis,
            )
        },
    )
}

@Composable
private fun MemoryItem(
    memory: AssistantMemory,
    onEditMemory: (AssistantMemory) -> Unit,
    onDeleteMemory: (AssistantMemory) -> Unit,
) {
    val typeLabel = stringResource(
        if (memory.type == MemoryType.FACT) R.string.assistant_page_memory_filter_fact
        else R.string.assistant_page_memory_filter_episodic
    )
    val createdAt = if (memory.createdAt > 0L) {
        stringResource(
            R.string.assistant_page_memory_created_at,
            Instant.ofEpochMilli(memory.createdAt).toLocalDateTime(),
        )
    } else {
        stringResource(R.string.assistant_page_memory_created_at_unknown)
    }
    val source = memory.sourceConversationId?.let { conversationId ->
        stringResource(
            R.string.assistant_page_memory_source_conversation,
            conversationId.takeLast(8),
        )
    } ?: stringResource(R.string.assistant_page_memory_source_manual)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "$typeLabel · #${memory.id}",
                    color = if (memory.type == MemoryType.EPISODIC) {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    text = memory.content,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "$createdAt · $source",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            IconButton(onClick = { onEditMemory(memory) }) {
                Icon(
                    HugeIcons.PencilEdit01,
                    contentDescription = stringResource(R.string.assistant_page_edit_memory),
                )
            }
            IconButton(onClick = { onDeleteMemory(memory) }) {
                Icon(
                    HugeIcons.Delete01,
                    contentDescription = stringResource(R.string.assistant_page_delete),
                )
            }
        }
    }
}
