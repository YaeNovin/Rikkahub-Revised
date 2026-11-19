package me.rerere.rikkahub

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import dev.chrisbanes.haze.rememberHazeState
import me.rerere.ai.ui.UIMessage
import me.rerere.common.android.LogEntry
import me.rerere.common.android.Logging
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.*
import me.rerere.rikkahub.data.render.RenderDiagnostics
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.Navigator
import me.rerere.rikkahub.ui.pages.chat.ChatList
import me.rerere.rikkahub.ui.pages.chat.ChatFloatingHeader
import me.rerere.rikkahub.ui.pages.log.LogPage
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Only synthetic records in an isolated application ID; no model requests or user QA data. */
class DiagnosticsRegressionTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var host: ComponentActivity
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val folder get() = File(context.externalCacheDir, "diagnostics-regression").apply { mkdirs() }
    private fun shell(command: String): String = android.os.ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }

    @Before fun launch() {
        check(context.packageName.endsWith(".revised.appearanceqa") || context.packageName.endsWith(".revised.loggingqa")) { "Isolated regression package required" }
        val app = context.applicationContext as Application
        val resumed = CountDownLatch(1)
        val callback = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                if (activity.javaClass == ComponentActivity::class.java) { host = activity as ComponentActivity; resumed.countDown() }
            }
            override fun onActivityCreated(activity: Activity, state: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        }
        instrumentation.runOnMainSync { app.registerActivityLifecycleCallbacks(callback) }
        try {
            shell("am start -W -f 0x10008000 -n ${context.packageName}/androidx.activity.ComponentActivity")
            check(resumed.await(20, TimeUnit.SECONDS))
        } finally { instrumentation.runOnMainSync { app.unregisterActivityLifecycleCallbacks(callback) } }
    }

    @After fun close() { if (::host.isInitialized) instrumentation.runOnMainSync { host.finish() } }

    private fun content(settings: Settings = Settings(), body: @Composable () -> Unit) {
        compose.runOnUiThread { host.setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                val navigator = remember { Navigator(mutableStateListOf(Screen.Log)) }
                val toaster = com.dokar.sonner.rememberToasterState()
                val tts = me.rerere.rikkahub.ui.hooks.rememberCustomTtsState()
                CompositionLocalProvider(LocalSettings provides settings, LocalNavController provides navigator,
                    me.rerere.rikkahub.ui.context.LocalToaster provides toaster,
                    me.rerere.rikkahub.ui.context.LocalTTSState provides tts) { body() }
            }
        } }
    }

    private fun capture(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(folder, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun providerFiltersAndExpandableRenderingErrors() {
        Logging.clear()
        val now = System.currentTimeMillis()
        Logging.logProviderRequest(LogEntry.ProviderRequestLog(provider = "Regression Provider", model = "model-success", channel = "CHAT",
            operation = "STREAM_TEXT", timestamp = now, responseCode = 200, firstTokenMs = 123.456, durationMs = 980,
            durationPhase = "model_complete", preciseTimestamp = java.time.Instant.now().toString()))
        Logging.logProviderRequest(LogEntry.ProviderRequestLog(provider = "Regression Provider", model = "model-error", channel = "IMAGE",
            operation = "IMAGE_GENERATION", timestamp = now - 1_000, responseCode = 400, error = "Synthetic invalid size"))
        RenderDiagnostics.report("sigil-test", "runtime", "Synthetic render failure", IllegalArgumentException("invalid radius"))
        content { LogPage() }
        compose.waitUntil(12_000) { compose.onAllNodesWithText("Regression Provider").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Regression Provider").performScrollTo().performClick()
        compose.onNodeWithText("日期：全部日期 ▾").performClick()
        compose.onNodeWithText("自定义时间范围", useUnmergedTree = true).performClick()
        androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withId(android.R.id.button1))
            .perform(androidx.test.espresso.action.ViewActions.click())
        androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withId(android.R.id.button1))
            .perform(androidx.test.espresso.action.ViewActions.click())
        compose.waitForIdle()
        compose.onNodeWithText("日期：${java.time.LocalDate.now()}", substring = true).assertIsDisplayed()
        compose.onNodeWithText("模型：全部模型 ▾").performClick()
        compose.onNode(hasText("model-error") and hasAnyAncestor(isPopup()), useUnmergedTree = true).performClick()
        compose.onNodeWithText("状态：全部 ▾").performClick()
        compose.onNodeWithText("错误", useUnmergedTree = true).performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("1 条日志").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("model-error").performScrollTo().assertIsDisplayed()
        capture("provider-errors")
        compose.onNodeWithText("model-error").performClick()
        compose.onNodeWithText("导出详情 · .log").assertIsDisplayed()
        shell("input keyevent 4")
        compose.waitForIdle()
        compose.onNodeWithText("软件与系统").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("sigil-test: runtime").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("sigil-test: runtime").performScrollTo().performClick()
        compose.onNodeWithText("软件与系统日志详情").assertIsDisplayed()
        compose.onNodeWithTag("system-log-detail").performScrollToNode(hasText("详细日志"))
        compose.onNodeWithText("展开").performClick()
        compose.onNodeWithTag("system-log-body").performScrollTo()
        compose.onNode(hasText("invalid radius", substring = true) and hasAnyAncestor(hasTestTag("system-log-body"))).assertIsDisplayed()
        shell("screencap -p ${folder}/render-stack.png")
    }

    @Test fun fixedHistoryScrollWithFloatingControls() {
        val assistant = Assistant(regexes = listOf(AssistantRegex(id = kotlin.uuid.Uuid.random(), findRegex = "fixture", replaceString = "history", visualOnly = true)))
        val settings = Settings(assistants = listOf(assistant), assistantId = assistant.id)
        val conversation = Conversation(assistantId = assistant.id, messageNodes = (0 until 60).map { index ->
            UIMessage.assistant("## fixture $index\n\n" + (0..5).joinToString("\n\n") { "History paragraph $it: **readable text** and `inline code`. ".repeat(4) } +
                "\n\n| A | B |\n|---|---|\n| $index | table |\n\n```kotlin\n// comment\nval index = $index\n```").toMessageNode()
        })
        lateinit var list: LazyListState
        content(settings) {
            val state = rememberLazyListState(initialFirstVisibleItemIndex = 30)
            val haze = rememberHazeState()
            SideEffect { list = state }
            Box(Modifier.fillMaxSize().testTag("history_fixture")) {
                ChatList(PaddingValues(top = 92.dp, bottom = 24.dp), conversation, state, false, previewMode = false, settings = settings, hazeState = haze)
                ChatFloatingHeader(settings, haze, true, {}, {}, {})
            }
        }
        compose.waitForIdle()
        compose.waitUntil(15_000) { list.layoutInfo.visibleItemsInfo.isNotEmpty() }
        shell("dumpsys gfxinfo ${context.packageName} reset")
        repeat(8) { compose.onNodeWithTag("history_fixture").performTouchInput { swipeUp(durationMillis = 400) }; compose.waitForIdle() }
        repeat(8) { compose.onNodeWithTag("history_fixture").performTouchInput { swipeDown(durationMillis = 400) }; compose.waitForIdle() }
        File(folder, "history-gfxinfo.txt").writeText(shell("dumpsys gfxinfo ${context.packageName}"))
        File(folder, "history-framestats.txt").writeText(shell("dumpsys gfxinfo ${context.packageName} framestats"))
        compose.onNodeWithTag("chat_open_drawer").assertIsDisplayed()
        capture("history-after-scroll")
        assertTrue(list.layoutInfo.visibleItemsInfo.isNotEmpty())
    }
}
