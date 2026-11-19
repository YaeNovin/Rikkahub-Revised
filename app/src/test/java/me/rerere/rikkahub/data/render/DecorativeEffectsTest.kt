package me.rerere.rikkahub.data.render

import kotlinx.serialization.json.*
import me.rerere.rikkahub.ui.components.richtext.buildGeometryPreviewHtml
import me.rerere.rikkahub.ui.components.webview.previewLayerType
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class DecorativeEffectsTest {
    @Test fun `effects are shared bounded opt in and remain static by default`() {
        for (id in listOf("sigil", "pattern")) {
            val result = compileDeclarativeRenderSpec(id, """{"effects":{"stardust":{},"trail":{},"reveal":{}},"layers":[{"type":"ring"}]}""")
            assertFalse(result["animate"]!!.jsonPrimitive.boolean)
            assertEquals(48, result["effects"]!!.jsonObject["stardust"]!!.jsonObject["count"]!!.jsonPrimitive.int)
            assertEquals(5, result["effects"]!!.jsonObject["trail"]!!.jsonObject["samples"]!!.jsonPrimitive.int)
        }
        assertEquals(0, compileDeclarativeRenderSpec("sigil", """{"layers":[{"type":"ring"}]}""")["effects"]!!.jsonObject.size)
    }
    @Test fun `unsafe or unbounded effects are refused before rendering`() {
        for (effect in listOf(
            """{"trail":{}}""", """{"stardust":{"count":129}}""", """{"stardust":{"count":"50"}}""",
            """{"stardust":{},"trail":{"samples":9}}""", """{"reveal":{"duration":0}}""",
            """{"stardust":{"texture":"https://evil.test"}}""", """{"stardust":{"color":"url(x)"}}""",
            """{"stardust":{"seed":-1}}""", """{"fps":240}""", """{"hardwareAccelerated":true}""", "null")) {
            for (id in listOf("sigil", "pattern")) assertTrue(effect, runCatching {
                compileDeclarativeRenderSpec(id, """{"layers":[{"type":"ring"}],"effects":$effect}""")
            }.isFailure)
        }
    }
    @Test fun `screen rendering inherits hardware while bitmap export stays software`() {
        assertEquals(0, previewLayerType(false))
        assertEquals(1, previewLayerType(true))
        assertTrue(File("src/main/AndroidManifest.xml").readText().contains("android:hardwareAccelerated=\"true\""))
    }
    @Test fun `generate shared effects and performance fixtures`() {
        val dir = File("build/reports/decorative-effects").apply { mkdirs() }
        val effects = """"effects":{"stardust":{"count":96,"speed":25},"trail":{"samples":6},"reveal":{"duration":0.35,"stagger":0.12}}"""
        val cases = mapOf(
            "sigil" to """{"animate":true,"glow":0.4,$effects,"layers":[{"type":"ring","radius":94},{"type":"ticks","radius":86,"count":60,"speed":-8},{"type":"star","points":6,"step":2,"radius":68,"speed":18},{"type":"ring","radius":34}]}""",
            "pattern" to """{"animate":true,$effects,"layers":[{"type":"mandala","rings":4,"petals":16,"speed":12},{"type":"rose","radius":36,"speed":-20}]}""",
            "reveal-only" to """{"animate":true,"effects":{"reveal":{"duration":0.2,"stagger":0.1}},"layers":[{"type":"ring"},{"type":"star","points":6,"step":2}]}""",
        )
        cases.forEach { (name, json) -> dir.resolve("$name.html").writeText(buildGeometryPreviewHtml(if (name == "pattern") name else "sigil", json, true)) }
        dir.resolve("static.html").writeText(buildGeometryPreviewHtml("sigil", cases.getValue("sigil").replace("\"animate\":true", "\"animate\":false"), true))
    }
}
