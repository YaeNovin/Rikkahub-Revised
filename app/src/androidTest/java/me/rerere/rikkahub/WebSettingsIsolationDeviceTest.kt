package me.rerere.rikkahub

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.web.WebSettings
import org.junit.Assert.*
import org.junit.Test
import org.koin.core.context.GlobalContext
import java.io.File

class WebSettingsIsolationDeviceTest {
    @Test fun independentWebSettingsPersistWithoutNativeMutations() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".revised.websettingsqa")) { "Only run in the isolated Web QA app" }
        val native = GlobalContext.get().get<SettingsStore>()
        val web = GlobalContext.get().get<WebSettings>().store
        val beforeNative = withTimeout(15000) { native.settingsFlow.first { !it.init } }
        val beforeWeb = withTimeout(15000) { web.settingsFlow.first { !it.init } }
        try {
            val selected = beforeWeb.assistants.last().id
            web.update { it.copy(titlePrompt = "WEB-ONLY-PROMPT", titleMaxLength = 35, displaySetting = it.displaySetting.copy(sendOnEnter = !beforeNative.displaySetting.sendOnEnter)) }
            web.updateAssistant(selected)
            assertEquals(beforeNative.assistantId, native.settingsFlowRaw.first().assistantId)
            assertEquals(beforeNative.titlePrompt, native.settingsFlowRaw.first().titlePrompt)
            assertEquals(beforeNative.displaySetting.sendOnEnter, native.settingsFlowRaw.first().displaySetting.sendOnEnter)
            assertEquals(selected, web.settingsFlowRaw.first().assistantId)
            assertEquals("WEB-ONLY-PROMPT", web.settingsFlowRaw.first().titlePrompt)
            val provider = ProviderSetting.OpenAI(name = "Web isolation QA", enabled = false)
            native.update { it.copy(titlePrompt = "NATIVE-ONLY-PROMPT", providers = it.providers + provider) }
            withTimeout(10000) { web.settingsFlow.first { it.providers.any { p -> p.id == provider.id } } }
            assertEquals("WEB-ONLY-PROMPT", web.settingsFlowRaw.first().titlePrompt)
            assertEquals(35, web.settingsFlowRaw.first().titleMaxLength)
            assertTrue(File(context.filesDir, "datastore/web_settings.preferences_pb").length() > 0)
            assertTrue(File(context.filesDir, "datastore/settings.preferences_pb").length() > 0)
        } finally {
            native.update(beforeNative)
            web.update(beforeWeb)
        }
    }
}
