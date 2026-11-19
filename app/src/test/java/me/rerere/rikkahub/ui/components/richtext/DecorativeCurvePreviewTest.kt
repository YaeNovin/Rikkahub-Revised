package me.rerere.rikkahub.ui.components.richtext

import java.io.File
import java.util.Base64
import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.render.compileDeclarativeRenderSpec
import me.rerere.rikkahub.ui.components.webview.WEBVIEW_EXPORT_PROBE
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class DecorativeCurvePreviewTest {
    @Test fun `shared formulas use the existing strict offline preview and generate browser fixtures`() {
        val directory = File("build/reports/decorative-curves").apply { mkdirs() }
        val examples = linkedMapOf(
            "hypotrochoid" to """{"type":"curve","formula":"hypotrochoid","fixed":7,"rolling":3,"pen":0.8}""",
            "epitrochoid" to """{"type":"curve","formula":"epitrochoid","fixed":5,"rolling":2,"pen":0.65}""",
            "lissajous" to """{"type":"curve","formula":"lissajous","frequencyX":5,"frequencyY":4,"phase":90}""",
            "harmonic" to """{"type":"curve","formula":"harmonic","terms":[{"frequency":6,"amplitude":0.3},{"frequency":12,"amplitude":0.12,"phase":30}]}""",
        )
        for (id in listOf("sigil", "pattern")) {
            for ((name, layer) in examples) {
                val source = """{"title":"$name · shared geometry","color":"#67e8f9","glow":0,"layers":[$layer]}"""
                val html = buildGeometryPreviewHtml(id, source, true)
                val doc = Jsoup.parse(html)
                val data = Json.parseToJsonElement(String(Base64.getDecoder().decode(doc.body().attr("data-source")))).jsonObject
                assertFalse("error" in data)
                assertEquals(compileDeclarativeRenderSpec(id, source), data)
                assertTrue(doc.select("script:not([src]),img,iframe").isEmpty())
                assertFalse(doc.selectFirst("meta[http-equiv]")!!.attr("content").contains("unsafe-eval"))
                assertTrue(doc.select("script[src]").all { it.attr("src").startsWith("https://rikkahub.local/assets/html/renderers/") })
                assertEquals(id == "pattern", doc.select("script[src]").any { it.attr("src").contains("p5-1.") })
                directory.resolve("$id-$name.html").writeText(html)
                directory.resolve("$id-$name.json").writeText(data.toString())
            }
            val animated = """{"animate":true,"layers":[{"type":"curve","formula":"hypotrochoid","symmetry":3,"mirror":true,"speed":12,"animation":{"mode":"pulse","amplitude":0.08}}]}"""
            directory.resolve("$id-animated.html").writeText(buildGeometryPreviewHtml(id, animated, true))
            directory.resolve("$id-fullscreen.html").writeText(prepareGeometryFullscreenHtml(buildGeometryPreviewHtml(id, """{"layers":[${examples.getValue("harmonic")}]}""", true), true))
        }
        directory.resolve("export-probe.js").writeText(WEBVIEW_EXPORT_PROBE)
    }
}
