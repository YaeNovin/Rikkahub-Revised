package me.rerere.rikkahub

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.os.*
import android.webkit.WebView as AndroidWebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.*
import me.rerere.rikkahub.data.ai.transformers.DocumentAsPromptTransformer
import me.rerere.rikkahub.data.render.*
import me.rerere.rikkahub.ui.components.richtext.*
import org.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Synthetic documents only. Must run with music-qa.init.gradle and the external ADB driver. */
class MusicRenderingDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private var activity: ComponentActivity? = null
    @Volatile private var view: AndroidWebView? = null
    private val artifacts get() = File(instrumentation.targetContext.filesDir,"music-qa").apply { mkdirs() }
    private fun onMain(action: () -> Unit) {
        val done=CountDownLatch(1);val expired=java.util.concurrent.atomic.AtomicBoolean(false)
        val failure=java.util.concurrent.atomic.AtomicReference<Throwable?>()
        Handler(Looper.getMainLooper()).post { if(!expired.get())try{action()}catch(e:Throwable){failure.set(e)}finally{done.countDown()} }
        if(!done.await(10,TimeUnit.SECONDS)){expired.set(true);error("Main thread did not respond")}
        failure.get()?.let{throw it}
    }
    private fun launch() {
        check(instrumentation.targetContext.packageName.endsWith(".musicqa"))
        val until=SystemClock.elapsedRealtime()+10000
        while(activity==null && SystemClock.elapsedRealtime()<until){
            onMain { activity=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<ComponentActivity>().firstOrNull() }
            SystemClock.sleep(100)
        }
        requireNotNull(activity)
    }
    private fun js(script:String):String {
        val done=CountDownLatch(1);var result="null"
        onMain { view?.evaluateJavascript(script){result=it;done.countDown()} ?: done.countDown() }
        check(done.await(15,TimeUnit.SECONDS));return result
    }
    private fun ready() {
        val until=SystemClock.elapsedRealtime()+25000
        while(SystemClock.elapsedRealtime()<until){
            if(js("window.__rikkaRenderStatus==='ready' && typeof window.rikkaScoreInfo==='function'")=="true")return
            SystemClock.sleep(100)
        }
        error("Score did not finish: "+js("document.getElementById('render-error')?.textContent"))
    }
    private fun capture(name:String) {
        val marker=File(artifacts,"$name-captured");marker.delete()
        instrumentation.sendStatus(2,Bundle().apply{putString("music_capture",name)})
        val until=SystemClock.elapsedRealtime()+15000
        while(!marker.exists()&&SystemClock.elapsedRealtime()<until)SystemClock.sleep(50)
        check(marker.exists())
    }
    @After fun close(){onMain{activity?.finish()}}

    @Test fun scoresPaginateAndExportEveryPageInBothThemes() {
        launch();val results=JSONArray()
        val xml=RendererCatalog.find("musicxml")!!.example
        val measure=xml.substringAfter("<part id='P1'>").substringBefore("</part>")
        val longXml=xml.replace(measure,(1..24).joinToString(""){ measure.replace("number='1'","number='$it'") })
        val samples=linkedMapOf(
            "abc" to (InteractiveCodeRenderer.ABC to "X:1\nT:Mobile score\nM:4/4\nL:1/4\nK:C\n"+(1..20).joinToString("\n"){ "\"C\"CDEF|\"G7\"GABc|" }),
            "jianpu" to (InteractiveCodeRenderer.JIANPU to "title: 和声分页\n1=C\n"+(1..12).joinToString("\n"){ "1 2 3 4 | 5 6 7 1' |\nH: I | V7\nL: 一闪一闪亮晶晶" }),
            "musicxml" to (InteractiveCodeRenderer.MUSICXML to longXml),
            "mei-mensural" to (InteractiveCodeRenderer.MEI to MEI_MENSURAL_EXAMPLE),
            "mei-neume" to (InteractiveCodeRenderer.MEI to MEI_NEUME_EXAMPLE),
        )
        for(dark in listOf(false,true))for((id,sample)in samples){
            val colors=if(dark)darkColorScheme()else lightColorScheme()
            val html=buildInteractiveRendererHtml(sample.first,sample.second,colors)
            onMain{view=null;activity!!.setContent{key(html){MaterialTheme(colorScheme=colors){Surface{InteractiveCodeBlock(sample.first,sample.second,onViewCreated={view=it})}}}}}
            ready()
            val count=js("window.rikkaScoreInfo().pages").toInt()
            assertTrue(count in 1..64)
            if(!id.startsWith("mei"))assertTrue("Long score should paginate",count>1)
            if(count>1){
                js("document.querySelector('.score-pager button:last-child').click()")
                assertEquals("2",js("window.__rikkaScore.page"))
            }
            capture("$id-${if(dark)"dark"else"light"}")
            val file=File(artifacts,"$id-${if(dark)"dark"else"light"}.pdf")
            assertEquals(count,runBlocking{exportScorePdf(requireNotNull(view),file)})
            if(count>1)assertEquals("2",js("window.__rikkaScore.page"))
            PdfRenderer(ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY)).use{pdf ->
                assertEquals(count,pdf.pageCount)
                for(index in 0 until count)pdf.openPage(index).use{page ->
                    val bitmap=Bitmap.createBitmap(595,842,Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(AndroidColor.WHITE);page.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    var ink=0
                    for(y in 20 until 820 step 2)for(x in 20 until 575 step 2){val c=bitmap.getPixel(x,y);if(AndroidColor.red(c)<150&&AndroidColor.green(c)<150&&AndroidColor.blue(c)<150)ink++}
                    assertTrue("$id page ${index+1} must contain ink",ink>20)
                    if(index==0||index==count-1)File(artifacts,"$id-${if(dark)"dark"else"light"}-pdf-${index+1}.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
                    bitmap.recycle()
                }
            }
            if(id=="mei-neume")assertEquals("true",js("document.querySelector('.score-notice').textContent.includes('歌词')"))
            results.put(JSONObject().put("renderer",id).put("dark",dark).put("pages",count).put("pdfBytes",file.length()).put("ready",true))
        }
        File(artifacts,"results.json").writeText(results.toString(2))
    }

    @Test fun XMLAndMXLAttachmentsStayReadableInModelContextOnAndroid() {
        launch()
        val source=RendererCatalog.find("musicxml")!!.example
        val file=File(artifacts,"sample.mxl")
        ZipOutputStream(file.outputStream()).use{zip ->
            for((name,text) in listOf("META-INF/container.xml" to "<container><rootfiles><rootfile full-path='main.xml' media-type='application/vnd.recordare.musicxml+xml'/></rootfiles></container>","main.xml" to source)){
                zip.putNextEntry(ZipEntry(name));zip.write(text.toByteArray());zip.closeEntry()
            }
        }
        assertEquals(source,ScoreDocuments.read(file,"sample.mxl")!!.source)
        assertEquals(MEI_NEUME_EXAMPLE,ScoreDocuments.parseXml(MEI_NEUME_EXAMPLE,"mei").source)
        val document=UIMessagePart.Document(file.toURI().toString(),"sample.mxl","application/vnd.recordare.musicxml")
        val result=runBlocking{DocumentAsPromptTransformer.transformDocumentContents(listOf(UIMessage(role=MessageRole.USER,parts=listOf(document))))}.single()
        assertEquals(document,result.parts.filterIsInstance<UIMessagePart.Document>().single())
        assertTrue(result.toText().contains("```musicxml"));assertTrue(result.toText().contains("score-partwise"));assertFalse(result.toText().contains("[ERROR"))
        File(artifacts,"attachment-result.txt").writeText("MXL expanded safely; original document retained; provider prompt contains MusicXML; Android XML parser accepts MEI.")
    }
}
