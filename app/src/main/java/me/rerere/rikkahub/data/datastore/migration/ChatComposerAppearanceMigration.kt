package me.rerere.rikkahub.data.datastore.migration

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import me.rerere.rikkahub.data.datastore.*
import me.rerere.rikkahub.utils.JsonInstant

internal fun migrateComposerDisplay(display: DisplaySetting, appearance: AdvancedAppearanceSetting): DisplaySetting =
    if (appearance.enableChatDockGlass && appearance.enableChatDockPerformanceEffects && !display.enableBlurEffect) {
        display.copy(enableBlurEffect = true, inputBlurRadius = appearance.chatDockGlassBlurRadius,
            inputSurfaceOpacity = appearance.chatDockGlassOpacity)
    } else display

class ChatComposerAppearanceMigration : DataMigration<Preferences> {
    private val migrated = booleanPreferencesKey("unified_chat_composer_v1")
    override suspend fun shouldMigrate(currentData: Preferences) = currentData[migrated] != true
    override suspend fun migrate(currentData: Preferences): Preferences {
        val prefs = currentData.toMutablePreferences()
        val display = decodeDisplaySetting(prefs[SettingsStore.DISPLAY_SETTING])
        val appearance = decodeAdvancedAppearanceSetting(prefs[SettingsStore.ADVANCED_APPEARANCE_SETTING])
        prefs[SettingsStore.DISPLAY_SETTING] = JsonInstant.encodeToString(migrateComposerDisplay(display, appearance))
        prefs[migrated] = true
        return prefs.toPreferences()
    }
    override suspend fun cleanUp() {}
}
