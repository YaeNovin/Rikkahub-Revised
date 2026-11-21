package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import me.rerere.rikkahub.data.render.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlinx.serialization.json.*

class LinguisticRendererTest {
    @Test fun generateProductionHtmlForRealBrowserChecks() {
        val directory=File("build/reports/linguistic-renderers").apply { mkdirs() }
        // Remove only this suite's stale HTML fixtures when a format id changes.
        directory.listFiles()?.filter { it.extension == "html" }?.forEach { it.delete() }
        linguisticRendererSpecs.forEach { spec ->
            listOf("light" to lightColorScheme(),"dark" to darkColorScheme()).forEach { (theme,colors) ->
                val html=buildLinguisticRendererHtml(spec.id,spec.example,colors,"Invalid")
                assertTrue(html.contains("linguistic-v1"));assertTrue(html.contains("DoulosSIL-Regular.woff2"))
                directory.resolve("${spec.id}-$theme.html").writeText(html)
                directory.resolve("${spec.id}-$theme-fullscreen.html").writeText(
                    me.rerere.rikkahub.ui.pages.webview.prepareFullscreenPreviewHtml(html,true))
            }
        }
        val unsafe="""{"version":1,"segments":[{"base":"</script><img src=x onerror=alert(1)>","reading":"テスト"}]}"""
        val html=buildLinguisticRendererHtml("ruby_annotation",unsafe,lightColorScheme(),"Invalid")
        assertFalse(html.contains("<img"));directory.resolve("unsafe.html").writeText(html)
        directory.resolve("invalid.html").writeText(buildLinguisticRendererHtml("dependency","not conllu",darkColorScheme(),"Invalid"))
        directory.resolve("export-probe.js").writeText(me.rerere.rikkahub.ui.components.webview.WEBVIEW_EXPORT_PROBE)
        fun fixture(id:String,name:String,source:String) {
            directory.resolve("$name.html").writeText(buildLinguisticRendererHtml(id,source,darkColorScheme(),"Invalid"))
        }
        fixture("ipa","ipa-marks","""{"version":1,"text":"ãː t͡ʃ ɹ̠ˤ ˥˩ ʰ n̩ é\n/ˈlɪŋ.ɡwɪs.tɪks/","notation":"plain"}""")
        fixture("gloss","gloss-rtl","""{"version":1,"dir":"rtl","language":"ar","words":[{"text":"البيت","morphemes":"ال-بيت","gloss":"DEF-house"},{"text":"كبير","morphemes":"كبير","gloss":"big"}],"translation":"The house is big."}""")
        val longGloss=buildJsonObject {
            put("version",1);put("translation","END OF GLOSS");put("words",buildJsonArray {
                repeat(48) { add(buildJsonObject { put("text","linguistics$it");put("gloss","language.science$it") }) }
            })
        }
        fixture("gloss","gloss-long",longGloss.toString())
        val wide=buildJsonObject {
            put("version",1);put("title","Wide tree");put("root",buildJsonObject {
                put("label","S");put("children",buildJsonArray {
                    repeat(8) { group -> add(buildJsonObject { put("label","NP$group");put("children",buildJsonArray {
                        repeat(8) { leaf -> add(buildJsonObject { put("label","long-leaf-$group-$leaf") }) }
                    }) }) }
                })
            })
        }
        fixture("syntax_tree","tree-wide",wide.toString())
        fixture("dependency","dependency-enhanced","# sent_id = enhanced\n1-2\tcan't\t_\t_\t_\t_\t_\t_\t_\t_\n1\tca\tcan\tAUX\t_\t_\t3\taux\t3:aux\t_\n2\tn't\tnot\tPART\t_\t_\t3\tadvmod\t3:advmod\t_\n3\tgo\tgo\tVERB\t_\t_\t0\troot\t0:root\t_\n3.1\tthere\tthere\tADV\t_\t_\t_\t_\t3:advmod\tSpaceAfter=No\n")
        fixture("dependency","dependency-labels","1\ta\t_\tPRON\t_\t_\t2\tnsubj:extremely_long_subtype_label_for_a_small_sentence\t_\t_\n2\tb\t_\tVERB\t_\t_\t0\troot\t_\t_\n")
        linguisticRendererSpecs.forEach { spec ->
            directory.resolve("${spec.id}-large-font.html").writeText(buildLinguisticRendererHtml(spec.id,spec.example,darkColorScheme(),"Invalid",2f))
        }
    }
}
