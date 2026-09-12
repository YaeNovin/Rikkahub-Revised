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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flowOn
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
    val checking: Boolean = true,
    val error: String? = null,
)

internal class ExtensionsVM(
    private val settingsStore: SettingsStore,
    private val skillManager: SkillManager,
    workspaceRepository: WorkspaceRepository,
    private val workspaceManager: WorkspaceManager,
) : ViewModel() {
    private val skillScan = MutableStateFlow(SkillScanResult())
    private val scanState = MutableStateFlow<Pair<Boolean, String?>>(true to null)
    private var refreshJob: kotlinx.coroutines.Job? = null

    val uiState = combine(
        settingsStore.settingsFlow,
        skillScan,
        workspaceRepository.listFlow(),
        scanState,
    ) { settings, skills, workspaces, scan ->
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
            checking = scan.first,
            error = scan.second,
        )
    }.flowOn(Dispatchers.IO).stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ExtensionsUiState(),
    )

    init {
        refresh()
    }

    fun refresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            scanState.value = true to null
            try {
                skillScan.value = withContext(Dispatchers.IO) { skillManager.scanSkills() }
                scanState.value = false to null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                scanState.value = false to (e.message ?: "检查失败，请重试")
            }
        }
    }

    fun setMode(mode: ExtensionManagementMode) {
        if (settingsStore.settingsFlow.value.extensionManagementMode == mode) return
        viewModelScope.launch {
            settingsStore.update { it.copy(extensionManagementMode = mode) }
        }
    }
}
