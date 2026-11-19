package me.rerere.rikkahub

import android.app.Activity
import android.app.Application
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import dev.chrisbanes.haze.rememberHazeState
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.*
import me.rerere.rikkahub.ui.context.*
import me.rerere.rikkahub.ui.pages.chat.ChatList
import me.rerere.rikkahub.ui.pages.chat.ChatFloatingHeader
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Deliberately NO Compose test rule/virtual clock: Android Choreographer drives every frame. */
class RealClockHistoryRegressionTest {
    @Test fun historyWithRealAdbGestures() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".revised.appearanceqa"))
        val folder = File(context.externalCacheDir, "diagnostics-regression").apply { mkdirs() }
        fun shell(command: String) = android.os.ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
        var host: ComponentActivity? = null
        val resumed = CountDownLatch(1)
        val ready = CountDownLatch(1)
        val app = context.applicationContext as Application
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
            val assistant = Assistant(regexes = listOf(AssistantRegex(id = kotlin.uuid.Uuid.random(), findRegex = "fixture", replaceString = "history", visualOnly = true)))
            val settings = Settings(assistants = listOf(assistant), assistantId = assistant.id)
            val conversation = Conversation(assistantId = assistant.id, messageNodes = (0 until 60).map { index ->
                UIMessage.assistant("## fixture $index\n\n" + (0..5).joinToString("\n\n") { "History paragraph $it: **readable text** and `inline code`. ".repeat(4) } +
                    "\n\n| A | B |\n|---|---|\n| $index | table |\n\n```kotlin\n// comment\nval index = $index\n```").toMessageNode()
            })
            instrumentation.runOnMainSync {
                host!!.setContent {
                    MaterialTheme {
                        val toaster = com.dokar.sonner.rememberToasterState()
                        val tts = me.rerere.rikkahub.ui.hooks.rememberCustomTtsState()
                        val navigator = remember { Navigator(mutableStateListOf(Screen.Log)) }
                        CompositionLocalProvider(LocalSettings provides settings, LocalNavController provides navigator,
                            LocalToaster provides toaster, LocalTTSState provides tts) {
                            val list = rememberLazyListState(initialFirstVisibleItemIndex = 30)
                            val haze = rememberHazeState()
                            Box(Modifier.fillMaxSize()) {
                                ChatList(PaddingValues(top = 92.dp, bottom = 24.dp), conversation, list, false,
                                    previewMode = false, settings = settings, hazeState = haze)
                                ChatFloatingHeader(settings, haze, true, {}, {}, {})
                            }
                            LaunchedEffect(Unit) { kotlinx.coroutines.delay(2_500); ready.countDown() }
                        }
                    }
                }
            }
            check(ready.await(20, TimeUnit.SECONDS))
            val bounds = ArrayList<Rect>()
            fun visit(node: AccessibilityNodeInfo) {
                if (node.isScrollable) bounds += Rect().also(node::getBoundsInScreen)
                for (index in 0 until node.childCount) node.getChild(index)?.let(::visit)
            }
            visit(checkNotNull(instrumentation.uiAutomation.rootInActiveWindow))
            val rect = checkNotNull(bounds.maxByOrNull { it.width() * it.height() }) { "No scrollable UI-tree node" }
            check(rect.width() > 0 && rect.height() > 0)
            val x = rect.left + rect.width() / 3
            val top = rect.top + rect.height() * 3 / 10
            val bottom = rect.top + rect.height() * 8 / 10
            val trace = "/data/misc/perfetto-traces/history-${System.currentTimeMillis()}.pftrace"
            File(folder, "real-clock-trace-path.txt").writeText(trace)
            File(folder, "real-clock-trace-start.txt").writeText(shell("perfetto --background-wait -o $trace -t 20s --app ${context.packageName} sched freq idle am wm gfx view binder_driver"))
            shell("dumpsys gfxinfo ${context.packageName} reset")
            repeat(8) { shell("input swipe $x $bottom $x $top 450") }
            repeat(8) { shell("input swipe $x $top $x $bottom 450") }
            File(folder, "real-clock-gfxinfo.txt").writeText(shell("dumpsys gfxinfo ${context.packageName}"))
            File(folder, "real-clock-framestats.txt").writeText(shell("dumpsys gfxinfo ${context.packageName} framestats"))
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                File(folder, "real-clock-after.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        } finally {
            instrumentation.runOnMainSync { app.unregisterActivityLifecycleCallbacks(callback); host?.finish() }
        }
    }
}
