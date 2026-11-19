package me.rerere.rikkahub.ui.pages.setting

import me.rerere.rikkahub.data.datastore.AdvancedAppearanceSetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.*
import org.junit.Test

class MicaPreviewBackgroundTest {
    private val assistant = Assistant(background = "file:///assistant.jpg")
    private val settings = Settings(assistantId = assistant.id, assistants = listOf(assistant))

    @Test fun `absent disabled and fully transparent wallpapers do not gate theme preview`() {
        assertNull(settings.copy(assistants = listOf(assistant.copy(background = null))).micaPreviewBackground())
        assertNull(settings.copy(assistants = listOf(assistant.copy(backgroundOpacity = 0f))).micaPreviewBackground())
        val disabled = settings.copy(advancedAppearanceSetting = AdvancedAppearanceSetting(
            enableGlobalBackground = false, globalBackground = "file:///disabled.jpg"))
        assertEquals(assistant.background, disabled.micaPreviewBackground()?.background)
    }

    @Test fun `global source takes precedence without rewriting chat override or glass settings`() {
        val appearance = AdvancedAppearanceSetting(enableGlobalBackground = true,
            globalBackground = "file:///global.jpg", applyGlobalBackgroundToChat = false)
        val original = settings.copy(advancedAppearanceSetting = appearance)
        val selected = original.micaPreviewBackground()
        assertEquals(appearance.globalBackground, selected?.background)
        assertTrue(selected!!.usesGlobalBackground)
        assertEquals(appearance, original.advancedAppearanceSetting)
        assertFalse(original.advancedAppearanceSetting.applyGlobalBackgroundToChat)
    }

    @Test fun `gradient source remains available without an image file`() {
        val selected = settings.copy(assistants = listOf(assistant.copy(background = null, useGradientBackground = true))).micaPreviewBackground()
        assertNotNull(selected)
        assertTrue(selected!!.useGradientBackground)
        assertNull(selected.background)
    }
}
