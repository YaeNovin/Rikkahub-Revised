package me.rerere.rikkahub.ui.pages.extensions

import android.content.ClipData
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Edit01
import me.rerere.hugeicons.stroke.Favourite
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Zap
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.ExtensionManagementMode
import me.rerere.rikkahub.data.datastore.QuickMessageSortMode
import me.rerere.rikkahub.data.model.QuickMessage
import me.rerere.rikkahub.data.model.matchesQuery
import me.rerere.rikkahub.data.model.normalizeQuickMessageTags
import me.rerere.rikkahub.data.model.sortedForDisplay
import me.rerere.rikkahub.data.ai.transformers.PromptVariableScope
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.AppearanceAlertDialog as AlertDialog
import me.rerere.rikkahub.ui.components.ui.AppearanceDropdownMenu as DropdownMenu
import me.rerere.rikkahub.ui.components.ui.PromptVariableReference
import me.rerere.rikkahub.ui.components.ui.RikkaConfirmDialog
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import me.rerere.rikkahub.utils.toLocalDateTime
import org.koin.androidx.compose.koinViewModel

@Composable
fun QuickMessagesPage(vm: QuickMessagesVM = koinViewModel()) {
    val settings = vm.settings.collectAsStateWithLifecycle().value
    val entertainmentMode = settings.extensionManagementMode == ExtensionManagementMode.ENTERTAINMENT
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var showAddDialog by rememberSaveable { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<QuickMessage?>(null) }
    var deleteTarget by remember { mutableStateOf<QuickMessage?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var selectedCategory by rememberSaveable { mutableStateOf<String?>(null) }
    var favoritesOnly by rememberSaveable { mutableStateOf(false) }
    val categories = settings.quickMessages
        .map { it.category.trim() }
        .filter { it.isNotEmpty() }
        .distinctBy { it.lowercase() }
        .sortedBy { it.lowercase() }
    val filteredMessages = settings.quickMessages
        .filter { !entertainmentMode || it.matchesQuery(query) }
        .filter { !entertainmentMode || selectedCategory == null || it.category.equals(selectedCategory, true) }
        .filter { !entertainmentMode || !favoritesOnly || it.favorite }
    val visibleMessages = if (entertainmentMode) {
        filteredMessages.sortedForDisplay(settings.quickMessageSortMode)
    } else {
        filteredMessages
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.assistant_page_quick_messages)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(HugeIcons.Add01, contentDescription = null)
            }
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (entertainmentMode) {
                item {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(),
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
                }
                item {
                    QuickMessageSortSelector(
                        selected = settings.quickMessageSortMode,
                        onSelect = vm::setSortMode,
                    )
                }
                if (categories.isNotEmpty() || settings.quickMessages.any { it.favorite }) {
                    item {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            FilterChip(
                                selected = selectedCategory == null,
                                onClick = { selectedCategory = null },
                                label = { Text(stringResource(R.string.quick_messages_page_all_categories)) },
                            )
                            categories.forEach { category ->
                                FilterChip(
                                    selected = selectedCategory.equals(category, true),
                                    onClick = { selectedCategory = category },
                                    label = { Text(category) },
                                )
                            }
                            FilterChip(
                                selected = favoritesOnly,
                                onClick = { favoritesOnly = !favoritesOnly },
                                label = { Text(stringResource(R.string.quick_messages_page_favorites)) },
                                leadingIcon = { Icon(HugeIcons.Favourite, null, Modifier.size(16.dp)) },
                            )
                        }
                    }
                }
            }

            if (visibleMessages.isEmpty()) {
                item {
                    QuickMessageEmptyState(hasMessages = settings.quickMessages.isNotEmpty())
                }
            }

            items(visibleMessages, key = { it.id }) { quickMessage ->
                QuickMessageCard(
                    quickMessage = quickMessage,
                    entertainmentMode = entertainmentMode,
                    onToggleFavorite = {
                        vm.updateQuickMessage(quickMessage.copy(favorite = !quickMessage.favorite))
                    },
                    onEdit = { editTarget = quickMessage },
                    onDelete = { deleteTarget = quickMessage },
                )
            }
        }
    }

    if (showAddDialog) {
        EditQuickMessageDialog(
            title = stringResource(R.string.quick_messages_page_add_title),
            initialQuickMessage = null,
            entertainmentMode = entertainmentMode,
            onDismiss = { showAddDialog = false },
            onConfirm = {
                vm.addQuickMessage(it)
                showAddDialog = false
            },
        )
    }

    editTarget?.let { quickMessage ->
        EditQuickMessageDialog(
            title = stringResource(R.string.quick_messages_page_edit_title),
            initialQuickMessage = quickMessage,
            entertainmentMode = entertainmentMode,
            onDismiss = { editTarget = null },
            onConfirm = {
                vm.updateQuickMessage(it)
                editTarget = null
            },
        )
    }

    RikkaConfirmDialog(
        show = deleteTarget != null,
        title = stringResource(R.string.quick_messages_page_delete_title),
        confirmText = stringResource(R.string.delete),
        dismissText = stringResource(R.string.cancel),
        onConfirm = {
            deleteTarget?.let { vm.deleteQuickMessage(it.id) }
            deleteTarget = null
        },
        onDismiss = { deleteTarget = null },
    ) {
        Text(stringResource(R.string.quick_messages_page_delete_message, deleteTarget?.title ?: ""))
    }
}

