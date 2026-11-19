package me.rerere.rikkahub

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.context.*
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import org.junit.Rule
import org.junit.Assert.*
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Mock HTTP responses but real Coil SVG decoding, Compose layout, source toggles and hardware canvas. */
class MarkdownFenceBadgesDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun badgesStayInsideCodeChromeAndOnlyImageExpressionsChange() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".revised.appearanceqa"))
        var host: ComponentActivity? = null
        val resumed = CountDownLatch(1)
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
        val fetched = java.util.Collections.synchronizedSet(mutableSetOf<String>())
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" width="126" height="28" viewBox="0 0 126 28"><rect width="126" height="28" fill="#188038"/><rect width="55" height="28" fill="#444"/><text x="7" y="19" font-family="sans-serif" font-size="12" fill="white">Build</text><text x="62" y="19" font-family="sans-serif" font-size="12" fill="white">passing</text></svg>"""
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            fetched += chain.request().url.toString()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(svg.toResponseBody("image/svg+xml".toMediaType())).build()
        }.build()
        val loader = ImageLoader.Builder(context).components { add(OkHttpNetworkFetcherFactory(callFactory = { client })) }.build()
        SingletonImageLoader.setUnsafe(loader)
        instrumentation.runOnMainSync { app.registerActivityLifecycleCallbacks(callback) }
        try {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
                "am start -W -f 0x10008000 -n ${context.packageName}/androidx.activity.ComponentActivity")).use { it.readBytes() }
            check(resumed.await(20, TimeUnit.SECONDS))
            val urls = listOf("https://img.shields.io/badge/Android-green", "https://img.shields.io/badge/Kotlin-purple", "https://badgen.net/badge/CI/passing/green")
            val code = "<!-- Flat -->\n![Android](${urls[0]})\n# keep heading syntax\n![Kotlin](${urls[1]}) [![CI](${urls[2]})](https://example.com/project)\n`![literal](${urls[0]})`\n![photo](https://example.com/photo.png)"
            var dark by mutableStateOf(false)
            var wrap by mutableStateOf(true)
            compose.runOnUiThread { host!!.setContent {
                val settings = Settings().let { it.copy(displaySetting = it.displaySetting.copy(showLineNumbers = true, codeBlockAutoWrap = wrap)) }
                MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                    val nav = remember { Navigator(mutableStateListOf(Screen.Log)) }
                    val toaster = com.dokar.sonner.rememberToasterState()
                    CompositionLocalProvider(LocalSettings provides settings, LocalNavController provides nav, LocalDarkMode provides dark,
                        LocalToaster provides toaster) {
                        Surface(Modifier.fillMaxSize()) {
                            Column(Modifier.padding(16.dp).padding(top = 40.dp).testTag("fixture")) {
                                MarkdownBlock("```markdown\n$code\n```", Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
            } }
            compose.waitUntil(15_000) { compose.onAllNodes(hasContentDescription("Android")).fetchSemanticsNodes().isNotEmpty() }
            compose.waitUntil(15_000) { compose.onAllNodes(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "图片已加载")).fetchSemanticsNodes().size == 3 }
            compose.onNodeWithText("markdown").assertIsDisplayed()
            compose.onNodeWithText("<!-- Flat -->").assertIsDisplayed()
            compose.onNodeWithText("# keep heading syntax").assertIsDisplayed()
            compose.onNodeWithText("![photo](https://example.com/photo.png)").assertIsDisplayed()
            compose.onNodeWithContentDescription("Android").assertHeightIsEqualTo(28.dp).assertWidthIsEqualTo(126.dp)
            val folder = File(context.externalCacheDir, "markdown-badges").apply { mkdirs() }
            fun screenshot(name: String) {
                val bitmap = compose.onNodeWithTag("fixture").captureToImage().asAndroidBitmap()
                File(folder, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            screenshot("light-preview")
            compose.onNodeWithContentDescription(context.getString(R.string.code_block_source)).performClick()
            compose.onAllNodesWithContentDescription("Android").assertCountEquals(0)
            compose.onNodeWithText("![Android](${urls[0]})").assertIsDisplayed()
            screenshot("source")
            compose.onNodeWithContentDescription(context.getString(R.string.code_block_preview)).performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Android").fetchSemanticsNodes().isNotEmpty() }
            compose.runOnIdle { dark = true; wrap = false }
            compose.waitForIdle()
            screenshot("dark-nowrap")
            assertEquals(urls.toSet(), fetched.toSet())
        } finally {
            instrumentation.runOnMainSync { app.unregisterActivityLifecycleCallbacks(callback); host?.finish() }
            loader.shutdown()
        }
    }
}
