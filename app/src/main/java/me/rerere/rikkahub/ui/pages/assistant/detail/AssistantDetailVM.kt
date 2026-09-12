package me.rerere.rikkahub.ui.pages.assistant.detail

import android.util.Log
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.files.SkillMetadata
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.model.GradientBackgroundCustomColors
import me.rerere.rikkahub.data.model.MemoryLifecycleState
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.model.Tag
import me.rerere.rikkahub.data.model.withPromptSettingsFrom
import me.rerere.rikkahub.data.memory.MemoryEmbeddingService
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.KnowledgeBaseRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import kotlin.uuid.Uuid

private const val TAG = "AssistantDetailVM"

class AssistantDetailVM(
    private val id: String,
    private val settingsStore: SettingsStore,
    private val memoryRepository: MemoryRepository,
    private val memoryEmbeddingService: MemoryEmbeddingService,
    private val filesManager: FilesManager,
    private val skillManager: SkillManager,
    private val workspaceRepository: WorkspaceRepository,
    private val knowledgeBaseRepository: KnowledgeBaseRepository,
) : ViewModel() {
    private val assistantId = Uuid.parse(id)
    private val promptUpdateMutex = Mutex()

    private val _skills = MutableStateFlow<List<SkillMetadata>>(emptyList())
    val skills = _skills.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            _skills.value = skillManager.listSkills()
        }
    }

    val settings: StateFlow<Settings> =
        settingsStore.settingsFlow.stateIn(viewModelScope, SharingStarted.Eagerly, Settings.dummy())
    val memoryEditError = MutableStateFlow<String?>(null)

    val mcpServerConfigs = settingsStore
        .settingsFlow.map { settings ->
            settings.mcpServers
        }.stateIn(
            scope = viewModelScope, started = SharingStarted.Eagerly, initialValue = emptyList()
        )

    val assistant: StateFlow<Assistant> = settingsStore
        .settingsFlow
        .map { settings ->
            settings.assistants.find { it.id == assistantId } ?: Assistant()
        }.stateIn(
            scope = viewModelScope, started = SharingStarted.Eagerly, initialValue = Assistant()
        )

    val memories = assistant
        .flatMapLatest { currentAssistant ->
            if (currentAssistant.useGlobalMemory) {
                memoryRepository.getGlobalMemoriesFlow()
            } else {
                memoryRepository.getMemoriesOfAssistantFlow(assistantId.toString())
            }
        }
        .stateIn(
            scope = viewModelScope, started = SharingStarted.Eagerly, initialValue = emptyList()
        )

    val providers = settingsStore
        .settingsFlow
        .map { settings ->
            settings.providers
        }.stateIn(
            scope = viewModelScope, started = SharingStarted.Eagerly, initialValue = emptyList()
        )

    val tags = settingsStore
        .settingsFlow
        .map { settings ->
            settings.assistantTags
        }.stateIn(
            scope = viewModelScope, started = SharingStarted.Eagerly, initialValue = emptyList()
        )

    val workspaces: StateFlow<List<WorkspaceEntity>> = workspaceRepository
        .listFlow()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = emptyList(),
        )

    val knowledgeBases = knowledgeBaseRepository
        .observeBases()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun updateTags(tagIds: List<Uuid>, tags: List<Tag>) {
        viewModelScope.launch {
            settingsStore.update { current ->
                current.copy(
                    assistantTags = tags,
                    assistants = current.assistants.map { if (it.id == assistantId) it.copy(tags = tagIds.toList()) else it },
                )
            }
            cleanupUnusedTags()
        }
    }

    fun cleanupUnusedTags() {
        viewModelScope.launch {
            settingsStore.update { current ->
                val validTagIds = current.assistantTags.map { it.id }.toSet()
                val assistants = current.assistants.map { assistant -> assistant.copy(tags = assistant.tags.filter { it in validTagIds }) }
                val usedIds = assistants.flatMap { it.tags }.toSet()
                current.copy(assistants = assistants, assistantTags = current.assistantTags.filter { it.id in usedIds })
            }
        }
    }

    fun update(assistant: Assistant, before: Assistant = this.assistant.value) {
        viewModelScope.launch(Dispatchers.Default) {
            if (assistant.id != assistantId || before.id != assistantId) return@launch
            // Media may also be referenced by another assistant, a revision or the
            // global background. Keep it for explicit file management after saving.
            settingsStore.updateAssistantConfig(assistantId) { current ->
                me.rerere.rikkahub.data.model.mergeAssistantEdits(before, assistant, current)
            }
        }
    }

    fun updateGradientBackgroundCustomColors(
        transform: (GradientBackgroundCustomColors) -> GradientBackgroundCustomColors,
    ) {
        viewModelScope.launch {
            settingsStore.updateAssistantConfig(assistantId) { currentAssistant ->
                currentAssistant.copy(
                    gradientBackgroundCustomColors = transform(
                        currentAssistant.gradientBackgroundCustomColors
                    )
                )
            }
        }
    }

    fun useAssistantBackground() {
        viewModelScope.launch { settingsStore.updateAdvancedAppearance { it.copy(applyGlobalBackgroundToChat = false) } }
    }

    fun updatePromptSettings(draft: Assistant) {
        if (draft.id != assistantId) return
        viewModelScope.launch {
            promptUpdateMutex.withLock {
                settingsStore.update { currentSettings ->
                    currentSettings.copy(
                        assistants = currentSettings.assistants.map { currentAssistant ->
                            if (currentAssistant.id == assistantId) {
                                currentAssistant.withPromptSettingsFrom(draft)
                            } else {
                                currentAssistant
                            }
                        }
                    )
                }
            }
        }
    }

    fun addMemory(memory: AssistantMemory) {
        viewModelScope.launch {
            val memoryAssistantId = if (assistant.value.useGlobalMemory) {
                MemoryRepository.GLOBAL_MEMORY_ID
            } else {
                assistantId.toString()
            }
            memoryEmbeddingService.addMemory(
                assistantId = memoryAssistantId,
                content = memory.content,
                settings = settings.value,
                type = memory.type,
                sourceConversationId = memory.sourceConversationId,
                lifecycleState = memory.lifecycleState,
            )
        }
    }

    fun updateMemory(memory: AssistantMemory) {
        viewModelScope.launch {
            try {
            val memoryAssistantId = if (assistant.value.useGlobalMemory) {
                MemoryRepository.GLOBAL_MEMORY_ID
            } else {
                assistantId.toString()
            }
            memoryEmbeddingService.updateMemory(
                assistantId = memoryAssistantId,
                id = memory.id,
                content = memory.content,
                settings = settings.value,
                type = memory.type,
                lifecycleState = memory.lifecycleState,
                expectedRevision = memory.revision,
            )
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) { memoryEditError.value = error.message ?: "记忆保存失败" }
        }
    }

    fun deleteMemory(memory: AssistantMemory) {
        viewModelScope.launch {
            val memoryAssistantId = if (assistant.value.useGlobalMemory) {
                MemoryRepository.GLOBAL_MEMORY_ID
            } else {
                assistantId.toString()
            }
            memoryRepository.deleteMemory(assistantId = memoryAssistantId, id = memory.id)
        }
    }

}
