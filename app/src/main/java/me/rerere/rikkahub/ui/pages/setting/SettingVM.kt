package me.rerere.rikkahub.ui.pages.setting

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.AdvancedAppearanceSetting
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.ui.theme.selectTheme
import me.rerere.rikkahub.ui.theme.selectDynamicColors
import me.rerere.rikkahub.ui.theme.selectBackgroundAccent

class SettingVM(
    private val settingsStore: SettingsStore,
    private val mcpManager: McpManager
) :
    ViewModel() {
    val appearanceSaveError = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val settings: StateFlow<Settings> = settingsStore.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Lazily, Settings(init = true, providers = emptyList()))

    fun updateSettings(settings: Settings) {
        viewModelScope.launch {
            settingsStore.update(settings)
        }
    }

    fun updateSettings(transform: (Settings) -> Settings) {
        viewModelScope.launch {
            settingsStore.update(transform)
        }
    }

    fun selectTheme(id: String) = updateThemeSelection { it.selectTheme(id) }

    fun selectDynamicColors(enabled: Boolean) = updateThemeSelection { it.selectDynamicColors(enabled) }

    fun selectBackgroundAccent(enabled: Boolean) = updateThemeSelection { it.selectBackgroundAccent(enabled) }

    private fun updateThemeSelection(transform: (Settings) -> Settings) {
        viewModelScope.launch { saveAppearance { settingsStore.update(transform) } }
    }

    fun updateAdvancedAppearance(
        transform: (AdvancedAppearanceSetting) -> AdvancedAppearanceSetting,
    ) {
        viewModelScope.launch {
            saveAppearance { settingsStore.updateAdvancedAppearance(transform) }
        }
    }

    fun updateDisplaySetting(
        transform: (DisplaySetting) -> DisplaySetting,
    ) {
        viewModelScope.launch {
            saveAppearance { settingsStore.updateDisplaySetting(transform) }
        }
    }

    internal fun updateComposerMaterial(material: me.rerere.rikkahub.data.model.ChatComposerMaterial) {
        viewModelScope.launch {
            saveAppearance { settingsStore.updateComposerMaterial(material) }
        }
    }

    private suspend fun saveAppearance(action: suspend () -> Unit) {
        appearanceSaveError.value = null
        try { action() }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { appearanceSaveError.value = "外观设置保存失败，请检查剩余存储空间后重试。" }
    }
}
