package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.ai.tools.createRenderingTools
import me.rerere.rikkahub.data.ai.tools.parseRenderRequest
import me.rerere.rikkahub.data.render.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class MusicScoreRenderingTest {
    @Test fun `catalog tools advertise scores and enforce MEI disabled policy`() = runBlocking {
        val request=buildJsonObject { put("renderer","mei");put("source",MEI_MENSURAL_EXAMPLE) }
        assertEquals("mei",parseRenderRequest(request).renderer.id)
        val result=createRenderingTools(meiEnabled=false).first { it.name=="render_content" }.execute(request)
        assertTrue(result.toString().contains("renderer_disabled"))
        val list=createRenderingTools(meiEnabled=false).first { it.name=="list_renderers" }.execute(buildJsonObject{put("renderer","mei")})
        assertTrue(list.toString().contains("disabled_by_user"))
    }
    @Test fun `MEI auto detection is limited to an explicit namespace and closed document`() {
        assertEquals("mei",resolveDiagramLanguage("xml",MEI_MENSURAL_EXAMPLE))
        assertEquals("xml",resolveDiagramLanguage("xml",MEI_MENSURAL_EXAMPLE.replace("http://www.music-encoding.org/ns/mei","https://example.com")))
        assertEquals("xml",resolveDiagramLanguage("xml",MEI_MENSURAL_EXAMPLE.removeSuffix("</mei>")))
    }
    @Test fun `production score HTML uses a common export and pagination contract`() {
        val dir=File("build/reports/music-renderers").apply { mkdirs() }
        val note="<note><pitch><step>C</step><octave>4</octave></pitch><duration>1</duration><type>quarter</type></note>"
        fun musicXml(count:Int)= "<score-partwise version='4.0'><work><work-title>Pagination and harmony</work-title></work><part-list><score-part id='P1'><part-name>Piano</part-name></score-part></part-list><part id='P1'>" +
            (1..count).joinToString("") { n -> "<measure number='$n'>" + (if(n==1)"<attributes><divisions>1</divisions><time><beats>4</beats><beat-type>4</beat-type></time><clef><sign>G</sign><line>2</line></clef></attributes>" else "") +
                (if(n%2==1)"<harmony><root><root-step>C</root-step></root><kind>major</kind></harmony>" else "<harmony><numeral><numeral-root text='V'>5</numeral-root></numeral><kind>dominant</kind><offset>1</offset></harmony>") + note.repeat(4) + "</measure>" } + "</part></score-partwise>"
        val fixtures=mapOf(
            "abc" to (InteractiveCodeRenderer.ABC to "X:1\nT:ABC Harmony\nM:4/4\nL:1/4\nK:C\n"+(1..24).joinToString("\n"){ "\"C\"CDEF|\"G7\"GABc|" }),
            "jianpu" to (InteractiveCodeRenderer.JIANPU to "title: 和声与分页\n1=C\n"+(1..16).joinToString("\n"){ "1 2 3 4 | 5 6 7 1' |\nH: I | V7\nL: 一 闪 一 闪 亮 晶 晶" }),
            "musicxml" to (InteractiveCodeRenderer.MUSICXML to musicXml(48)),
            "mei-mensural" to (InteractiveCodeRenderer.MEI to MEI_MENSURAL_EXAMPLE),
            "mei-neume" to (InteractiveCodeRenderer.MEI to MEI_NEUME_EXAMPLE),
            "mei-long" to (InteractiveCodeRenderer.MEI to MEI_MENSURAL_EXAMPLE.replace(Regex("<layer n=\"1\">.*?</layer>"),"<layer n=\"1\">" + "<note pname=\"c\" oct=\"4\" dur=\"minima\"/>".repeat(200) + "</layer>")),
            "mei-modern" to (InteractiveCodeRenderer.MEI to """<mei xmlns="http://www.music-encoding.org/ns/mei" meiversion="5.0"><music><body><mdiv><score><scoreDef meter.count="4" meter.unit="4"><staffGrp><staffDef n="1" lines="5" clef.shape="G" clef.line="2"/></staffGrp></scoreDef><section><measure n="1"><staff n="1"><layer n="1"><note xml:id="n1" pname="c" oct="4" dur="1"/></layer></staff><harm startid="#n1">I</harm></measure></section></score></mdiv></body></music></mei>"""),
        )
        for(dark in listOf(false,true)) for((name,pair) in fixtures){
            val html=buildInteractiveRendererHtml(pair.first,pair.second,if(dark)darkColorScheme() else lightColorScheme())
            assertTrue(html.contains("score-pages.js"));assertTrue(me.rerere.rikkahub.ui.pages.webview.isMusicScorePreview(html))
            File(dir,"$name-${if(dark) "dark" else "light"}.html").writeText(html)
        }
        File(dir,"invalid-abc.html").writeText(buildInteractiveRendererHtml(InteractiveCodeRenderer.ABC,"X:1\nK:C\n"+"C ".repeat(8001),lightColorScheme()))
        File(dir,"invalid-xml.html").writeText(buildInteractiveRendererHtml(InteractiveCodeRenderer.MUSICXML,"<!DOCTYPE score-partwise [<!ENTITY x 'oops'>]>"+musicXml(1),lightColorScheme()))
        File(dir,"invalid-jianpu.html").writeText(buildInteractiveRendererHtml(InteractiveCodeRenderer.JIANPU,"1 2 3 | 4 5 6 |\nH: C",lightColorScheme()))
        File(dir,"musicxml-resize.html").writeText(me.rerere.rikkahub.ui.pages.webview.prepareFullscreenPreviewHtml(buildInteractiveRendererHtml(InteractiveCodeRenderer.MUSICXML,musicXml(24),lightColorScheme())))
        File(dir,"musicxml-harmony.xml").writeText(musicXml(48))
    }
}
