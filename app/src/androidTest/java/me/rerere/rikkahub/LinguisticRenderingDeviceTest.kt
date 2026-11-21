package me.rerere.rikkahub

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.WebView as AndroidWebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.render.*
import me.rerere.rikkahub.ui.components.richtext.*
import me.rerere.rikkahub.ui.context.*
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import me.rerere.rikkahub.ui.pages.webview.prepareFullscreenPreviewHtml
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Run with linguistic-qa.init.gradle: synthetic content, no provider calls or user data. */
class LinguisticRenderingDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private var activity: ComponentActivity? = null
    @Volatile private var view: AndroidWebView? = null
    private fun onMain(action: () -> Unit) {
        val done=CountDownLatch(1)
        val expired=java.util.concurrent.atomic.AtomicBoolean(false)
        val failure=java.util.concurrent.atomic.AtomicReference<Throwable?>()
        Handler(Looper.getMainLooper()).post { if(!expired.get())try { action() } catch(e:Throwable){failure.set(e)} finally {done.countDown()} }
        if(!done.await(10,TimeUnit.SECONDS)){expired.set(true);error("Main thread did not respond")}
        failure.get()?.let { throw it }
    }
    private fun launch() {
        val context=instrumentation.targetContext
        check(context.packageName.endsWith(".linguisticqa")) { "Use isolated linguistic QA package" }
        // qa_linguistic_renderers.py launches from ADB after each test-start event.
        // No in-process UiAutomation connection/background launch is needed on vendor ROMs.
        val until=SystemClock.elapsedRealtime()+10000
        while(activity==null && SystemClock.elapsedRealtime()<until){
            onMain { activity=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<ComponentActivity>().firstOrNull() }
            SystemClock.sleep(100)
        }
        requireNotNull(activity)
    }
    private fun js(script:String):String {
        val done=CountDownLatch(1);var result="null"
        onMain { view?.evaluateJavascript(script) { result=it;done.countDown() } ?: done.countDown() }
        check(done.await(10,TimeUnit.SECONDS));return result
    }
    private fun ready(id:String) {
        val until=SystemClock.elapsedRealtime()+15000
        while(SystemClock.elapsedRealtime()<until){
            if(js("window.__rikkaRenderStatus==='ready' && document.getElementById('renderer').dataset.renderer==='$id'")=="true")return
            SystemClock.sleep(100)
        }
        error("Renderer $id did not finish: "+js("document.getElementById('render-error')?.textContent"))
    }
    @After fun close() { onMain { activity?.finish() } }

    @Test fun productionPagesWorkInAndroidWebViewInBothThemesAndFullscreen() {
        launch()
        val artifacts=File(instrumentation.targetContext.filesDir,"linguistic-qa").apply { mkdirs() }
        val results=org.json.JSONArray()
        for(dark in listOf(false,true)) for(spec in linguisticRendererSpecs){
            val colors=if(dark)darkColorScheme() else lightColorScheme()
            val html=buildLinguisticRendererHtml(spec.id,spec.example,colors,"Render failed",if(dark && spec.id=="ipa") 2f else 1f)
            onMain { view=null;activity!!.setContent { key(html) { MaterialTheme(colorScheme=colors) { Surface { LinguisticCodePreview(html) { view=it } } } } } }
            ready(spec.id)
            if(spec.id in setOf("ipa","ruby_annotation","gloss")) {
                val until=SystemClock.elapsedRealtime()+3000
                var compact=false
                while(!compact && SystemClock.elapsedRealtime()<until) {
                    compact=js("(function(){const children=document.getElementById('renderer').children;const bottom=children[children.length-1].getBoundingClientRect().bottom;return bottom<=innerHeight+2 && innerHeight-bottom<=40;})()") == "true"
                    if(!compact)SystemClock.sleep(50)
                }
                assertTrue("Short linguistic cards must not reserve a tall empty preview",compact)
            }
            assertEquals("true",js("document.fonts.check('20px RikkaIPA')"))
            assertEquals("0",js("document.querySelectorAll('#renderer script,#renderer img').length"))
            val artifact=JSONObject(JSONTokener(js("JSON.stringify(window.__rikkaArtifact)")).nextValue() as String)
            assertEquals(spec.id,artifact.getString("renderer"))
            assertTrue(artifact.getString("accessible_text").isNotBlank())
            if(spec.id=="dependency")assertEquals("true",js("Array.from(document.querySelectorAll('path.edge')).every(p=>p.getAttribute('marker-end'))"))
            capture("${spec.id}-${if(dark) "dark" else "light"}")
            results.put(JSONObject().put("renderer",spec.id).put("dark",dark).put("ready",true))
            val full=prepareFullscreenPreviewHtml(html,true)
            assertFalse(full.contains("fullscreen-pan.js"))
            onMain { view=null;activity!!.setContent { key(full) { MaterialTheme(colorScheme=colors) { Surface { LinguisticCodePreview(full) { view=it } } } } } }
            ready(spec.id)
            js("window.rikkaPrepareExport('message')")
            assertEquals("true",js("Array.from(document.querySelectorAll('details')).every(d=>d.open)"))
        }
        File(artifacts,"results.json").writeText(results.toString(2))
        println("LINGUISTIC_ARTIFACTS=${artifacts.absolutePath}")
    }

    @Test fun streamingAndDisabledSettingsKeepOriginalSourceAndRemoveWebView() {
        launch()
        var enabled by mutableStateOf(true)
        var complete by mutableStateOf(false)
        val source=linguisticRendererSpecs.first().example
        onMain { activity!!.setContent {
            val settings=Settings(displaySetting=DisplaySetting(codeBlockAutoCollapse=false,
                linguisticRendering=LinguisticRenderingSettings(ipa=enabled)))
            MaterialTheme(colorScheme=darkColorScheme()) {
                val toaster=com.dokar.sonner.rememberToasterState()
                val navigator=remember { Navigator(mutableStateListOf(Screen.Log)) }
                CompositionLocalProvider(LocalSettings provides settings,LocalNavController provides navigator,LocalToaster provides toaster,
                    LocalDarkMode provides true,LocalRichTextStreaming provides !complete) {
                    Surface(color=Color(0xff141218)) { HighlightCodeBlock(source,"ipa",completeCodeBlock=complete) }
                }
            }
        } }
        capture("source-streaming")
        assertEquals(0,countViews())
        onMain { complete=true }
        val until=SystemClock.elapsedRealtime()+12000
        while(countViews()==0 && SystemClock.elapsedRealtime()<until)SystemClock.sleep(100)
        assertEquals(1,countViews())
        onMain { enabled=false }
        capture("source-disabled")
        assertEquals(0,countViews())
    }
    private fun countViews():Int {
        var count=0
        onMain {
            fun visit(node:android.view.View){
                if(node is AndroidWebView)count++
                if(node is android.view.ViewGroup)for(i in 0 until node.childCount)visit(node.getChildAt(i))
            }
            visit(activity!!.window.decorView)
        }
        return count
    }
    private fun capture(name:String) {
        val marker=File(instrumentation.targetContext.filesDir,"linguistic-qa/$name-captured")
        marker.parentFile!!.mkdirs();marker.delete()
        instrumentation.sendStatus(2,android.os.Bundle().apply { putString("linguistic_capture",name) })
        val until=SystemClock.elapsedRealtime()+15000
        while(!marker.exists() && SystemClock.elapsedRealtime()<until)SystemClock.sleep(50)
        check(marker.exists()) { "ADB driver did not capture $name" }
    }
}
