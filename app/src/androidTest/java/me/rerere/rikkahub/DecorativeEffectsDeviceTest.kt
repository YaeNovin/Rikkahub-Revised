package me.rerere.rikkahub

import android.os.Build
import android.os.SystemClock
import android.webkit.WebView as AndroidWebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import android.content.Intent
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.ui.components.richtext.buildGeometryPreviewHtml
import me.rerere.rikkahub.ui.components.webview.WEB_VIEW_BASE_URL
import me.rerere.rikkahub.ui.components.webview.WebView
import me.rerere.rikkahub.ui.components.webview.rememberWebViewState
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.*
import org.junit.After
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Isolated debug-package flow; no model calls or changes to user conversations. */
class DecorativeEffectsDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    @Volatile private lateinit var view: AndroidWebView
    @Volatile private var activity: ComponentActivity? = null

    // Post directly instead of waiting for instrumentation/Espresso idle.
    // A bounded wait also makes an unresponsive host a test failure, not a hang.
    private fun onMain(action: () -> Unit) {
        val done = CountDownLatch(1)
        val expired = java.util.concurrent.atomic.AtomicBoolean(false)
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            if (!expired.get()) try { action() } catch (error: Throwable) { failure.set(error) }
            done.countDown()
        }
        if (!done.await(10, TimeUnit.SECONDS)) { expired.set(true); error("Main-thread callback timed out before rendering") }
        failure.get()?.let { throw it }
    }

    @After fun closeProbe() { onMain { activity?.finish() } }

    private fun launchProbe() {
        val context = instrumentation.targetContext
        onMain {
            context.startActivity(Intent(context, ComponentActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        }
        val deadline = SystemClock.elapsedRealtime() + 10000
        while (activity == null && SystemClock.elapsedRealtime() < deadline) {
            onMain {
                activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<ComponentActivity>().firstOrNull { it.packageName == context.packageName }
            }
            SystemClock.sleep(50)
        }
        assertNotNull("Probe activity did not resume", activity)
    }

    private fun js(script: String): String {
        val done = CountDownLatch(1); var result = "null"
        onMain { view.evaluateJavascript(script) { result = it; done.countDown() } }
        assertTrue("JavaScript response timed out", done.await(5, TimeUnit.SECONDS))
        return result
    }
    private fun diagnostics() = JSONObject(JSONTokener(js("JSON.stringify(window.rikkaRenderDiagnostics())")).nextValue() as String)
    private fun shell(command: String): String = android.os.ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }

    @Test fun sigilEffectsInHardwareWindow() = probe("sigil", """[{"type":"ring","radius":94},{"type":"ticks","radius":86,"count":60,"speed":-8},{"type":"star","points":6,"step":2,"radius":68,"speed":18},{"type":"ring","radius":34}]""")
    @Test fun patternCachedPathsInHardwareWindow() = probe("pattern", """[{"type":"mandala","rings":4,"petals":16,"speed":12},{"type":"rose","radius":36,"speed":-20}]""")

    private fun probe(kind: String, layers: String) {
        val context = instrumentation.targetContext
        val artifacts = File(context.getExternalFilesDir(null), "decorative-effects/$kind-${System.currentTimeMillis()}").apply { mkdirs() }
        launchProbe()
        val source = """{"title":"Effects QA $kind","animate":true,"effects":{"stardust":{"count":96,"speed":25},"trail":{"samples":6},"reveal":{"duration":0.35,"stagger":0.12}},"layers":$layers}"""
        val html = buildGeometryPreviewHtml(kind, source, true)
        onMain { requireNotNull(activity).setContent {
            val state = rememberWebViewState(data = html, baseUrl = WEB_VIEW_BASE_URL, mimeType = "text/html", encoding = "UTF-8",
                settings = { blockNetworkLoads = true; allowFileAccess = false; allowContentAccess = false; domStorageEnabled = false })
            WebView(state, Modifier.fillMaxSize(), onCreated = { view = it }, nativeZoomEnabled = false)
        } }
        val viewDeadline = SystemClock.elapsedRealtime() + 15000
        while (!::view.isInitialized && SystemClock.elapsedRealtime() < viewDeadline) SystemClock.sleep(50)
        assertTrue("Preview did not attach", ::view.isInitialized)
        val deadline = SystemClock.elapsedRealtime() + 20_000
        while (js("window.__rikkaRenderStatus") != "\"ready\"" && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
        assertEquals("\"ready\"", js("window.__rikkaRenderStatus"))
        onMain { assertTrue("Attached WebView must inherit hardware acceleration", view.isHardwareAccelerated); assertEquals(0, view.layerType) }
        SystemClock.sleep(500)
        val start = diagnostics()
        assertTrue("Native environment delivered", start.getBoolean("hardwareAccelerated"))
        assertTrue("Focused flow must be playing", start.getBoolean("active"))
        shell("dumpsys gfxinfo ${context.packageName} reset")
        val startTime = SystemClock.elapsedRealtime()
        SystemClock.sleep(6000)
        val end = diagnostics()
        val durationMs = SystemClock.elapsedRealtime() - startTime
        val frames = end.getInt("frames") - start.getInt("frames")
        assertTrue("Animation advanced", frames >= 12)
        assertTrue(end.getJSONObject("effects").getInt("pixels") <= 1200000)
        if (kind == "pattern") assertEquals("Paths are not rebuilt per animation frame", start.getJSONObject("artwork").getInt("pathBuilds"), end.getJSONObject("artwork").getInt("pathBuilds"))
        File(artifacts, "gfxinfo.txt").writeText(shell("dumpsys gfxinfo ${context.packageName}"))
        File(artifacts, "gfxinfo-framestats.txt").writeText(shell("dumpsys gfxinfo ${context.packageName} framestats"))
        File(artifacts, "meminfo.txt").writeText(shell("dumpsys meminfo ${context.packageName}"))
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(artifacts, "visible.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
        onMain { requireNotNull(activity).moveTaskToBack(true) }
        SystemClock.sleep(200)
        val paused = diagnostics(); SystemClock.sleep(400)
        assertFalse(paused.getBoolean("active"))
        assertEquals(paused.getInt("frames"), diagnostics().getInt("frames"))
        onMain { context.startActivity(Intent(context, ComponentActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)) }
        SystemClock.sleep(400)
        assertTrue(diagnostics().getInt("frames") > paused.getInt("frames"))
        js("window.rikkaPrepareExport()")
        val frozen = diagnostics(); SystemClock.sleep(300)
        assertEquals(frozen.getInt("frames"), diagnostics().getInt("frames"))
        val report = JSONObject().put("renderer", kind).put("device", Build.MODEL).put("android", Build.VERSION.RELEASE)
            .put("package", context.packageName).put("build", context.packageManager.getPackageInfo(context.packageName, 0).versionName).put("webView", AndroidWebView.getCurrentWebViewPackage()?.versionName)
            .put("runs", 1).put("durationMs", durationMs).put("renderedFrames", frames).put("rendererFps", frames * 1000.0 / durationMs)
            .put("start", start).put("end", end).put("lifecyclePauseVerified", true).put("exportFreezeVerified", true)
        File(artifacts, "report.json").writeText(report.toString(2))
        println("EFFECTS_PERF_ARTIFACTS=${artifacts.absolutePath}")
    }
}
