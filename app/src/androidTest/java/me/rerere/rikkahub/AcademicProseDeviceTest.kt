package me.rerere.rikkahub

import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.*
import androidx.compose.ui.geometry.Offset
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.render.LinguisticRenderingSettings
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.components.richtext.MarkdownRenderMode
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Isolated synthetic production Markdown, not a direct HTML-only fixture. */
class AcademicProseDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var host: ComponentActivity
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private var source by mutableStateOf("")
    private var dark by mutableStateOf(false)
    private var scale by mutableStateOf(1f)
    private var mode by mutableStateOf(MarkdownRenderMode.STANDARD)
    private var enabled by mutableStateOf(true)
    private var opened: String? = null

    @Before fun launch() {
        check(instrumentation.targetContext.packageName.endsWith(".citationqa")) { "Use independent citationqa debug package" }
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
            "am start -W -f 0x10008000 -n ${instrumentation.targetContext.packageName}/androidx.activity.ComponentActivity"
        )).use { it.readBytes() }
        val until = android.os.SystemClock.elapsedRealtime() + 15000
        while (!::host.isInitialized && android.os.SystemClock.elapsedRealtime() < until) {
            instrumentation.runOnMainSync {
                androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
                    .filterIsInstance<ComponentActivity>().firstOrNull()?.let { host = it }
            }
            Thread.sleep(100)
        }
        check(::host.isInitialized)
        compose.runOnUiThread { host.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalSettings provides Settings(displaySetting = DisplaySetting(
                linguisticRendering = LinguisticRenderingSettings(ipa = enabled, ruby = enabled, literary = enabled))),
                LocalDarkMode provides dark, LocalDensity provides Density(density.density, scale),
                LocalUriHandler provides object : UriHandler { override fun openUri(uri: String) { opened = uri } }) {
                MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                    Surface { Column(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp).verticalScroll(rememberScrollState())) {
                        MarkdownBlock(source, renderMode = mode)
                    } }
                }
            }
        } }
    }

    @After fun close() { if (::host.isInitialized) instrumentation.runOnMainSync { host.finish() } }

    private fun views(): List<WebView> {
        val result = mutableListOf<WebView>()
        instrumentation.runOnMainSync {
            fun visit(view: android.view.View) {
                if (view is WebView) result += view
                if (view is android.view.ViewGroup) repeat(view.childCount) { visit(view.getChildAt(it)) }
            }
            visit(host.window.decorView)
        }
        return result
    }

    private fun js(view: WebView, script: String): String {
        val latch = CountDownLatch(1)
        var value = "null"
        instrumentation.runOnMainSync { view.evaluateJavascript(script) { value = it; latch.countDown() } }
        check(latch.await(5, TimeUnit.SECONDS))
        return value
    }

    private fun ready(predicate: String): WebView {
        val until = android.os.SystemClock.elapsedRealtime() + 15000
        while (android.os.SystemClock.elapsedRealtime() < until) {
            // Drive Compose's test clock after changing source/theme; native
            // evaluateJavascript alone does not advance pending recompositions.
            compose.waitForIdle()
            views().forEach { view -> if (js(view, "window.__rikkaRenderStatus==='ready' && ($predicate)") == "true") return view }
            Thread.sleep(100)
        }
        error("Academic prose did not finish: $predicate; views=" + views().map {
            js(it, "JSON.stringify({status:window.__rikkaRenderStatus,body:document.body?.innerText,ready:document.readyState})")
        } + "; native=" + compose.onRoot(useUnmergedTree = true).printToString())
    }

    private fun capture(name: String) {
        compose.waitForIdle(); Thread.sleep(350)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "academic-prose-qa").apply { mkdirs() }
        try { File(directory, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
    }

    @Test fun inlineRubyIpaMathAndChemicalNotationWorkInBothRolesAndThemes() {
        for (isDark in listOf(false, true)) for (role in MarkdownRenderMode.entries) {
            compose.runOnIdle {
                dark = isDark; mode = role; scale = if (isDark) 1.6f else 1f
                source = "这是<ruby>漢字<rt>かんじ</rt></ruby>，/t͡ʃ/，<ipa>kat</ipa>，公式\\(x_i^2+\\frac{1}{2}\\)，反应\\ce{2H2 + O2 -> 2H2O}。"
            }
            val view = ready("document.querySelectorAll('ruby').length===1 && document.querySelectorAll('.katex').length===2")
            assertEquals("2", js(view, "document.querySelectorAll('.ipa').length"))
            assertEquals("0", js(view, "document.querySelectorAll('.math-error').length"))
            assertEquals("true", js(view, "document.fonts.check('18px RikkaIPA')"))
            assertEquals("true", js(view, "document.documentElement.scrollWidth<=innerWidth+2"))
            assertNull(opened)
            capture("inline-${role.name}-${if (isDark) "dark-large" else "light"}")
        }
    }

    @Test fun verticalAndApparatusRemainReadableAndExportEveryColumn() {
        compose.runOnIdle {
            dark = true; source = "<vertical lang=\"ja\"><ruby>古語<rt>こご</rt></ruby>ゐゑヰヱ・々ゝゞ〱〲。" +
                "<ruby>物語<rt>ものがたり</rt></ruby>の文。".repeat(32) + "</vertical>"
        }
        val view = ready("!!document.querySelector('.vertical-rl')")
        assertEquals("\"vertical-rl\"", js(view, "getComputedStyle(document.querySelector('.vertical-rl')).writingMode"))
        assertEquals("true", js(view, "document.querySelector('.vertical-rl').clientHeight>=300"))
        val deviceDensity = instrumentation.targetContext.resources.displayMetrics.density
        compose.waitUntil(5000) { view.height / deviceDensity >= 300f }
        capture("vertical-dark")
        assertEquals("true", js(view, "(()=>{const n=document.querySelector('.vertical-scroll');n.scrollLeft=-100;return n.scrollLeft<0})()"))
        js(view, "window.rikkaPrepareExport('message')")
        assertEquals("true", js(view, "document.querySelectorAll('.vertical-export-panel').length>1"))
        assertEquals("true", js(view, "document.documentElement.scrollWidth<=innerWidth+2"))
        compose.runOnIdle { source = "本文<app><lem>春</lem><rdg wit=\"#A\">花</rdg><note>底本は春。</note></app><note n=\"一\">校注の詳細</note>。" }
        val apparatus = ready("document.querySelectorAll('details').length===3")
        val before = js(apparatus, "document.getElementById('academic').clientHeight").toInt()
        js(apparatus, "document.querySelectorAll('details').forEach(n=>n.open=true)")
        compose.waitUntil(5000) { js(apparatus, "document.getElementById('academic').clientHeight").toInt() > before }
        compose.waitUntil(5000) {
            compose.waitForIdle()
            val css = js(apparatus, "document.getElementById('academic').clientHeight").toFloat()
            apparatus.height / deviceDensity >= css - 2
        }
        capture("apparatus-expanded-dark")
        compose.runOnIdle { enabled = false }
        ready("document.querySelectorAll('details').length===0 && document.getElementById('academic').textContent.includes('底本')")
    }

    @Test fun linksWaitForAGestureAndIncompleteStreamingMarkupKeepsItsSource() {
        compose.runOnIdle { source = "<ruby>漢<rt>かん</rt></ruby> [打开参考](https://example.test/reference)" }
        val view = ready("document.querySelectorAll('a').length===1")
        assertNull(opened)
        js(view, "document.querySelector('a').click()")
        compose.waitForIdle()
        assertNull("A scripted click must not navigate", opened)
        val point = org.json.JSONArray(js(view, "JSON.stringify((()=>{const r=document.querySelector('a').getBoundingClientRect();return[(r.left+r.right)/2,(r.top+r.bottom)/2,innerWidth]})())").trim('"').replace("\\\"", "\""))
        val ratio = view.width / point.getDouble(2).toFloat()
        compose.onNodeWithTag("academic-prose").performTouchInput { click(Offset(point.getDouble(0).toFloat() * ratio, point.getDouble(1).toFloat() * ratio)) }
        compose.waitUntil(5000) { opened == "https://example.test/reference" }
        compose.runOnIdle { source = "文字 <ruby>漢<rt>かん" }
        compose.waitUntil(10000) { views().isEmpty() }
        compose.onAllNodesWithText("文字 <ruby>漢<rt>かん", substring = true).onFirst().assertIsDisplayed()
        capture("incomplete-source")
    }
}
