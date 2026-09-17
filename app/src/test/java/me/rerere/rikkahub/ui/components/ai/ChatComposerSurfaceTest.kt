package me.rerere.rikkahub.ui.components.ai

import me.rerere.rikkahub.data.datastore.*
import me.rerere.rikkahub.data.datastore.migration.migrateComposerDisplay
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.ChatComposerMaterial
import me.rerere.rikkahub.data.model.chatComposerMaterial
import me.rerere.rikkahub.data.model.withChatComposerMaterial
import org.junit.Assert.*
import org.junit.Test

class ChatComposerSurfaceTest {
    private fun settings(background: Boolean): Settings {
        val assistant = Assistant(useGradientBackground = background)
        return Settings(assistants = listOf(assistant), assistantId = assistant.id,
            displaySetting = DisplaySetting(enableBlurEffect = true, inputSurfaceOpacity = .6f, inputBlurRadius = 20f))
    }
    @Test fun `scene blur includes messages without requiring wallpaper while unsupported blur falls back`() {
        assertEquals(ChatComposerStyle(.6f, 20f), resolveChatComposerStyle(settings(false), true, 24f))
        assertEquals(ChatComposerStyle(.6f, 0f), resolveChatComposerStyle(settings(true), false, 24f))
        assertEquals(ChatComposerStyle(.6f, 12f), resolveChatComposerStyle(settings(true), true, 12f))
    }
    @Test fun `performance switch stops optical effects without hiding the chat behind an opaque tint`() {
        val settings = settings(true).copy(advancedAppearanceSetting = AdvancedAppearanceSetting(enableInputPerformanceEffects = false))
        assertEquals(ChatComposerStyle(.6f, 0f), resolveChatComposerStyle(settings, true, 24f))
    }
    @Test fun `legacy dock only configuration moves to common input settings`() {
        val result = migrateComposerDisplay(DisplaySetting(enableBlurEffect = false), AdvancedAppearanceSetting(enableChatDockGlass = true, chatDockGlassOpacity = .5f, chatDockGlassBlurRadius = 12f))
        assertTrue(result.enableBlurEffect)
        assertEquals(.5f, result.inputSurfaceOpacity)
        assertEquals(12f, result.inputBlurRadius)
    }
    @Test fun `existing input configuration wins migration and ignores old dock tint`() {
        val settings = settings(true)
        val legacy = AdvancedAppearanceSetting(enableChatDockGlass = true, chatDockGlassOpacity = .9f)
        assertEquals(settings.displaySetting, migrateComposerDisplay(settings.displaySetting, legacy))
        assertEquals(resolveChatComposerStyle(settings, true, 24f), resolveChatComposerStyle(settings.copy(advancedAppearanceSetting = legacy), true, 24f))
    }
    @Test fun `changing material updates both switches together without altering chosen strength or opacity`() {
        val original = settings(false).copy(advancedAppearanceSetting = AdvancedAppearanceSetting(enableInputPerformanceEffects = false))
        ChatComposerMaterial.entries.forEach { material ->
            val changed = original.withChatComposerMaterial(material)
            assertEquals(material, changed.chatComposerMaterial())
            assertEquals(original.displaySetting.inputSurfaceOpacity, changed.displaySetting.inputSurfaceOpacity, 0f)
            assertEquals(original.displaySetting.inputBlurRadius, changed.displaySetting.inputBlurRadius, 0f)
            assertFalse(changed.advancedAppearanceSetting.enableInputPerformanceEffects)
            assertEquals(material == ChatComposerMaterial.LIQUID_GLASS, changed.advancedAppearanceSetting.liquidGlass.applyToComposer)
            assertEquals(material != ChatComposerMaterial.TRANSLUCENT, changed.displaySetting.enableBlurEffect)
        }
    }
    @Test fun `zero opacity remains transparent for every material and legacy dock values do not override it`() {
        val original = settings(true).let { it.copy(displaySetting = it.displaySetting.copy(inputSurfaceOpacity = 0f, inputBlurRadius = 0f),
            advancedAppearanceSetting = it.advancedAppearanceSetting.copy(enableChatDockGlass = true, chatDockGlassOpacity = 1f)) }
        ChatComposerMaterial.entries.forEach { material ->
            assertEquals(ChatComposerStyle(0f, 0f), resolveChatComposerStyle(original.withChatComposerMaterial(material), true, 24f))
        }
    }
    @Test fun `legacy glass selection stays selected even with the old blur switch off`() {
        val original = settings(true).let { it.copy(displaySetting = it.displaySetting.copy(enableBlurEffect = false),
            advancedAppearanceSetting = it.advancedAppearanceSetting.copy(liquidGlass = it.advancedAppearanceSetting.liquidGlass.copy(applyToComposer = true))) }
        assertEquals(ChatComposerMaterial.LIQUID_GLASS, original.chatComposerMaterial())
        assertEquals(ChatComposerStyle(.6f, 20f), resolveChatComposerStyle(original, true, 24f))
        assertFalse(original.withChatComposerMaterial(ChatComposerMaterial.FROSTED).advancedAppearanceSetting.liquidGlass.applyToComposer)
    }
}
