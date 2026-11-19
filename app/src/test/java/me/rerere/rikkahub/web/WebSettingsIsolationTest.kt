package me.rerere.rikkahub.web

import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.ai.provider.ProviderSetting
import org.junit.Assert.*
import org.junit.Test

class WebSettingsIsolationTest {
    @Test fun `native updates only replace shared services in web profile`() {
        val assistant = Assistant(name = "Web", systemPrompt = "Web-only prompt")
        val web = Settings(assistants = listOf(assistant), assistantId = assistant.id, titlePrompt = "Web title", titleMaxLength = 30)
        val native = web.copy(assistants = listOf(Assistant(name = "Native")), titlePrompt = "Native title", titleMaxLength = 60,
            providers = listOf(ProviderSetting.OpenAI(name = "Updated provider")), displaySetting = web.displaySetting.copy(sendOnEnter = !web.displaySetting.sendOnEnter))
        val merged = web.withSharedWebServices(native)
        assertEquals(native.providers, merged.providers)
        assertEquals(native.searchServices, merged.searchServices)
        assertEquals(native.mcpServers, merged.mcpServers)
        assertEquals(web.assistants, merged.assistants)
        assertEquals(web.assistantId, merged.assistantId)
        assertEquals(web.titlePrompt, merged.titlePrompt)
        assertEquals(web.titleMaxLength, merged.titleMaxLength)
        assertEquals(web.displaySetting, merged.displaySetting)
        assertEquals("Native title", native.titlePrompt)
    }
}
