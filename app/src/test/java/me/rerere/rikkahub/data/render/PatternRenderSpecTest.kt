package me.rerere.rikkahub.data.render

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PatternRenderSpecTest {
    private fun compile(source: String) = compileDeclarativeRenderSpec("pattern", source)

    @Test fun `catalog aliases use a bounded decorative data contract rather than p5 scripts`() {
        val spec = RendererCatalog.find("pattern")!!
        spec.aliases.forEach { assertEquals("pattern", RendererCatalog.find(it)!!.id) }
        assertNull(RendererCatalog.find("p5"))
        val result = compile(spec.example)
        assertEquals("pattern", result["kind"]!!.jsonPrimitive.content)
        assertFalse(result["animate"]!!.jsonPrimitive.boolean)
        assertEquals(24000, result["vertexBudget"]!!.jsonPrimitive.int)
    }

    @Test fun `rose ratios normalize while phase remains degrees`() {
        val layer = compile("""{"layers":[{"type":"rose","numerator":12,"denominator":8,"phase":90}]}""")["layers"]!!.jsonArray.single().jsonObject
        assertEquals(3, layer["numerator"]!!.jsonPrimitive.int)
        assertEquals(2, layer["denominator"]!!.jsonPrimitive.int)
        assertEquals(90.0, layer["phase"]!!.jsonPrimitive.double, 0.0)
    }

    @Test fun `radial layers are identical to sigil geometry with the same defaults`() {
        for (type in listOf("ring", "star", "polygon", "ticks", "rays")) {
            val source = """{"layers":[{"type":"$type","color":"#123456"}]}"""
            assertEquals(compileDeclarativeRenderSpec("sigil", source)["layers"], compile(source)["layers"])
        }
    }

    @Test fun `fractal variants include base cases without unbounded recursion`() {
        for (variant in listOf("koch", "sierpinski", "tree")) {
            for (depth in listOf(0, if (variant == "koch") 5 else 7)) {
                assertEquals(1, compile("""{"layers":[{"type":"fractal","variant":"$variant","depth":$depth}]}""")["layers"]!!.jsonArray.size)
            }
        }
    }

    @Test fun `unknown fields scripts assets and non decorative presets are rejected`() {
        listOf(
            """{"layers":[{"type":"mandala"}],"setup":"alert(1)"}""",
            """{"layers":[{"type":"rose","expression":"sin(x)"}]}""",
            """{"layers":[{"type":"fractal","variant":"user","rules":"F+F"}]}""",
            """{"layers":[{"type":"physics"}]}""",
            """{"layers":[{"type":"mandala","texture":"https://evil.test"}]}""",
            """{"layers":[{"type":"text_ring","text":"hello"}]}""",
            """{"layers":[{"type":"rose","color":"url(x)"}]}""",
            """{"layers":[{"type":"rose"}],"palette":["<script>"]}""",
        ).forEach { assertTrue(it, runCatching { compile(it) }.isFailure) }
    }

    @Test fun `strict types ranges and total geometry budget reject expensive inputs`() {
        listOf(
            """{"layers":[]}""", """{"layers":[{"type":"mandala","petals":33}]}""",
            """{"layers":[{"type":"mandala","rings":1.5}]}""",
            """{"layers":[{"type":"rose","denominator":0}]}""",
            """{"layers":[{"type":"rose","radius":"90"}]}""",
            """{"layers":[{"type":"rose","radius":1e999}]}""",
            """{"layers":[{"type":"fractal","variant":"koch","depth":6}]}""",
            """{"layers":[{"type":"fractal","variant":"tree","depth":8}]}""",
            """{"layers":[{"type":"fractal","angle":20}]}""",
            """{"layers":[{"type":"rose"}],"palette":[]}""",
            """{"layers":[{"type":"rose"}],"seed":2147483648}""",
            """{"layers":[{"type":"rose"}],"animate":"true"}""",
            "{\"layers\":[" + List(13) { "{\"type\":\"ring\"}" }.joinToString() + "]}",
            "{\"layers\":[" + List(3) { "{\"type\":\"mandala\",\"petals\":32,\"rings\":6}" }.joinToString() + "]}",
        ).forEach { assertTrue(it, runCatching { compile(it) }.isFailure) }
    }

    @Test fun `explicit color overrides mandala palette while seed is reproducible`() {
        val source = """{"seed":123,"palette":["#123456","#abcdef"],"layers":[{"type":"mandala","color":"#fff"}]}"""
        val result = compile(source)
        assertEquals(result, compile(source))
        assertEquals(JsonArray(listOf(JsonPrimitive("#fff"))), result["layers"]!!.jsonArray.single().jsonObject["palette"])
    }

    @Test fun `pattern supports bounded free paths symmetry and advanced choreography`() {
        val result = compile("""{
          "name":"星门","colors":["#ff2bd6","#24d8ff","#ffe08a"],
          "glow":{"amount":1.2,"spread":1.8},"animation":{"mode":"orbit","duration":4},
          "layers":[{"type":"freeform","points":[[-30,0],[-10,-22],[20,-15],[35,0],[20,15],[-10,22]],"closed":true,"fill":"#211435","symmetry":3,"mirror":true,"animation":{"mode":"draw","loop":false}}]
        }""")
        val layer = result["layers"]!!.jsonArray.single().jsonObject
        assertEquals("path", layer["type"]!!.jsonPrimitive.content)
        assertEquals(3, layer["symmetry"]!!.jsonPrimitive.int)
        assertTrue(layer["mirror"]!!.jsonPrimitive.boolean)
        assertEquals("draw", layer["animation"]!!.jsonObject["mode"]!!.jsonPrimitive.content)
        assertEquals(3, result["palette"]!!.jsonArray.size)
    }

    @Test fun `pattern accepts common background and line color aliases`() {
        val result = compile("""{"backgroundColor":"#07111f","lineColor":"#29d3ff","layers":[{"type":"ring","lineColor":"#ffd166"}]}""")
        assertEquals("#07111f", result["background"]!!.jsonPrimitive.content)
        assertEquals("#29d3ff", result["palette"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals("#ffd166", result["layers"]!!.jsonArray.single().jsonObject["color"]!!.jsonPrimitive.content)
    }
}
