package me.rerere.rikkahub.ui.pages.extensions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.QuickMessageSortMode
import me.rerere.rikkahub.data.model.QuickMessage
import me.rerere.rikkahub.data.model.withQuickMessageIds
import kotlin.uuid.Uuid

class QuickMessagesVM(
    private val settingsStore: SettingsStore
) : ViewModel() {
    val settings = settingsStore.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Lazily, Settings.dummy())

    fun addQuickMessage(quickMessage: QuickMessage) {
        updateQuickMessages(
            settings.value.quickMessages + quickMessage
        )
    }

    fun updateQuickMessage(updated: QuickMessage) {
        updateQuickMessages(
            settings.value.quickMessages.map { quickMessage ->
                if (quickMessage.id == updated.id) updated else quickMessage
            }
        )
    }

    fun deleteQuickMessage(id: Uuid) {
        viewModelScope.launch { settingsStore.deleteQuickMessage(id) }
    }

    fun setSortMode(mode: QuickMessageSortMode) {
        viewModelScope.launch {
            settingsStore.update { it.copy(quickMessageSortMode = mode) }
        }
    }

    private fun updateQuickMessages(quickMessages: List<QuickMessage>) {
        val validIds = quickMessages.map { it.id }.toSet()
        viewModelScope.launch {
            settingsStore.update { settings ->
                settings.copy(
                    quickMessages = quickMessages,
                    assistants = settings.assistants.map { assistant ->
                        assistant.withQuickMessageIds(
                            assistant.quickMessageIds.filter { it in validIds }.toSet()
                        )
                    }
                )
            }
        }
    }
}
