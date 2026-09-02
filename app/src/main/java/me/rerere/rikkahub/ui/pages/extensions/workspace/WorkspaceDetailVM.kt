package me.rerere.rikkahub.ui.pages.extensions.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.files.WorkspaceLocalFileEntry
import me.rerere.workspace.RootfsInstallProgress
import me.rerere.workspace.RootfsInstallStage
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceDeletedFile
import me.rerere.workspace.WorkspaceCommandResult
import me.rerere.workspace.WorkspaceAccessPolicy
import me.rerere.workspace.WorkspaceAuditEntry
import me.rerere.workspace.WorkspaceIntegrityReport
import me.rerere.workspace.WorkspaceLocalDirectoryGrant
import me.rerere.workspace.WorkspaceStorageStats
import me.rerere.workspace.WorkspaceStorageArea

class WorkspaceDetailVM(
    private val id: String,
    private val repository: WorkspaceRepository,
) : ViewModel() {
    private var refreshJob: Job? = null
    private val _state = MutableStateFlow(WorkspaceDetailState())
    val state = _state.asStateFlow()

    private val _terminalState = MutableStateFlow(WorkspaceTerminalState())
    val terminalState = _terminalState.asStateFlow()

    private val _installProgress = MutableStateFlow<RootfsInstallProgress?>(null)
    val installProgress = _installProgress.asStateFlow()

    private val _installError = MutableStateFlow<String?>(null)
    val installError = _installError.asStateFlow()

    private val _deletionEvents = MutableSharedFlow<WorkspaceDeletedFile>(extraBufferCapacity = 1)
    val deletionEvents = _deletionEvents.asSharedFlow()

    init {
        loadWorkspace()
        refresh()
        refreshLocalDirectories()
    }

    fun selectArea(area: WorkspaceStorageArea) {
        _state.update {
            it.copy(
                area = area,
                path = "",
                entries = emptyList(),
                nextOffset = null,
                error = null,
            )
        }
        refresh()
    }

    fun open(entry: WorkspaceFileEntry) {
        if (!entry.isDirectory) return
        _state.update { it.copy(path = entry.path, entries = emptyList(), nextOffset = null, error = null) }
        refresh()
    }

    fun goUp() {
        val path = state.value.path
        if (path.isBlank()) return
        _state.update {
            it.copy(
                path = path.substringBeforeLast('/', missingDelimiterValue = ""),
                entries = emptyList(),
                nextOffset = null,
                error = null,
            )
        }
        refresh()
    }

    fun refresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            runCatching {
                repository.listFilePage(
                    id = id,
                    area = state.value.area,
                    path = state.value.path,
                )
            }.onSuccess { page ->
                val stats = runCatching { repository.storageStats(id) }.getOrNull()
                val policy = runCatching { repository.getAccessPolicy(id) }.getOrNull()
                val audit = runCatching { repository.auditHistory(id, 8) }.getOrDefault(emptyList())
                val integrity = runCatching { repository.integrityReport(id) }.getOrNull()
                _state.update {
                    it.copy(
                        entries = page.entries,
                        nextOffset = page.nextOffset,
                        stats = stats ?: it.stats,
                        accessPolicy = policy ?: it.accessPolicy,
                        audit = audit,
                        integrity = integrity ?: it.integrity,
                        loading = false,
                    )
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                _state.update {
                    it.copy(
                        entries = emptyList(),
                        nextOffset = null,
                        loading = false,
                        error = error.message ?: "加载工作区文件失败",
                    )
                }
            }
        }
    }

    fun refreshLocalDirectories() {
        viewModelScope.launch {
            _state.update { it.copy(localLoading = true, localError = null) }
            runCatching { repository.getLocalDirectoryGrants(id) }
                .onSuccess { grants ->
                    val selected = state.value.selectedLocalGrantId?.takeIf { selectedId ->
                        grants.any { it.id == selectedId }
                    }
                    _state.update {
                        it.copy(
                            localDirectories = grants,
                            selectedLocalGrantId = selected,
                            localPath = if (selected == null) "" else it.localPath,
                            localEntries = if (selected == null) emptyList() else it.localEntries,
                            localNextOffset = if (selected == null) null else it.localNextOffset,
                            localLoading = false,
                        )
                    }
                    if (selected != null) refreshLocalFiles()
                }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    _state.update {
                        it.copy(
                            localLoading = false,
                            localError = error.message ?: "加载本地目录授权失败",
                        )
                    }
                }
        }
    }

    fun addLocalDirectory(treeUri: String, resultFlags: Int) {
        viewModelScope.launch {
            _state.update { it.copy(localLoading = true, localError = null) }
            runCatching { repository.addLocalDirectoryGrant(id, treeUri, resultFlags) }
                .onSuccess { grant ->
                    _state.update {
                        it.copy(
                            selectedLocalGrantId = grant.id,
                            localPath = "",
                            localEntries = emptyList(),
                            localNextOffset = null,
                        )
                    }
                    refreshLocalDirectories()
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            localLoading = false,
                            localError = error.message ?: "授权本地目录失败",
                        )
                    }
                }
        }
    }

    fun removeLocalDirectory(grantId: String) {
        viewModelScope.launch {
            runCatching { repository.removeLocalDirectoryGrant(id, grantId) }
                .onSuccess {
                    if (state.value.selectedLocalGrantId == grantId) {
                        _state.update {
                            it.copy(
                                selectedLocalGrantId = null,
                                localPath = "",
                                localEntries = emptyList(),
                                localNextOffset = null,
                            )
                        }
                    }
                    refreshLocalDirectories()
                }
                .onFailure { error ->
                    _state.update { it.copy(localError = error.message ?: "撤销本地目录授权失败") }
                }
        }
    }

    fun selectLocalDirectory(grantId: String) {
        _state.update {
            it.copy(
                selectedLocalGrantId = grantId,
                localPath = "",
                localEntries = emptyList(),
                localNextOffset = null,
                localError = null,
            )
        }
        refreshLocalFiles()
    }

    fun leaveLocalDirectory() {
        _state.update {
            it.copy(
                selectedLocalGrantId = null,
                localPath = "",
                localEntries = emptyList(),
                localNextOffset = null,
                localError = null,
            )
        }
    }

    fun openLocalDirectory(entry: WorkspaceLocalFileEntry) {
        if (!entry.isDirectory) return
        _state.update {
            it.copy(
                localPath = entry.path,
                localEntries = emptyList(),
                localNextOffset = null,
                localError = null,
            )
        }
        refreshLocalFiles()
    }

    fun goUpLocalDirectory() {
        val path = state.value.localPath
        if (path.isBlank()) {
            leaveLocalDirectory()
            return
        }
        _state.update {
            it.copy(
                localPath = path.substringBeforeLast('/', missingDelimiterValue = ""),
                localEntries = emptyList(),
                localNextOffset = null,
                localError = null,
            )
        }
        refreshLocalFiles()
    }

    fun refreshLocalFiles() {
        val grantId = state.value.selectedLocalGrantId ?: return
        viewModelScope.launch {
            _state.update { it.copy(localLoading = true, localError = null) }
            runCatching {
                repository.listLocalFiles(id, grantId, state.value.localPath)
            }.onSuccess { page ->
                _state.update {
                    it.copy(
                        localEntries = page.entries,
                        localNextOffset = page.nextOffset,
                        localLoading = false,
                    )
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                _state.update {
                    it.copy(
                        localEntries = emptyList(),
                        localNextOffset = null,
                        localLoading = false,
                        localError = error.message ?: "加载本地文件失败",
                    )
                }
            }
        }
    }

    fun loadMoreLocalFiles() {
        val grantId = state.value.selectedLocalGrantId ?: return
        val offset = state.value.localNextOffset ?: return
        if (state.value.localLoadingMore) return
        viewModelScope.launch {
            _state.update { it.copy(localLoadingMore = true, localError = null) }
            runCatching {
                repository.listLocalFiles(id, grantId, state.value.localPath, offset)
            }.onSuccess { page ->
                _state.update {
                    it.copy(
                        localEntries = it.localEntries + page.entries,
                        localNextOffset = page.nextOffset,
                        localLoadingMore = false,
                    )
                }
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        localLoadingMore = false,
                        localError = error.message ?: "加载更多本地文件失败",
                    )
                }
            }
        }
    }

    fun exportLocalToCacheFile(
        entry: WorkspaceLocalFileEntry,
        cacheDir: File,
        onReady: (File) -> Unit,
    ) {
        viewModelScope.launch {
            runCatching {
                val bytes = repository.readLocalFileBytes(id, entry.grantId, entry.path)
                val dir = File(cacheDir, "workspace_local_preview").apply { mkdirs() }
                File(dir, entry.name).apply { writeBytes(bytes) }
            }.onSuccess(onReady).onFailure { error ->
                _state.update { it.copy(localError = error.message ?: "读取本地文件失败") }
            }
        }
    }

    fun loadMore() {
        val offset = state.value.nextOffset ?: return
        if (state.value.loadingMore) return
        viewModelScope.launch {
            _state.update { it.copy(loadingMore = true, error = null) }
            runCatching {
                repository.listFilePage(
                    id = id,
                    area = state.value.area,
                    path = state.value.path,
                    offset = offset,
                )
            }.onSuccess { page ->
                _state.update {
                    it.copy(
                        entries = it.entries + page.entries,
                        nextOffset = page.nextOffset,
                        loadingMore = false,
                    )
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                _state.update {
                    it.copy(loadingMore = false, error = error.message ?: "加载更多文件失败")
                }
            }
        }
    }

    fun delete(entry: WorkspaceFileEntry) {
        viewModelScope.launch {
            runCatching {
                repository.moveFileToTrash(
                    id = id,
                    area = state.value.area,
                    path = entry.path,
                    recursive = entry.isDirectory,
                )
            }.onSuccess { deleted ->
                if (deleted != null) _deletionEvents.tryEmit(deleted)
                refresh()
            }.onFailure { error ->
                _state.update { it.copy(error = error.message ?: "删除失败") }
            }
        }
    }

    fun restoreDeletedFile(deletedFile: WorkspaceDeletedFile) {
        viewModelScope.launch {
            runCatching { repository.restoreDeletedFile(id, deletedFile) }
                .onSuccess { refresh() }
                .onFailure { error ->
                    _state.update { it.copy(error = error.message ?: "恢复失败") }
                }
        }
    }

    fun importFile(inputStream: InputStream, fileName: String) {
        viewModelScope.launch {
            runCatching {
                repository.importFile(
                    id = id,
                    area = state.value.area,
                    destinationPath = state.value.path,
                    fileName = fileName,
                    inputStream = inputStream,
                )
            }.onSuccess {
                refresh()
            }.onFailure { error ->
                _state.update { it.copy(error = error.message ?: "导入文件失败") }
            }
        }
    }

    fun exportFile(entry: WorkspaceFileEntry, outputStream: OutputStream) {
        viewModelScope.launch {
            runCatching {
                repository.exportFile(
                    id = id,
                    area = state.value.area,
                    path = entry.path,
                    outputStream = outputStream,
                )
            }.onFailure { error ->
                _state.update { it.copy(error = error.message ?: "导出文件失败") }
            }
        }
    }

    /**
     * 把当前区域下的文件导出到 cacheDir 的临时文件, 完成后回调 [onReady].
     * 供分享 / 图片预览 / 交给系统应用打开等复用 (它们都需要一个 FileProvider 可访问的真实 File).
     */
    fun exportToCacheFile(entry: WorkspaceFileEntry, cacheDir: File, onReady: (File) -> Unit) {
        viewModelScope.launch {
            runCatching {
                val dir = File(cacheDir, "workspace_share").apply { mkdirs() }
                val file = File(dir, entry.name)
                file.outputStream().use { output ->
                    repository.exportFile(
                        id = id,
                        area = state.value.area,
                        path = entry.path,
                        outputStream = output,
                    )
                }
                file
            }.onSuccess(onReady).onFailure { error ->
                _state.update { it.copy(error = error.message ?: "导出文件失败") }
            }
        }
    }

    fun setToolApproval(toolName: String, needsApproval: Boolean) {
        viewModelScope.launch {
            val workspace = state.value.workspace ?: return@launch
            repository.setToolApproval(workspace.id, toolName, needsApproval)
            loadWorkspace()
        }
    }

    fun setAccessPolicy(policy: WorkspaceAccessPolicy) {
        viewModelScope.launch {
            val workspace = state.value.workspace ?: return@launch
            runCatching { repository.setAccessPolicy(workspace.id, policy) }
                .onSuccess { refresh() }
                .onFailure { error ->
                    _state.update { it.copy(error = error.message ?: "保存访问策略失败") }
                }
        }
    }

    fun repairWorkspace() {
        viewModelScope.launch {
            runCatching { repository.repairWorkspace(id) }
                .onSuccess {
                    loadWorkspace()
                    refresh()
                }
                .onFailure { error ->
                    _state.update { it.copy(error = error.message ?: "修复工作区失败") }
                }
        }
    }

    fun installRootfs(url: String, expectedSha256: String?) {
        viewModelScope.launch {
            _installError.value = null
            val workspace = state.value.workspace ?: return@launch
            _installProgress.value = RootfsInstallProgress(stage = RootfsInstallStage.DOWNLOADING)
            try {
                repository.installRootfs(workspace.id, url, expectedSha256) { progress ->
                    _installProgress.value = progress
                }
                loadWorkspace()
                refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (error: Throwable) {
                _installError.value = error.message ?: "Rootfs 安装失败"
            } finally {
                _installProgress.value = null
            }
        }
    }

    fun dismissInstallError() {
        _installError.value = null
    }

    fun executeTerminalCommand(command: String) {
        val trimmed = command.trim()
        if (trimmed.isBlank()) return
        // 原子地完成「检查 running」与「置 running=true」, 避免两次快速提交并发启动两条命令
        val previous = _terminalState.getAndUpdate { state ->
            if (state.running) {
                state
            } else {
                state.copy(
                    running = true,
                    input = "",
                    history = state.history + WorkspaceTerminalEntry.Command(trimmed),
                )
            }
        }
        if (previous.running) return
        viewModelScope.launch {
            runCatching {
                repository.executeCommand(id, trimmed)
            }.onSuccess { result ->
                _terminalState.update {
                    it.copy(
                        running = false,
                        history = it.history + WorkspaceTerminalEntry.Result(result),
                    )
                }
            }.onFailure { error ->
                _terminalState.update {
                    it.copy(
                        running = false,
                        history = it.history + WorkspaceTerminalEntry.Error(error.message ?: "命令执行失败"),
                    )
                }
            }
        }
    }

    fun updateTerminalInput(input: String) {
        _terminalState.update { it.copy(input = input) }
    }

    fun clearTerminal() {
        _terminalState.update { it.copy(history = emptyList()) }
    }

    private fun loadWorkspace() {
        viewModelScope.launch {
            val workspace = repository.getById(id)
            _state.update { it.copy(workspace = workspace) }
        }
    }
}

data class WorkspaceDetailState(
    val workspace: WorkspaceEntity? = null,
    val area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
    val path: String = "",
    val entries: List<WorkspaceFileEntry> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val nextOffset: Int? = null,
    val loadingMore: Boolean = false,
    val stats: WorkspaceStorageStats? = null,
    val accessPolicy: WorkspaceAccessPolicy = WorkspaceAccessPolicy(),
    val audit: List<WorkspaceAuditEntry> = emptyList(),
    val integrity: WorkspaceIntegrityReport? = null,
    val localDirectories: List<WorkspaceLocalDirectoryGrant> = emptyList(),
    val selectedLocalGrantId: String? = null,
    val localPath: String = "",
    val localEntries: List<WorkspaceLocalFileEntry> = emptyList(),
    val localLoading: Boolean = false,
    val localLoadingMore: Boolean = false,
    val localNextOffset: Int? = null,
    val localError: String? = null,
)

data class WorkspaceTerminalState(
    val input: String = "",
    val running: Boolean = false,
    val history: List<WorkspaceTerminalEntry> = emptyList(),
)

sealed interface WorkspaceTerminalEntry {
    data class Command(val command: String) : WorkspaceTerminalEntry
    data class Result(val result: WorkspaceCommandResult) : WorkspaceTerminalEntry
    data class Error(val message: String) : WorkspaceTerminalEntry
}
