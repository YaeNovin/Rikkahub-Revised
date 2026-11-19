package me.rerere.rikkahub

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.common.android.*
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.BackgroundSurfaceStyle
import me.rerere.rikkahub.data.datastore.TextColorMode
import me.rerere.rikkahub.ui.theme.*
import me.rerere.rikkahub.ui.context.*
import me.rerere.rikkahub.ui.pages.log.LogPage
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Only synthetic diagnostics in the dedicated logging test package. */
class SystemLogPresentationDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var host: ComponentActivity
    private val folder get() = File(context.externalCacheDir, "system-log-presentation").apply { mkdirs() }
    private fun shell(command: String): String = android.os.ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }

    @Before fun launch() {
        check(context.packageName == "me.rerere.rikkahub.revised.loggingqa")
        Logging.clear()
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

    @After fun close() {
        if (::host.isInitialized) instrumentation.runOnMainSync { host.finish() }
        Logging.clear()
    }

    private fun content(dark: Boolean) {
        compose.runOnUiThread { host.setContent {
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                val navigator = remember { Navigator(mutableStateListOf(Screen.Log)) }
                val toaster = com.dokar.sonner.rememberToasterState()
                val tts = me.rerere.rikkahub.ui.hooks.rememberCustomTtsState()
                CompositionLocalProvider(LocalSettings provides Settings(), LocalNavController provides navigator,
                    LocalToaster provides toaster, LocalTTSState provides tts) { LogPage() }
            }
        } }
    }

    private fun detailField(text: String) {
        compose.onNodeWithTag("system-log-detail").performScrollToNode(hasText(text))
        compose.onNode(hasText(text) and hasAnyAncestor(hasTestTag("system-log-detail"))).assertIsDisplayed()
    }

    private fun detailFlow(dark: Boolean) {
        val original = DiagnosticClient("RikkaHub", context.packageName, "2.4.8-fixture-qa", 185, "qa")
        Logging.logError("合成系统故障 QA", "合成诊断，不涉及用户会话",
            "java.lang.IllegalStateException: SYNTHETIC_ROOT_CAUSE\n" +
                (1..45).joinToString("\n") { "    at fixture.Synthetic$it.run(Test.kt:$it)" },
            diagnostic = DiagnosticMetadata(source = DiagnosticSource.JAVA_CRASH, severity = DiagnosticSeverity.FATAL,
                outcome = DiagnosticOutcome.FAILED, client = original, errorType = "java.lang.IllegalStateException",
                attributes = linkedMapOf("pss_kb" to "81920", "rss_kb" to "131072", "stage" to "synthetic")))
        Logging.logError("旧版故障样本", "旧版合成错误", "java.lang.IllegalArgumentException: LEGACY_SYNTHETIC",
            diagnostic = DiagnosticMetadata(source = DiagnosticSource.JAVA_CRASH, severity = DiagnosticSeverity.ERROR,
                outcome = DiagnosticOutcome.FAILED))
        Logging.flush()
        content(dark)
        compose.onNodeWithText("软件与系统").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("合成系统故障 QA").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("合成系统故障 QA").performScrollTo().performClick()
        compose.onNodeWithText("软件与系统日志详情").assertIsDisplayed()
        detailField("时间")
        detailField("RikkaHub · 2.4.8-fixture-qa")
        detailField("QA 测试版")
        shell("screencap -p ${folder}/${if (dark) "dark" else "light"}-fields.png")
        detailField("java.lang.IllegalStateException")
        detailField("81920 KB")
        detailField("131072 KB")
        shell("screencap -p ${folder}/${if (dark) "dark" else "light"}-values.png")
        detailField("详细日志")
        compose.onNodeWithText("展开").performClick()
        compose.onNodeWithTag("system-log-body").performScrollTo()
        compose.onNodeWithText("收起").assertIsDisplayed()
        compose.onNode(hasText("SYNTHETIC_ROOT_CAUSE", substring = true) and
            hasAnyAncestor(hasTestTag("system-log-body"))).assertIsDisplayed()
        shell("screencap -p ${folder}/${if (dark) "dark" else "light"}-expanded.png")
        compose.onNodeWithText("收起").performClick()
        compose.onNodeWithText("展开").assertIsDisplayed()
        shell("input keyevent 4")
        compose.waitForIdle()
        compose.onNodeWithText("旧版故障样本").performScrollTo().performClick()
        detailField("未记录（旧日志）")
        detailField("未记录")
        shell("screencap -p ${folder}/${if (dark) "dark" else "light"}-legacy.png")
    }

    @Test fun lightSystemDetailFieldsAndExpandableStack() = detailFlow(false)
    @Test fun darkSystemDetailFieldsAndExpandableStack() = detailFlow(true)

    @Test fun productionThemeMaterialsAndTextPalettes() {
        val wallpaper = File(folder, "synthetic-background.png")
        val bitmap = android.graphics.Bitmap.createBitmap(240, 480, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawColor(0xFF402570.toInt())
        canvas.drawRect(0f, 240f, 240f, 480f, android.graphics.Paint().apply { color = 0xFF25366D.toInt() })
        wallpaper.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val logId = Logging.logError("外观适配合成样本", "只验证日志显示", "java.lang.IllegalStateException: APPEARANCE_FIXTURE\n" +
            (1..40).joinToString("\n") { "    at fixture.Appearance$it.run(Test.kt:$it)" })
        Logging.flush()
        data class Case(val dark: Boolean, val mode: TextColorMode, val style: BackgroundSurfaceStyle)
        val cases = listOf(Case(false, TextColorMode.THEME, BackgroundSurfaceStyle.OPAQUE),
            Case(true, TextColorMode.AUTO_CLEAR, BackgroundSurfaceStyle.OPAQUE)) +
            BackgroundSurfaceStyle.entries.flatMap { style -> listOf(
                Case(true, TextColorMode.APP_BACKGROUND, style), Case(true, TextColorMode.SYSTEM_WALLPAPER, style)) }
        var selected by mutableStateOf(cases.first())
        compose.runOnUiThread { host.setContent {
            val settings = Settings(dynamicColor = false).let { it.copy(advancedAppearanceSetting =
                it.advancedAppearanceSetting.copy(enableGlobalBackground = true, globalBackground = wallpaper.absolutePath,
                    globalBackgroundBlurRadius = 0f, textColorMode = selected.mode,
                    pageSurfaceStyle = selected.style, overlaySurfaceStyle = selected.style,
                    pageSurfaceOpacity = .68f, overlaySurfaceOpacity = .82f)) }
            val navigator = remember { Navigator(mutableStateListOf(Screen.Log)) }
            val toaster = com.dokar.sonner.rememberToasterState()
            val tts = me.rerere.rikkahub.ui.hooks.rememberCustomTtsState()
            CompositionLocalProvider(LocalSettings provides settings, LocalNavController provides navigator,
                LocalToaster provides toaster, LocalTTSState provides tts) {
                RikkahubThemeContent(settings, if (selected.dark) ColorMode.DARK else ColorMode.LIGHT) {
                    // Deterministic system seed; real application background still uses bitmap sampling.
                    CompositionLocalProvider(LocalWallpaperTextSeed provides WallpaperTextSeed(0xFF008855.toInt())) {
                        RouteAppearance(settings, chat = false) { LogPage() }
                    }
                }
            }
        } }
        compose.onNodeWithText("软件与系统").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("外观适配合成样本").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("外观适配合成样本").performScrollTo().performClick()
        val notes = mutableListOf<String>()
        val selectedHues = mutableMapOf<TextColorMode, Double>()
        fun selectedHueMatches(mode: TextColorMode, color: Color): Boolean {
            val hue = me.rerere.material3.hctColorFromArgb(color.toArgb()).hue
            val expected = selectedHues[mode] ?: return hasVisiblePaletteHue(color)
            val distance = kotlin.math.abs(hue - expected)
            return hasVisiblePaletteHue(color) && minOf(distance, 360.0 - distance) < 15.0
        }
        fun textColor(text: String): Color {
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNode(hasText(text) and hasAnyAncestor(hasTestTag("system-log-detail")))
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            return layouts.single().layoutInput.style.color
        }
        for ((index, next) in cases.withIndex()) {
            compose.runOnIdle { selected = next }
            compose.waitForIdle()
            detailField("软件与系统日志详情")
            if (next.mode == TextColorMode.APP_BACKGROUND || next.mode == TextColorMode.SYSTEM_WALLPAPER) {
                compose.waitUntil(10_000) { selectedHueMatches(next.mode, textColor("软件与系统日志详情")) }
            }
            val foreground = textColor("软件与系统日志详情")
            if (next.style == BackgroundSurfaceStyle.OPAQUE && next.mode in setOf(TextColorMode.APP_BACKGROUND, TextColorMode.SYSTEM_WALLPAPER)) {
                selectedHues[next.mode] = me.rerere.material3.hctColorFromArgb(foreground.toArgb()).hue
            }
            assertTrue(foreground != Color.Unspecified)
            val pixels = compose.onNodeWithText("软件与系统日志详情").captureToImage().toPixelMap()
            var matches = 0
            for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
                val pixel = pixels[x, y]
                if (kotlin.math.abs(pixel.red - foreground.red) < .025f && kotlin.math.abs(pixel.green - foreground.green) < .025f &&
                    kotlin.math.abs(pixel.blue - foreground.blue) < .025f) matches++
            }
            assertTrue("$next foreground pixels missing", matches > 6)
            detailField("详细日志")
            compose.onNodeWithText("展开").performClick()
            compose.onNodeWithTag("system-log-body").performScrollTo()
            compose.onNode(hasText("APPEARANCE_FIXTURE", substring = true) and
                hasAnyAncestor(hasTestTag("system-log-body"))).assertIsDisplayed()
            if (next.mode == TextColorMode.APP_BACKGROUND || next.mode == TextColorMode.SYSTEM_WALLPAPER) {
                val bodyLayouts = mutableListOf<TextLayoutResult>()
                compose.onNode(hasText("APPEARANCE_FIXTURE", substring = true) and hasAnyAncestor(hasTestTag("system-log-body")))
                    .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(bodyLayouts) }
                assertTrue("$next body lost selected hue", selectedHueMatches(next.mode, bodyLayouts.single().layoutInput.style.color))
            }
            shell("screencap -p ${folder}/appearance-$index.png")
            compose.onNodeWithText("收起").performClick()
            notes += "$index:$next:foreground=${foreground.toArgb()}:pixels=$matches:passed"
            File(folder, "appearance-results.txt").writeText(notes.joinToString("\n"))
        }
        assertNotNull(Logging.getLog(logId))
        File(folder, "appearance-results.txt").writeText(notes.joinToString("\n"))
    }

    @Test fun clientIdentityPersistsAndImportedUnknownBuildsStayUnknown() {
        Logging.logSoftwareError("fixture", "当前构建样本", IllegalStateException("SYNTHETIC_CURRENT"))
        val oldClient = DiagnosticClient("RikkaHub", context.packageName, "previous-fixture", 184, "release")
        val saved = Logging.logError("旧构建", "old", "old", diagnostic = DiagnosticMetadata(
            source = DiagnosticSource.JAVA_CRASH, client = oldClient))
        val unknown = Logging.logError("未知构建", "unknown", "unknown", diagnostic = DiagnosticMetadata(source = DiagnosticSource.PROCESS_EXIT))
        Logging.flush()
        val current = Logging.getRecentLogs().filterIsInstance<LogEntry.ErrorLog>().single { it.name == "当前构建样本" }
        assertEquals("debug", current.diagnostic?.client?.buildType)
        assertEquals(context.packageName, current.diagnostic?.client?.packageName)
        Logging.initialize(context); Logging.flush()
        assertEquals(oldClient, Logging.getLog(saved)?.diagnostic?.client)
        assertNull(Logging.getLog(unknown)?.diagnostic?.client)
        assertEquals("debug", Logging.getLog(current.id)?.diagnostic?.client?.buildType)
        File(folder, "metadata-results.txt").writeText("current_build=debug\nprevious_build=release\nunknown_import=unrecorded\npersistence=passed")
    }
}
