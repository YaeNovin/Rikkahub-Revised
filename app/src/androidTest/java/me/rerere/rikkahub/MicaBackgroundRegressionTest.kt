package me.rerere.rikkahub

import android.graphics.Bitmap
import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import dev.chrisbanes.haze.rememberHazeState
import java.io.File
import me.rerere.rikkahub.data.datastore.*
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.ui.components.ai.ModelSearchField
import me.rerere.rikkahub.ui.components.ui.*
import me.rerere.rikkahub.ui.context.*
import me.rerere.rikkahub.ui.pages.chat.AssistantBackground
import me.rerere.rikkahub.ui.pages.chat.ChatFloatingHeader
import me.rerere.rikkahub.ui.theme.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.Before
import org.junit.After
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Fixed synthetic images only; never reads or changes the user's QA settings. */
@org.junit.FixMethodOrder(org.junit.runners.MethodSorters.NAME_ASCENDING)
class MicaBackgroundRegressionTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var host: ComponentActivity
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val folder get() = File(context.externalCacheDir, "mica-background-regression").apply { mkdirs() }

    // Same direct-host strategy as MenuViewportRegressionTest: some OEM builds
    // leave ActivityScenario waiting before ever launching its test Activity.
    @Before fun launchHostDirectly() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = context.applicationContext as Application
        val resumed = CountDownLatch(1)
        val callback = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                if (activity.javaClass == ComponentActivity::class.java) {
                    host = activity as ComponentActivity
                    resumed.countDown()
                }
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
            android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
                "am start -W -f 0x10008000 -n ${context.packageName}/androidx.activity.ComponentActivity"
            )).use { it.readBytes() }
            check(resumed.await(20, TimeUnit.SECONDS)) { "Appearance regression host did not resume" }
        } finally {
            instrumentation.runOnMainSync { app.unregisterActivityLifecycleCallbacks(callback) }
        }
    }

    @After fun closeHost() {
        if (::host.isInitialized) InstrumentationRegistry.getInstrumentation().runOnMainSync { host.finish() }
    }

    private fun setTestContent(content: @Composable () -> Unit) {
        compose.runOnUiThread { host.setContent(content = content) }
    }

    private fun wallpaper(): String {
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        for (y in 0 until 256) for (x in 0 until 256) bitmap.setPixel(x, y,
            if (x < 128) android.graphics.Color.rgb(230, 45, 70) else android.graphics.Color.rgb(30, 85, 225))
        val file = File(folder, "synthetic-wallpaper.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return file.toURI().toString()
    }

    private fun capture(tag: String, name: String): Bitmap {
        val bitmap = compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
        File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return bitmap
    }

    private fun backgroundVisible(bitmap: Bitmap): Boolean {
        val y = (bitmap.height * .82).toInt()
        val left = bitmap.getPixel(bitmap.width / 4, y)
        val right = bitmap.getPixel(bitmap.width * 3 / 4, y)
        return android.graphics.Color.red(left) - android.graphics.Color.red(right) > 20 &&
            android.graphics.Color.blue(right) - android.graphics.Color.blue(left) > 20
    }

    @Test fun globalAssistantCardsAndIndependentMicaKeepWallpaper() {
        val uri = wallpaper()
        var mode by mutableIntStateOf(0)
        var source by mutableStateOf(true)
        var dark by mutableStateOf(false)
        setTestContent {
            val scheme = if (dark) darkColorScheme() else lightColorScheme()
            val assistant = remember(source) { Assistant(background = uri.takeIf { source }) }
            val settings = Settings(assistants = listOf(assistant), assistantId = assistant.id,
                advancedAppearanceSetting = AdvancedAppearanceSetting(pageSurfaceStyle = BackgroundSurfaceStyle.MICA,
                    pageSurfaceOpacity = .45f, enableGlobalBackground = source, globalBackground = uri,
                    overlaySurfaceStyle = BackgroundSurfaceStyle.MICA, overlaySurfaceOpacity = .45f))
            MaterialTheme(colorScheme = scheme) {
                CompositionLocalProvider(LocalSettings provides settings, LocalDarkMode provides dark,
                    LocalPageSurfaceStyle provides BackgroundSurfaceStyle.MICA,
                    LocalAppearanceBackground provides AppearanceBackgroundSpec(uri.takeIf { source }, 1f, 0f)) {
                    GlobalGlassTheme(true, .45f, BackgroundSurfaceStyle.MICA) {
                        Box(Modifier.size(300.dp, 400.dp).testTag("scene")) {
                            if (mode == 1) AssistantBackground(settings, Modifier.fillMaxSize())
                            else GlobalAppBackground(settings, Modifier.fillMaxSize())
                            when (mode) {
                                2 -> Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainer) {
                                    Text("云母卡片保留背景", Modifier.padding(16.dp))
                                }
                                3 -> IsolatedAppearanceSurface(BackgroundSurfaceStyle.MICA, .45f, 20f,
                                    Modifier.fillMaxSize(), backgroundRendering = IsolatedBackgroundRendering.INDEPENDENT) {
                                    Column(Modifier.padding(16.dp)) {
                                        Text("独立抽屉 / 浮层")
                                        ModelSearchField("") {}
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        for (night in listOf(false, true)) for (role in 0..3) {
            compose.runOnIdle { dark = night; mode = role }
            compose.waitUntil(10_000) { backgroundVisible(compose.onNodeWithTag("scene").captureToImage().asAndroidBitmap()) }
            assertTrue(backgroundVisible(capture("scene", "${if (night) "dark" else "light"}-$role")))
        }
        compose.runOnIdle { source = false; mode = 3 }
        compose.waitForIdle()
        assertFalse(backgroundVisible(capture("scene", "no-background")))
    }

    @Test fun actualBottomSheetReplaysMicaWallpaper() {
        val uri = wallpaper()
        setTestContent {
            MaterialTheme {
                val nav = remember { Navigator(mutableListOf()) }
                val settings = Settings(advancedAppearanceSetting = AdvancedAppearanceSetting(
                    overlaySurfaceStyle = BackgroundSurfaceStyle.MICA, overlaySurfaceOpacity = .45f))
                CompositionLocalProvider(LocalSettings provides settings, LocalNavController provides nav,
                    LocalAppearanceBackground provides AppearanceBackgroundSpec(uri, 1f, 0f)) {
                    AppearanceModalBottomSheet(onDismissRequest = {}) {
                        Column(Modifier.fillMaxWidth().height(320.dp).testTag("sheet")) {
                            Text("模型选择 · 固定合成内容", Modifier.padding(16.dp))
                            ModelSearchField("") {}
                        }
                    }
                }
            }
        }
        compose.waitUntil(10_000) {
            runCatching { backgroundVisible(compose.onNodeWithTag("sheet").captureToImage().asAndroidBitmap()) }.getOrDefault(false)
        }
        assertTrue(backgroundVisible(capture("sheet", "model-sheet")))
    }

    @Test fun floatingButtonsWorkAndCoverOffLeavesReadingGapUnpainted() {
        var cover by mutableStateOf(false)
        var clicks = 0
        setTestContent {
            MaterialTheme {
                val settings = Settings(displaySetting = DisplaySetting(showTopBarBackground = cover),
                    advancedAppearanceSetting = AdvancedAppearanceSetting(pageSurfaceStyle = BackgroundSurfaceStyle.MICA))
                CompositionLocalProvider(LocalSettings provides settings) {
                    Box(Modifier.fillMaxSize().background(Color.Magenta)) {
                        ChatFloatingHeader(settings, rememberHazeState(), true,
                            { clicks++ }, { clicks++ }, { clicks++ })
                    }
                }
            }
        }
        compose.onNodeWithTag("chat_open_drawer").assertHasClickAction().performClick()
        compose.onNodeWithTag("chat_new_conversation").performClick()
        compose.onNodeWithTag("chat_open_menu").performClick()
        compose.runOnIdle { assertEquals(3, clicks) }
        val transparent = capture("chat_floating_header", "header-cover-off")
        val off = transparent.getPixel(transparent.width / 2, transparent.height * 3 / 4)
        assertEquals(android.graphics.Color.MAGENTA, off)
        compose.runOnIdle { cover = true }
        val shaded = capture("chat_floating_header", "header-cover-on")
        assertNotEquals(off, shaded.getPixel(shaded.width / 2, shaded.height * 3 / 4))
    }

}
