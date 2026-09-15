package me.rerere.rikkahub

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.AdvancedAppearanceSetting
import me.rerere.rikkahub.data.datastore.BackgroundSurfaceStyle
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.ui.components.ui.*
import me.rerere.rikkahub.ui.context.LocalSettings
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.Before
import org.junit.After
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MenuViewportRegressionTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var host: ComponentActivity

    @Before fun launchHostDirectly() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as Application
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
            val command = "am start -W -f 0x10008000 -n ${instrumentation.targetContext.packageName}/androidx.activity.ComponentActivity"
            android.os.ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand(command),
            ).use { it.readBytes() }
            check(resumed.await(20, TimeUnit.SECONDS)) { "Regression host did not resume" }
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

    private fun createWallpaper(): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "menu-regression-wallpaper.png")
        val bitmap = Bitmap.createBitmap(128, 256, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint()
        listOf(0xffcf5040.toInt(), 0xff40a060.toInt(), 0xff4268c4.toInt(), 0xffd9b840.toInt()).forEachIndexed { index, color ->
            paint.color = color
            canvas.drawRect(0f, index * 64f, 128f, (index + 1) * 64f, paint)
        }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return file
    }

    @Composable
    private fun Fixture(background: File?, content: @Composable () -> Unit) {
        MaterialTheme(colorScheme = lightColorScheme()) {
            CompositionLocalProvider(
                LocalSettings provides Settings(advancedAppearanceSetting = AdvancedAppearanceSetting(
                    overlaySurfaceStyle = if (background != null) BackgroundSurfaceStyle.TRANSLUCENT else BackgroundSurfaceStyle.OPAQUE,
                    overlaySurfaceOpacity = 0f,
                )),
                LocalAppearanceBackground provides background?.let {
                    AppearanceBackgroundSpec(it.absolutePath, opacity = 1f, blurRadius = 0f, foreground = Color.Black)
                },
            ) { content() }
        }
    }

    @Test fun sixHundredOptionsStayBoundedAndWallpaperDoesNotScrollOrZoom() {
        val wallpaper = createWallpaper()
        val scroll = ScrollState(0)
        lateinit var scope: CoroutineScope
        var expanded by mutableStateOf(true)
        var selection by mutableIntStateOf(-1)
        var density = 1f
        setTestContent {
            density = LocalDensity.current.density
            scope = rememberCoroutineScope()
            Fixture(wallpaper) {
                Box(Modifier.padding(top = 64.dp)) {
                    Text("Anchor")
                    AppearanceDropdownMenu(
                        expanded = expanded, onDismissRequest = { expanded = false },
                        scrollState = scroll, maxHeight = 240.dp,
                        modifier = Modifier.width(320.dp).testTag("menu"),
                    ) {
                        repeat(600) { index ->
                            DropdownMenuItem(text = { Text("Option $index", maxLines = 1) },
                                onClick = { selection = index; expanded = false })
                        }
                    }
                }
                Text("Selected $selection", Modifier.testTag("selection"))
            }
        }
        compose.onNodeWithTag("menu").assertIsDisplayed()
        compose.waitUntil(10_000) {
            val image = compose.onNodeWithTag("menu").captureToImage().toPixelMap()
            val pixel = image[image.width - 20, image.height / 3]
            pixel.green > pixel.red * 1.2f && pixel.green > pixel.blue * 1.1f
        }
        val before = compose.onNodeWithTag("menu").captureToImage()
        assertTrue("Menu must fit its 240dp viewport", before.height <= 242 * density)
        assertTrue("A long menu must retain a usable visible viewport", before.height >= 160 * density)
        assertTrue(scroll.maxValue > before.height * 10)
        compose.runOnIdle { scope.launch { scroll.scrollTo(scroll.maxValue) } }
        compose.waitForIdle()
        compose.onNodeWithText("Option 599").assertIsDisplayed()
        val after = compose.onNodeWithTag("menu").captureToImage()
        assertEquals(before.width, after.width)
        assertEquals(before.height, after.height)
        val a = before.toPixelMap()
        val b = after.toPixelMap()
        for (fraction in listOf(3, 2)) {
            assertEquals("Wallpaper must remain fixed while only options scroll",
                a[a.width - 20, a.height / fraction], b[b.width - 20, b.height / fraction])
        }
        val folder = InstrumentationRegistry.getInstrumentation().targetContext.externalCacheDir!!
        File(folder, "menu-before-scroll.png").outputStream().use { before.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
        File(folder, "menu-after-scroll.png").outputStream().use { after.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
        compose.onNodeWithText("Option 599").performClick()
        compose.onNodeWithTag("selection").assertTextEquals("Selected 599")
        compose.onNodeWithTag("menu").assertDoesNotExist()
    }

    @Test fun exposedSelectorAlsoScrollsLongListWithoutBackground() {
        var selected by mutableIntStateOf(0)
        setTestContent {
            Fixture(null) {
                Box(Modifier.padding(top = 64.dp).width(320.dp)) {
                    Select(options = (0 until 500).toList(), selectedOption = selected,
                        onOptionSelected = { selected = it }, optionToString = { "Entry $it" })
                }
            }
        }
        compose.onNodeWithText("Entry 0").performClick()
        compose.onNodeWithText("Entry 499").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("Entry 499").assertIsDisplayed()
        compose.runOnIdle { assertEquals(499, selected) }
    }
}
