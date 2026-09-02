package me.rerere.rikkahub.ui.pages.extensions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.datastore.ExtensionManagementMode
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.files.SkillScanResult
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.WorkspaceManager
import me.rerere.workspace.WorkspaceShellStatus

internal data class ExtensionsUiState(
    val mode: ExtensionManagementMode = ExtensionManagementMode.NORMAL,
    val audit: ExtensionAudit = ExtensionAudit(emptyMap(), emptyList(), emptyList()),
)

internal class ExtensionsVM(
    private val settingsStore: SettingsStore,
    private val skillManager: SkillManager,
    workspaceRepository: WorkspaceRepository,
    private val workspaceManager: WorkspaceManager,
) : ViewModel() {
    private val skillScan = MutableStateFlow(SkillScanResult())

    val uiState = combine(
        settingsStore.settingsFlow,
        skillScan,
        workspaceRepository.listFlow(),
    ) { settings, skills, workspaces ->
        val workspaceInputs = workspaces.map { workspace ->
            val accessible = runCatching {
                val directory = workspaceManager.workspaceDir(workspace.root)
                directory.isDirectory && directory.canRead() && directory.canWrite()
            }.getOrDefault(false)
            WorkspaceAuditInput(
                id = workspace.id,
                name = workspace.name,
                accessible = accessible,
                broken = workspace.shellStatus == WorkspaceShellStatus.BROKEN.name,
            )
        }
        ExtensionsUiState(
            mode = settings.extensionManagementMode,
            audit = buildExtensionAudit(settings, skills, workspaceInputs),
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ExtensionsUiState(),
    )

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            skillScan.value = withContext(Dispatchers.IO) { skillManager.scanSkills() }
        }
    }

    fun setMode(mode: ExtensionManagementMode) {
        if (settingsStore.settingsFlow.value.extensionManagementMode == mode) return
        viewModelScope.launch {
            settingsStore.update { it.copy(extensionManagementMode = mode) }
        }
    }
}
