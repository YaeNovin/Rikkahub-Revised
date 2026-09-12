package me.rerere.rikkahub.data.datastore

import androidx.datastore.preferences.core.preferencesOf
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.datastore.migration.ChatComposerAppearanceMigration
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Test

class ChatComposerAppearanceMigrationTest {
    @Test fun `migration runs once and preserves assistants and global configuration`() = runBlocking {
        val display = DisplaySetting(enableBlurEffect = false)
        val appearance = AdvancedAppearanceSetting(enableChatDockGlass = true, chatDockGlassOpacity = .55f,
            globalBackground = "file:///background.jpg", enableGlobalBackground = true, applyGlobalBackgroundToChat = true)
        val savedAppearance = JsonInstant.encodeToString(AdvancedAppearanceSetting.serializer(), appearance)
        val prefs = preferencesOf(SettingsStore.DISPLAY_SETTING to JsonInstant.encodeToString(DisplaySetting.serializer(), display),
            SettingsStore.ADVANCED_APPEARANCE_SETTING to savedAppearance, SettingsStore.ASSISTANTS to "unchanged")
        val migration = ChatComposerAppearanceMigration()
        assertTrue(migration.shouldMigrate(prefs))
        val updated = migration.migrate(prefs)
        assertFalse(migration.shouldMigrate(updated))
        assertEquals("unchanged", updated[SettingsStore.ASSISTANTS])
        assertEquals(savedAppearance, updated[SettingsStore.ADVANCED_APPEARANCE_SETTING])
        assertEquals(.55f, decodeDisplaySetting(updated[SettingsStore.DISPLAY_SETTING]).inputSurfaceOpacity)
    }
}
