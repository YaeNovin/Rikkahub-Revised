package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import me.rerere.rikkahub.ui.components.ui.AppearanceModalBottomSheet as ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.res.stringResource
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowTurnBackward
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.files.WorkspaceLocalFileEntry
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceStorageArea
import me.rerere.workspace.WorkspaceLocalDirectoryGrant
import org.koin.compose.koinInject

@Composable
fun WorkspaceCwdPickerSheet(
    workspaceId: String,
    currentCwd: String?,
    onSelectCwd: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val workspaceRepository: WorkspaceRepository = koinInject()

    val parsedLocalCwd = remember(currentCwd) { parseLocalWorkspaceCwd(currentCwd) }
    var localGrantId by remember(currentCwd) { mutableStateOf(parsedLocalCwd?.grantId) }
    var browsePath by remember(currentCwd) {
        mutableStateOf(parsedLocalCwd?.path ?: fromAbsolutePath(currentCwd))
    }
    // Keep the loading state distinct from an empty grant list.  A saved SAF CWD
    // must not be cleared while the grant metadata is still being read from disk.
    val localGrants by produceState<List<WorkspaceLocalDirectoryGrant>?>(
        initialValue = null,
        key1 = workspaceId,
    ) {
        value = withContext(Dispatchers.IO) {
            workspaceRepository.getLocalDirectoryGrants(workspaceId)
        }
    }
    var entries by remember { mutableStateOf<List<CwdEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(localGrantId, localGrants) {
        val grants = localGrants ?: return@LaunchedEffect
        if (localGrantId != null && grants.none { it.id == localGrantId }) {
            localGrantId = null
            browsePath = ""
        }
    }

    LaunchedEffect(browsePath, localGrantId, localGrants) {
        if (localGrants == null) {
            loading = true
            entries = emptyList()
            return@LaunchedEffect
        }
        loading = true
        errorMessage = null
        try {
            entries = withContext(Dispatchers.IO) {
                if (localGrantId == null) {
                    workspaceRepository
                        .listFiles(workspaceId, WorkspaceStorageArea.FILES, browsePath)
                        .map { it.toCwdEntry() }
                } else {
                    workspaceRepository
                        .listLocalFiles(workspaceId, localGrantId!!, browsePath)
                        .entries
                        .map { it.toCwdEntry() }
                }
            }
                .sortedWith(compareByDescending<CwdEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            entries = emptyList()
            errorMessage = e.message
        } finally {
            loading = false
        }
    }

    val grantsLoaded = localGrants != null
    val grants = localGrants.orEmpty()
    val selectedLocalGrant = grants.firstOrNull { it.id == localGrantId }
    val displayPath = if (localGrantId != null) {
        formatWorkspaceCwd(encodeLocalWorkspaceCwd(localGrantId!!, browsePath), grants)
    } else {
        toAbsolutePath(browsePath)
    }
    val directoryEntries = entries.filter { it.isDirectory }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.workspace_cwd_select_directory),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            )
            Text(
                text = stringResource(R.string.workspace_cwd_select_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                IconButton(
                    enabled = browsePath.isNotBlank() || selectedLocalGrant != null,
                    onClick = {
                        if (selectedLocalGrant != null && browsePath.isBlank()) {
                            localGrantId = null
                            browsePath = ""
                        } else {
                            browsePath = browsePath.substringBeforeLast('/', missingDelimiterValue = "")
                        }
                    },
                ) {
                    Icon(HugeIcons.ArrowTurnBackward, contentDescription = null)
                }
                Text(
                    text = displayPath,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            HorizontalDivider()

            if (!grantsLoaded || loading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 350.dp),
            ) {
                items(directoryEntries, key = { "directory:${it.path}" }) { entry ->
                    ListItem(
                        headlineContent = {
                            Text(
                                text = entry.name,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        leadingContent = {
                            Icon(
                                imageVector = HugeIcons.Folder01,
                                contentDescription = null,
                                modifier = Modifier.size(22.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clickable {
                                browsePath = entry.path
                            },
                    )
                }

                if (selectedLocalGrant == null && grants.isNotEmpty()) {
                    item(key = "local-heading") {
                        Text(
                            text = stringResource(R.string.workspace_cwd_local_directories),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    items(grants, key = { "local:${it.id}" }) { grant ->
                        ListItem(
                            headlineContent = {
                                Text(
                                    text = grant.displayName,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            supportingContent = {
                                Text(
                                    text = if (grant.canWrite) {
                                        stringResource(R.string.workspace_local_access_read_write)
                                    } else {
                                        stringResource(R.string.workspace_local_access_read_only)
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            leadingContent = {
                                Icon(
                                    imageVector = HugeIcons.Folder01,
                                    contentDescription = null,
                                    modifier = Modifier.size(22.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clickable {
                                localGrantId = grant.id
                                browsePath = ""
                            },
                        )
                    }
                }

                if (grantsLoaded && !loading && directoryEntries.isEmpty() && grants.isEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.workspace_cwd_no_subdirectories),
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (errorMessage != null) {
                    item {
                        Text(
                            text = errorMessage!!,
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            HorizontalDivider()

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (currentCwd != null) {
                    TextButton(onClick = {
                        onSelectCwd(null)
                        onDismiss()
                    }) {
                        Text(stringResource(R.string.workspace_cwd_reset))
                    }
                }
                FilledTonalButton(
                    enabled = grantsLoaded && (localGrantId == null || selectedLocalGrant != null),
                    onClick = {
                    val newCwd = selectedLocalGrant?.let {
                        encodeLocalWorkspaceCwd(it.id, browsePath)
                    } ?: toAbsolutePath(browsePath)
                    onSelectCwd(newCwd)
                    onDismiss()
                    },
                ) {
                    Text(stringResource(R.string.workspace_cwd_set))
                }
            }
        }
    }
}

private const val WORKSPACE_PREFIX = "/workspace"
internal const val LOCAL_WORKSPACE_CWD_PREFIX = "saf:"

private data class CwdEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
)

internal data class LocalWorkspaceCwd(
    val grantId: String,
    val path: String,
)

internal fun parseLocalWorkspaceCwd(value: String?): LocalWorkspaceCwd? {
    if (value.isNullOrBlank() || !value.startsWith(LOCAL_WORKSPACE_CWD_PREFIX)) return null
    val payload = value.removePrefix(LOCAL_WORKSPACE_CWD_PREFIX)
    val grantId = payload.substringBefore('/').trim()
    if (grantId.isBlank()) return null
    val path = payload.substringAfter('/', missingDelimiterValue = "").trim('/')
    if ((path.isNotBlank() && path.split('/').any { it.isBlank() || it == "." || it == ".." }) ||
        path.contains('\u0000')) {
        return null
    }
    return LocalWorkspaceCwd(
        grantId = grantId,
        path = path,
    )
}

internal fun encodeLocalWorkspaceCwd(grantId: String, path: String): String {
    require(grantId.isNotBlank())
    val normalizedPath = path.replace('\\', '/').trim('/')
    require(
        normalizedPath.isBlank() ||
            normalizedPath.split('/').none { it.isBlank() || it == "." || it == ".." }
    ) {
        "Local working directory must stay inside the authorized directory"
    }
    require(!normalizedPath.contains('\u0000')) { "Local working directory contains an invalid character" }
    return if (normalizedPath.isBlank()) {
        "$LOCAL_WORKSPACE_CWD_PREFIX$grantId"
    } else {
        "$LOCAL_WORKSPACE_CWD_PREFIX$grantId/$normalizedPath"
    }
}

internal fun formatWorkspaceCwd(
    cwd: String?,
    localGrants: List<WorkspaceLocalDirectoryGrant>,
): String {
    val local = parseLocalWorkspaceCwd(cwd) ?: return cwd?.takeIf { it.isNotBlank() } ?: WORKSPACE_PREFIX
    val grant = localGrants.firstOrNull { it.id == local.grantId }
    val name = grant?.displayName ?: "Local directory"
    return if (local.path.isBlank()) name else "$name/${local.path}"
}

private fun WorkspaceFileEntry.toCwdEntry() = CwdEntry(name = name, path = path, isDirectory = isDirectory)

private fun WorkspaceLocalFileEntry.toCwdEntry() = CwdEntry(name = name, path = path, isDirectory = isDirectory)

private fun toAbsolutePath(relativePath: String): String {
    return if (relativePath.isBlank()) WORKSPACE_PREFIX else "$WORKSPACE_PREFIX/$relativePath"
}

private fun fromAbsolutePath(absolutePath: String?): String {
    if (absolutePath.isNullOrBlank()) return ""
    return absolutePath.removePrefix("$WORKSPACE_PREFIX/").removePrefix(WORKSPACE_PREFIX)
}
