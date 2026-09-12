package me.rerere.rikkahub.ui.pages.extensions.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.RootfsInstallProgress
import me.rerere.workspace.WorkspaceAccessPolicy
import me.rerere.workspace.WorkspaceIntegrityReport

class WorkspaceVM(
    private val repository: WorkspaceRepository,
    settingsStore: SettingsStore,
) : ViewModel() {
    val operationError = MutableStateFlow<String?>(null)
    val operationRunning = MutableStateFlow(false)
    val workspaces = combine(repository.listFlow(), settingsStore.settingsFlow) { workspaces, settings ->
        workspaces.map { workspace ->
            val stats = runCatching { repository.filesAreaStats(workspace.id) }.getOrDefault(0L to 0)
            WorkspaceListItem(
                workspace = workspace,
                assistantCount = settings.assistants.count { it.workspaceId?.toString() == workspace.id },
                filesBytes = stats.first,
                fileCount = stats.second,
                accessPolicy = runCatching { repository.getAccessPolicy(workspace.id) }
                    .getOrDefault(WorkspaceAccessPolicy()),
                integrity = runCatching { repository.integrityReport(workspace.id) }.getOrNull(),
            )
        }
    }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun create(name: String, onSuccess: () -> Unit) {
        perform(onSuccess) { repository.create(name) }
    }

    fun rename(workspace: WorkspaceEntity, name: String, onSuccess: () -> Unit) {
        perform(onSuccess) { check(repository.rename(workspace.id, name)) { "重命名失败" } }
    }

    fun delete(workspace: WorkspaceEntity) {
        perform({}) { check(repository.delete(workspace.id)) { "删除失败" } }
    }

    private fun perform(onSuccess: () -> Unit, action: suspend () -> Unit) {
        if (operationRunning.value) return
        operationRunning.value = true
        operationError.value = null
        viewModelScope.launch {
            try {
                action()
                onSuccess()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                operationError.value = e.message ?: "工作区操作失败，请重试"
            } finally {
                operationRunning.value = false
            }
        }
    }
}

data class WorkspaceListItem(
    val workspace: WorkspaceEntity,
    val assistantCount: Int,
    val filesBytes: Long,
    val fileCount: Int,
    val accessPolicy: WorkspaceAccessPolicy,
    val integrity: WorkspaceIntegrityReport?,
)
