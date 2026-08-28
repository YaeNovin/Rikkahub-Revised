package me.rerere.rikkahub.ui.pages.setting

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Image02
import me.rerere.hugeicons.stroke.Clean
import me.rerere.hugeicons.stroke.Delete01
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import me.rerere.rikkahub.ui.components.ui.AppearanceAlertDialog as AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.db.entity.ManagedFileEntity
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.files.MAX_FILE_RETENTION_DAYS
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.fileSizeToString
import org.koin.compose.koinInject
import java.io.File

@Composable
fun SettingFilesPage(
    filesManager: FilesManager = koinInject(),
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val gridState = rememberLazyStaggeredGridState()
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val context = LocalContext.current
    val folders = remember { listOf(FileFolders.UPLOAD) }

    // 预先获取字符串资源
    val deletedToast = stringResource(R.string.setting_files_page_deleted_toast)
    val deleteFailedToast = stringResource(R.string.setting_files_page_delete_failed_toast)
    var selectedFolder by remember { mutableStateOf(FileFolders.UPLOAD) }
    var pendingDelete by remember { mutableStateOf<ManagedFileEntity?>(null) }
    var showCleanDialog by remember { mutableStateOf(false) }
    var retentionDaysInput by rememberSaveable { mutableStateOf("30") }
    var cleanAllFiles by rememberSaveable { mutableStateOf(false) }
    val retentionDays = retentionDaysInput.toIntOrNull()
        ?.takeIf { it in 1..MAX_FILE_RETENTION_DAYS }
    val files by filesManager.observe(selectedFolder).collectAsState(initial = emptyList())

    if (pendingDelete != null) {
        val target = pendingDelete!!
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.setting_files_page_delete_file_title)) },
            text = { Text(target.displayName) },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            val ok = filesManager.delete(target.id, deleteFromDisk = true)
                            if (ok) {
                                toaster.show(deletedToast)
                            } else {
                                toaster.show(deleteFailedToast)
                            }
                            pendingDelete = null
                        }
                    }
                ) {
                    Text(stringResource(R.string.setting_files_page_delete_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.setting_files_page_cancel_action))
                }
            }
        )
    }

    if (showCleanDialog) {
        AlertDialog(
            onDismissRequest = { showCleanDialog = false },
            title = { Text(stringResource(R.string.setting_files_page_clean_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.setting_files_page_clean_confirmation))
                    OutlinedTextField(
                        value = retentionDaysInput,
                        onValueChange = { value ->
                            if (value.length <= 5 && value.all(Char::isDigit)) {
                                retentionDaysInput = value
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.setting_files_page_clean_days_label)) },
                        supportingText = {
                            Text(
                                stringResource(
                                    if (!cleanAllFiles && retentionDays == null) {
                                        R.string.setting_files_page_clean_days_invalid
                                    } else {
                                        R.string.setting_files_page_clean_days_supporting
                                    },
                                    MAX_FILE_RETENTION_DAYS,
                                )
                            )
                        },
                        isError = !cleanAllFiles && retentionDays == null,
                        enabled = !cleanAllFiles,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Checkbox(
                            checked = cleanAllFiles,
                            onCheckedChange = { cleanAllFiles = it },
                        )
                        Text(stringResource(R.string.setting_files_page_clean_all_files))
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val days = retentionDays
                        if (!cleanAllFiles && days == null) return@TextButton
                        showCleanDialog = false
                        scope.launch {
                            val result = if (cleanAllFiles) {
                                filesManager.deleteAllChatAndGeneratedFiles()
                            } else {
                                filesManager.deleteFilesOlderThan(days!!)
                            }
                            val message = when {
                                result.failedFiles > 0 -> context.getString(
                                    R.string.setting_files_page_clean_failed_toast,
                                    result.totalDeleted,
                                    result.failedFiles,
                                )

                                result.totalDeleted == 0 -> if (cleanAllFiles) {
                                    context.getString(R.string.setting_files_page_clean_none_all_toast)
                                } else {
                                    context.getString(R.string.setting_files_page_clean_none_toast, days)
                                }

                                else -> context.getString(
                                    R.string.setting_files_page_cleaned_toast,
                                    result.totalDeleted,
                                    result.deletedChatFiles,
                                    result.deletedGeneratedImages,
                                )
                            }
                            toaster.show(message)
                        }
                    },
                    enabled = cleanAllFiles || retentionDays != null,
                ) {
                    Text(stringResource(R.string.setting_files_page_clean_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { showCleanDialog = false }) {
                    Text(stringResource(R.string.setting_files_page_cancel_action))
                }
            }
        )
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_files_page_title)) },
                navigationIcon = { BackButton() },
                actions = {
                    IconButton(
                        onClick = { showCleanDialog = true },
                    ) {
                        Icon(
                            imageVector = HugeIcons.Clean,
                            contentDescription = stringResource(R.string.setting_files_page_clean_content_description),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor
    ) { innerPadding ->
        val layoutDirection = LocalLayoutDirection.current
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = innerPadding.calculateTopPadding(),
                    start = innerPadding.calculateStartPadding(layoutDirection),
                    end = innerPadding.calculateEndPadding(layoutDirection),
                )
        ) {
            FolderRow(
                folders = folders,
                selectedFolder = selectedFolder,
                onFolderSelected = { selectedFolder = it }
            )

            if (files.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(stringResource(R.string.setting_files_page_no_files))
                }
            } else {
                LazyVerticalStaggeredGrid(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        top = 16.dp,
                        end = 16.dp,
                        bottom = innerPadding.calculateBottomPadding() + 16.dp,
                    ),
                    verticalItemSpacing = 8.dp,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    state = gridState,
                    columns = StaggeredGridCells.Fixed(2)
                ) {
                    items(files, key = { it.id }) { file ->
                        FileItem(
                            file = file,
                            fileOnDisk = filesManager.getFile(file),
                            onDelete = { pendingDelete = file }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FolderRow(
    folders: List<String>,
    selectedFolder: String,
    onFolderSelected: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        folders.forEach { folder ->
            FilterChip(
                selected = selectedFolder == folder,
                onClick = { onFolderSelected(folder) },
                label = { Text(folderDisplayName(folder)) }
            )
        }
    }
}

@Composable
private fun folderDisplayName(folder: String): String = when (folder) {
    FileFolders.UPLOAD -> stringResource(R.string.setting_files_page_folder_upload)
    else -> folder
}

@Composable
private fun FileItem(
    file: ManagedFileEntity,
    fileOnDisk: File,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CustomColors.listItemColors.containerColor)
    ) {
        Column {
            Box(
                modifier = Modifier.fillMaxWidth()
            ) {
                if (file.mimeType.startsWith("image/")) {
                    AsyncImage(
                        model = fileOnDisk,
                        contentDescription = file.displayName,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(4f / 3f),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(4f / 3f),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = HugeIcons.Image02,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.align(Alignment.TopEnd)
                ) {
                    Icon(
                        HugeIcons.Delete01,
                        contentDescription = stringResource(R.string.setting_files_page_delete_content_description)
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
            ) {
                Text(
                    text = file.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = file.mimeType,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = file.sizeBytes.fileSizeToString(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