@Composable
private fun QuickMessageSortSelector(
    selected: QuickMessageSortMode,
    onSelect: (QuickMessageSortMode) -> Unit,
) {
    val modes = QuickMessageSortMode.entries
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        modes.forEachIndexed { index, mode ->
            SegmentedButton(
                selected = selected == mode,
                onClick = { onSelect(mode) },
                shape = SegmentedButtonDefaults.itemShape(index, modes.size),
            ) {
                Text(
                    stringResource(
                        when (mode) {
                            QuickMessageSortMode.DEFAULT -> R.string.quick_messages_page_sort_default
                            QuickMessageSortMode.RECENT -> R.string.quick_messages_page_sort_recent
                            QuickMessageSortMode.FREQUENT -> R.string.quick_messages_page_sort_frequent
                        }
                    )
                )
            }
        }
    }
}

@Composable
private fun QuickMessageEmptyState(hasMessages: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = HugeIcons.Zap,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(
                if (hasMessages) R.string.quick_messages_page_no_matches
                else R.string.quick_messages_page_empty_title
            ),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!hasMessages) {
            Text(
                text = stringResource(R.string.quick_messages_page_empty_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun QuickMessageCard(
    quickMessage: QuickMessage,
    entertainmentMode: Boolean,
    onToggleFavorite: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val metadata = buildList {
        if (quickMessage.category.isNotBlank()) add(quickMessage.category)
        addAll(quickMessage.tags.map { "#$it" })
    }.joinToString(" · ")

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (entertainmentMode) {
                    Modifier.combinedClickable(
                        onClick = onEdit,
                        onLongClick = { menuExpanded = true },
                    )
                } else {
                    Modifier
                }
            ),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (entertainmentMode) {
                IconButton(onClick = onToggleFavorite) {
                    Icon(
                        imageVector = HugeIcons.Favourite,
                        contentDescription = stringResource(R.string.quick_messages_page_toggle_favorite),
                        tint = if (quickMessage.favorite) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            } else {
                Icon(
                    imageVector = HugeIcons.Zap,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .size(20.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = quickMessage.title.ifBlank { stringResource(R.string.quick_messages_page_untitled) },
                    style = MaterialTheme.typography.titleSmallEmphasized,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = quickMessage.content.ifBlank { stringResource(R.string.quick_messages_page_empty_content) },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (entertainmentMode && metadata.isNotBlank()) {
                    Text(
                        text = metadata,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (entertainmentMode && quickMessage.useCount > 0) {
                    Text(
                        text = if (quickMessage.lastUsedAt > 0) {
                            stringResource(
                                R.string.quick_messages_page_usage_with_time,
                                quickMessage.useCount,
                                Instant.ofEpochMilli(quickMessage.lastUsedAt).toLocalDateTime(),
                            )
                        } else {
                            stringResource(R.string.quick_messages_page_usage, quickMessage.useCount)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(HugeIcons.MoreVertical, stringResource(R.string.skills_page_more_actions))
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    if (entertainmentMode) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.copy)) },
                            leadingIcon = { Icon(HugeIcons.Copy01, null) },
                            onClick = {
                                scope.launch {
                                    clipboard.setClipEntry(
                                        ClipEntry(ClipData.newPlainText(null, quickMessage.content))
                                    )
                                }
                                menuExpanded = false
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.edit)) },
                        leadingIcon = { Icon(HugeIcons.Edit01, null) },
                        onClick = {
                            menuExpanded = false
                            onEdit()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) },
                        leadingIcon = {
                            Icon(HugeIcons.Delete01, null, tint = MaterialTheme.colorScheme.error)
                        },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun EditQuickMessageDialog(
    title: String,
    initialQuickMessage: QuickMessage?,
    entertainmentMode: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (QuickMessage) -> Unit,
) {
    var quickMessageTitle by rememberSaveable(initialQuickMessage?.id) {
        mutableStateOf(initialQuickMessage?.title ?: "")
    }
    var quickMessageContent by rememberSaveable(initialQuickMessage?.id) {
        mutableStateOf(initialQuickMessage?.content ?: "")
    }
    var category by rememberSaveable(initialQuickMessage?.id) {
        mutableStateOf(initialQuickMessage?.category ?: "")
    }
    var tags by rememberSaveable(initialQuickMessage?.id) {
        mutableStateOf(initialQuickMessage?.tags?.joinToString(", ") ?: "")
    }
    var favorite by rememberSaveable(initialQuickMessage?.id) {
        mutableStateOf(initialQuickMessage?.favorite == true)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    OutlinedTextField(
                        value = quickMessageTitle,
                        onValueChange = { quickMessageTitle = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.assistant_page_quick_message_title)) },
                        singleLine = true,
                    )
                }
                item {
                    OutlinedTextField(
                        value = quickMessageContent,
                        onValueChange = { quickMessageContent = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.assistant_page_quick_message_content)) },
                        supportingText = if (entertainmentMode) {
                            { Text(stringResource(R.string.quick_messages_page_placeholder_hint)) }
                        } else null,
                        minLines = 4,
                        maxLines = 8,
                    )
                }
                if (entertainmentMode) {
                    item {
                        PromptVariableReference(
                            scope = PromptVariableScope.QUICK_MESSAGE,
                            showDescriptions = false,
                            onInsert = { token ->
                                quickMessageContent = if (quickMessageContent.isBlank()) {
                                    token
                                } else {
                                    "$quickMessageContent $token"
                                }
                            },
                        )
                    }
                }
                if (entertainmentMode) {
                    item {
                        OutlinedTextField(
                            value = category,
                            onValueChange = { category = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.quick_messages_page_category)) },
                            singleLine = true,
                        )
                    }
                    item {
                        OutlinedTextField(
                            value = tags,
                            onValueChange = { tags = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.quick_messages_page_tags)) },
                            supportingText = { Text(stringResource(R.string.quick_messages_page_tags_hint)) },
                            singleLine = true,
                        )
                    }
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(R.string.quick_messages_page_favorite),
                                modifier = Modifier.weight(1f),
                            )
                            Switch(checked = favorite, onCheckedChange = { favorite = it })
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        (initialQuickMessage ?: QuickMessage()).copy(
                            title = quickMessageTitle.trim(),
                            content = quickMessageContent.trim(),
                            category = if (entertainmentMode) category.trim() else initialQuickMessage?.category.orEmpty(),
                            tags = if (entertainmentMode) {
                                normalizeQuickMessageTags(tags.split(',', '，'))
                            } else {
                                initialQuickMessage?.tags.orEmpty()
                            },
                            favorite = if (entertainmentMode) favorite else initialQuickMessage?.favorite == true,
                        )
                    )
                },
                enabled = quickMessageTitle.isNotBlank() && quickMessageContent.isNotBlank(),
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
