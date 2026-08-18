package me.rerere.rikkahub.ui.pages.knowledge

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.entity.KnowledgeBaseEntity
import me.rerere.rikkahub.data.db.entity.KnowledgeDocumentEntity
import me.rerere.rikkahub.data.knowledge.KnowledgeDocumentImporter
import me.rerere.rikkahub.data.repository.KnowledgeBaseRepository
import kotlin.uuid.Uuid

class KnowledgeBaseVM(
    private val repository: KnowledgeBaseRepository,
    private val importer: KnowledgeDocumentImporter,
    private val settingsStore: SettingsStore,
) : ViewModel() {
    val bases: StateFlow<List<KnowledgeBaseEntity>> = repository
        .observeBases()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val assistants = settingsStore.settingsFlow
        .map { it.assistants }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError = _lastError.asStateFlow()

    private val _documentPreview = MutableStateFlow<KnowledgeDocumentPreviewUiState?>(null)
    val documentPreview = _documentPreview.asStateFlow()
    private var previewJob: Job? = null

    fun createBase(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            repository.createBase(name)
        }
    }

    fun deleteBase(id: String) {
        viewModelScope.launch { repository.deleteBase(id) }
    }

    fun setBaseEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch { repository.updateBaseEnabled(id, enabled) }
    }

    fun setBaseRagEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch { repository.updateBaseRagEnabled(id, enabled) }
    }

    fun deleteDocument(id: String) {
        viewModelScope.launch { repository.deleteDocument(id) }
    }

    fun setAssistantBinding(baseId: String, assistantId: Uuid, bound: Boolean) {
        viewModelScope.launch {
            val settings = settingsStore.settingsFlow.value
            settingsStore.update(
                settings.copy(
                    assistants = settings.assistants.map { assistant ->
                        if (assistant.id != assistantId) return@map assistant
                        val ids = assistant.knowledgeBaseIds.toMutableSet()
                        val knowledgeBaseId = Uuid.parse(baseId)
                        if (bound) ids += knowledgeBaseId else ids -= knowledgeBaseId
                        assistant.copy(knowledgeBaseIds = ids)
                    }
                )
            )
        }
    }

    fun clearLastError() {
        _lastError.value = null
    }

    fun previewDocument(document: KnowledgeDocumentEntity) {
        previewJob?.cancel()
        _documentPreview.value = KnowledgeDocumentPreviewUiState(document = document)
        previewJob = viewModelScope.launch {
            runCatching { repository.getDocumentPreview(document.id) }
                .onSuccess { preview ->
                    if (_documentPreview.value?.document?.id == document.id) {
                        _documentPreview.value = KnowledgeDocumentPreviewUiState(
                            document = document,
                            content = preview.content,
                            truncated = preview.truncated,
                            loading = false,
                        )
                    }
                }
                .onFailure { error ->
                    if (_documentPreview.value?.document?.id == document.id) {
                        _documentPreview.value = KnowledgeDocumentPreviewUiState(
                            document = document,
                            loading = false,
                            error = error.message ?: error.javaClass.simpleName,
                        )
                    }
                }
        }
    }

    fun dismissDocumentPreview() {
        previewJob?.cancel()
        previewJob = null
        _documentPreview.value = null
    }

    fun importDocument(base: KnowledgeBaseEntity, uri: Uri) {
        viewModelScope.launch {
            _lastError.value = null
            runCatching { importer.importDocument(base, uri) }
                .onFailure { _lastError.value = it.message ?: it.javaClass.simpleName }
        }
    }
}

data class KnowledgeDocumentPreviewUiState(
    val document: KnowledgeDocumentEntity,
    val content: String = "",
    val truncated: Boolean = false,
    val loading: Boolean = true,
    val error: String? = null,
)
