package me.rerere.rikkahub.ui.pages.knowledge

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Eye
import me.rerere.hugeicons.stroke.File02
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.db.entity.KnowledgeBaseEntity
import me.rerere.rikkahub.data.db.entity.KnowledgeDocumentEntity
import me.rerere.rikkahub.data.repository.KnowledgeBaseRepository
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

@Composable
fun KnowledgeBasePage(vm: KnowledgeBaseVM = koinViewModel()) {
    val bases by vm.bases.collectAsStateWithLifecycle()
    val lastError by vm.lastError.collectAsStateWithLifecycle()
    val assistants by vm.assistants.collectAsStateWithLifecycle()
    val documentPreview by vm.documentPreview.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var showCreateDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.knowledge_base_page_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreateDialog = true }) {
                Icon(HugeIcons.Add01, contentDescription = stringResource(R.string.knowledge_base_page_create))
            }
        },
        containerColor = CustomColors.topBarColors.containerColor,
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                top = padding.calculateTopPadding() + 16.dp,
                end = 16.dp,
                bottom = padding.calculateBottomPadding() + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            lastError?.let { error ->
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(R.string.knowledge_base_page_import_error, error),
                                modifier = Modifier.weight(1f),
                                color = androidx.compose.material3.MaterialTheme.colorScheme.error,
                            )
                            TextButton(onClick = vm::clearLastError) {
                                Text(stringResource(R.string.confirm))
                            }
                        }
                    }
                }
            }
            if (bases.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.knowledge_base_page_empty),
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            items(bases, key = { it.id }) { base ->
                KnowledgeBaseCard(
                    base = base,
                    assistants = assistants,
                    onImport = { uri -> vm.importDocument(base, uri) },
                    onDelete = { vm.deleteBase(base.id) },
                    onSetEnabled = { vm.setBaseEnabled(base.id, it) },
                    onSetRagEnabled = { vm.setBaseRagEnabled(base.id, it) },
                    onSetAssistantBinding = { assistantId, bound ->
                        vm.setAssistantBinding(base.id, assistantId, bound)
                    },
                    onDeleteDocument = vm::deleteDocument,
                    onPreviewDocument = vm::previewDocument,
                )
            }
        }
    }

    if (showCreateDialog) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text(stringResource(R.string.knowledge_base_page_create)) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.knowledge_base_page_name)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank(),
                    onClick = {
                        vm.createBase(name)
                        showCreateDialog = false
                    },
                ) { Text(stringResource(R.string.confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    documentPreview?.let { preview ->
        KnowledgeDocumentPreviewSheet(
            preview = preview,
            onDismiss = vm::dismissDocumentPreview,
        )
    }
}

@Composable
private fun KnowledgeBaseCard(
    base: KnowledgeBaseEntity,
    assistants: List<me.rerere.rikkahub.data.model.Assistant>,
    onImport: (Uri) -> Unit,
    onDelete: () -> Unit,
    onSetEnabled: (Boolean) -> Unit,
    onSetRagEnabled: (Boolean) -> Unit,
    onSetAssistantBinding: (Uuid, Boolean) -> Unit,
    onDeleteDocument: (String) -> Unit,
    onPreviewDocument: (KnowledgeDocumentEntity) -> Unit,
) {
    val repository: KnowledgeBaseRepository = koinInject()
    val documents by repository.observeDocuments(base.id).collectAsStateWithLifecycle(emptyList())
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showBindingsSheet by remember { mutableStateOf(false) }
    var documentToDelete by remember { mutableStateOf<KnowledgeDocumentEntity?>(null) }
    val boundAssistants = assistants.filter { base.id in it.knowledgeBaseIds.map(Uuid::toString) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(onImport)
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(base.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        text = stringResource(R.string.knowledge_base_page_document_count, documents.size),
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    )
                }
                IconButton(onClick = {
                    launcher.launch(
                        arrayOf(
                            "text/*",
                            "image/*",
                            "application/pdf",
                            "application/epub+zip",
                            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                        )
                    )
                }, enabled = base.enabled) {
                    Icon(HugeIcons.File02, contentDescription = stringResource(R.string.knowledge_base_page_import))
                }
                IconButton(onClick = { showDeleteDialog = true }) {
                    Icon(HugeIcons.Delete01, contentDescription = stringResource(R.string.delete))
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.knowledge_base_page_enabled))
                    Text(
                        text = stringResource(R.string.knowledge_base_page_enabled_desc),
                        style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                    )
                }
                Switch(checked = base.enabled, onCheckedChange = onSetEnabled)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.knowledge_base_page_rag_enabled))
                    Text(
                        text = stringResource(R.string.knowledge_base_page_rag_enabled_desc),
                        style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                    )
                }
                Switch(checked = base.ragEnabled, onCheckedChange = onSetRagEnabled)
            }
            TextButton(onClick = { showBindingsSheet = true }) {
                Text(
                    stringResource(
                        R.string.knowledge_base_page_bind_assistants,
                        boundAssistants.size,
                    )
                )
            }
            documents.forEach { document ->
                KnowledgeDocumentRow(
                    document = document,
                    onPreview = { onPreviewDocument(document) },
                    onDelete = { documentToDelete = document },
                )
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.confirm_delete)) },
            text = { Text(stringResource(R.string.knowledge_base_page_delete_desc)) },
            confirmButton = {
                TextButton(onClick = { showDeleteDialog = false; onDelete() }) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    if (showBindingsSheet) {
        ModalBottomSheet(onDismissRequest = { showBindingsSheet = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp),
            ) {
                if (assistants.isEmpty()) {
                    Text(
                        text = stringResource(R.string.knowledge_base_page_bind_assistants_empty),
                        modifier = Modifier.padding(24.dp),
                    )
                } else {
                    assistants.forEach { assistant ->
                        val checked = base.id in assistant.knowledgeBaseIds.map(Uuid::toString)
                        ListItem(
                            headlineContent = {
                                Text(
                                    assistant.name.ifBlank {
                                        stringResource(R.string.knowledge_base_page_unnamed_assistant)
                                    }
                                )
                            },
                            supportingContent = {
                                Text(
                                    if (base.enabled) {
                                        stringResource(R.string.knowledge_base_page_binding_ready)
                                    } else {
                                        stringResource(R.string.knowledge_base_page_binding_disabled)
                                    }
                                )
                            },
                            trailingContent = {
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = { onSetAssistantBinding(assistant.id, it) },
                                )
                            },
                        )
                    }
                }
            }
        }
    }

    documentToDelete?.let { document ->
        AlertDialog(
            onDismissRequest = { documentToDelete = null },
            title = { Text(stringResource(R.string.confirm_delete)) },
            text = { Text(stringResource(R.string.knowledge_base_page_document_delete_desc, document.title)) },
            confirmButton = {
                TextButton(onClick = {
                    documentToDelete = null
                    onDeleteDocument(document.id)
                }) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { documentToDelete = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun KnowledgeDocumentRow(
    document: KnowledgeDocumentEntity,
    onPreview: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(HugeIcons.File02, contentDescription = null)
        Column(modifier = Modifier.weight(1f)) {
            Text(document.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                text = when (document.status) {
                    KnowledgeDocumentEntity.STATUS_INDEXING -> stringResource(R.string.knowledge_base_page_status_indexing)
                    KnowledgeDocumentEntity.STATUS_READY -> stringResource(R.string.knowledge_base_page_status_ready)
                    KnowledgeDocumentEntity.STATUS_READY_WITHOUT_EMBEDDING -> stringResource(R.string.knowledge_base_page_status_ready_without_embedding)
                    KnowledgeDocumentEntity.STATUS_FAILED -> stringResource(R.string.knowledge_base_page_status_failed)
                    else -> document.status
                },
                style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
            )
            document.errorMessage?.takeIf { document.status == KnowledgeDocumentEntity.STATUS_FAILED }?.let { error ->
                Text(
                    text = stringResource(R.string.knowledge_base_page_import_error, error),
                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.error,
                )
            }
        }
        IconButton(
            onClick = onPreview,
            enabled = document.status != KnowledgeDocumentEntity.STATUS_INDEXING,
        ) {
            Icon(
                HugeIcons.Eye,
                contentDescription = stringResource(R.string.knowledge_base_page_preview),
            )
        }
        IconButton(onClick = onDelete) {
            Icon(HugeIcons.Delete01, contentDescription = stringResource(R.string.delete))
        }
    }
}

@Composable
private fun KnowledgeDocumentPreviewSheet(
    preview: KnowledgeDocumentPreviewUiState,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = preview.document.title,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = buildString {
                            append(preview.document.mimeType)
                            preview.document.pageCount?.let { pageCount ->
                                append(" | ")
                                append(stringResource(R.string.knowledge_base_page_preview_pages, pageCount))
                            }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        HugeIcons.Cancel01,
                        contentDescription = stringResource(R.string.knowledge_base_page_preview_close),
                    )
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (preview.document.mimeType.startsWith("image/")) {
                    AsyncImage(
                        model = preview.document.sourceUri,
                        contentDescription = preview.document.title,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp),
                        contentScale = ContentScale.Fit,
                    )
                }
                when {
                    preview.loading -> Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 200.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }

                    preview.error != null -> Text(
                        text = stringResource(
                            R.string.knowledge_base_page_preview_failed,
                            preview.error,
                        ),
                        color = MaterialTheme.colorScheme.error,
                    )

                    preview.content.isBlank() -> Text(
                        text = stringResource(R.string.knowledge_base_page_preview_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    else -> SelectionContainer {
                        Text(
                            text = preview.content,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                if (preview.truncated) {
                    Text(
                        text = stringResource(R.string.knowledge_base_page_preview_truncated),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}
