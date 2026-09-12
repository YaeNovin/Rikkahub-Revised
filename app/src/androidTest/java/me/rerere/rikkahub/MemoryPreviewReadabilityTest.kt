package me.rerere.rikkahub

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.BackgroundSurfaceStyle
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.components.ui.*
import me.rerere.rikkahub.ui.pages.chat.MemoryFullscreenPreview
import me.rerere.rikkahub.ui.pages.chat.MemoryPreviewContent
import me.rerere.rikkahub.ui.theme.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import android.os.ParcelFileDescriptor

class MemoryPreviewReadabilityTest {
    @Test(timeout = 30000) fun darkOpaquePreviewDoesNotInheritBlackWallpaperText() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // OEM devices can block startActivitySync indefinitely while waiting for activity idle.
        // Launch the dedicated fixture through shell, then inspect lifecycle with a bounded wait.
        val component = instrumentation.targetContext.packageName + "/androidx.activity.ComponentActivity"
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("am start -n $component")).use { it.readBytes() }
        var fixture: ComponentActivity? = null
        val deadline = System.nanoTime() + 8_000_000_000L
        while (fixture == null && System.nanoTime() < deadline) {
            instrumentation.runOnMainSync {
                fixture = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<ComponentActivity>().firstOrNull()
            }
            if (fixture == null) Thread.sleep(100)
        }
        val activity = requireNotNull(fixture) { "Preview test activity did not enter foreground" }
        try {
            val ready = CountDownLatch(1)
            instrumentation.runOnMainSync {
                activity.setContent {
                    val base = darkColorScheme(surface = Color(0xFF121316), background = Color(0xFF121316))
                    val settings = Settings().let { it.copy(advancedAppearanceSetting = it.advancedAppearanceSetting.copy(overlaySurfaceStyle = BackgroundSurfaceStyle.OPAQUE)) }
                    CompositionLocalProvider(LocalSettings provides settings, LocalDarkMode provides true,
                        LocalBaseThemeColorScheme provides base, LocalBackgroundBaseColorScheme provides base,
                        LocalAppearanceBackground provides AppearanceBackgroundSpec(null, 1f, 0f, foreground = Color.Black)) {
                        MaterialTheme(colorScheme = base.copy(onSurface = Color.Black, onBackground = Color.Black)) {
                            MemoryFullscreenPreview(MemoryPreviewContent("Readability regression", "Readable memory body\n".repeat(14), "id", "hash", "source"), onDismiss = {})
                            SideEffect { ready.countDown() }
                        }
                    }
                }
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS))
            Thread.sleep(700)
            val bitmap = instrumentation.uiAutomation.takeScreenshot()
            try {
                var brightText = 0
                for (y in bitmap.height / 5 until bitmap.height * 3 / 5 step 2) {
                    for (x in bitmap.width / 12 until bitmap.width * 3 / 4 step 2) {
                        val pixel = bitmap.getPixel(x, y)
                        if (android.graphics.Color.red(pixel) > 160 && android.graphics.Color.green(pixel) > 160 && android.graphics.Color.blue(pixel) > 160) brightText++
                    }
                }
                assertTrue("Expected light memory text over the dark opaque preview, got $brightText pixels", brightText > 500)
            } finally { bitmap.recycle() }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
}
