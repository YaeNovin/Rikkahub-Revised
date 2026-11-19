package me.rerere.rikkahub.data.render

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class DeclarativeRenderSpecTest {
    @Test fun `off grid values remain exact while valid step grids stay discrete`() {
        val data = compileDeclarativeRenderSpec("math_plot", """{
          "parameters":[{"name":"a","label":"振幅","min":0,"max":3,"value":1,"step":0.03},
            {"name":"b","min":-1,"max":1,"value":0.2,"step":0.1}],
          "curves":[{"expression":"a*sin(x)+b"}]}
        """)
        val params = data["parameters"]!!.jsonArray
        assertEquals(1.0, params[0].jsonObject["value"]!!.jsonPrimitive.double, 0.0)
        assertEquals("any", params[0].jsonObject["step"]!!.jsonPrimitive.content)
        assertTrue(params[0].jsonObject["continuousFallback"]!!.jsonPrimitive.boolean)
        assertEquals(0.1, params[1].jsonObject["step"]!!.jsonPrimitive.double, 0.0)
        assertEquals("振幅", params[0].jsonObject["label"]!!.jsonPrimitive.content)
        listOf("0", "-1", "4", "\"0.1\"").forEach { step ->
            assertTrue(runCatching { compileDeclarativeRenderSpec("math_plot",
                """{"parameters":[{"name":"a","min":0,"max":3,"step":$step}],"curves":[{"expression":"a*x"}]}""") }.isFailure)
        }
    }
    @Test fun `all primitive labels are bounded inert text`() {
        val data = compileDeclarativeRenderSpec("math_plot", """{"primitives":[
          {"type":"vector","from":[0,0],"to":[1,1],"label":"<img onerror=evil>"},
          {"type":"segment","from":[0,0],"to":[2,0],"label":"底边"},
          {"type":"circle","center":[0,0],"radius":1,"label":"圆"}]}""")
        assertEquals(3, data["primitives"]!!.jsonArray.count { it.jsonObject["label"]!!.jsonPrimitive.content.isNotBlank() })
        assertTrue(runCatching { compileDeclarativeRenderSpec("math_plot",
            """{"primitives":[{"type":"vector","from":[0,0],"to":[1,1],"label":"${"x".repeat(33)}"}]}""") }.isFailure)
    }
    private fun expression(source: String) = MathExpression(source, setOf("x", "a")).parse()
    @Test fun `mathematical precedence right associative powers and numeric constants are preserved`() {
        assertEquals(Json.parseToJsonElement("""["neg",["bin","^",["num",2.0],["num",2.0]]]"""), expression("-2^2"))
        assertEquals("bin", expression("2^3^2")[0].jsonPrimitive.content)
        assertEquals("bin", expression("2^3^2")[3].jsonArray[0].jsonPrimitive.content)
        assertEquals("neg", expression("2^-2")[3].jsonArray[0].jsonPrimitive.content)
        assertEquals(0.001, expression("1e-3")[1].jsonPrimitive.double, 0.0)
        assertEquals(kotlin.math.PI, expression("PI")[1].jsonPrimitive.double, 0.0)
        assertEquals("call", expression("max(sin(x), a)")[0].jsonPrimitive.content)
    }
    @Test fun `scripts member access arbitrary functions unknown variables and oversized ASTs are rejected`() {
        listOf("window.alert(1)", "x.constructor", "import('x')", "sin.constructor(1)", "x=2", "[1,2]", "2x", "sin()", "sin(1,2)", "pow(2)", "fetch(1)", "y+1", "1e309", "('hello')", "(".repeat(40) + "x" + ")".repeat(40), "1+".repeat(130) + "1")
            .forEach { assertTrue(it, runCatching { expression(it) }.isFailure) }
    }
    @Test fun `both catalog examples compile to bounded declarative payloads`() {
        for (id in listOf("math_plot", "sigil")) {
            val result = compileDeclarativeRenderSpec(id, RendererCatalog.find(id)!!.example)
            assertEquals(id, result["kind"]!!.jsonPrimitive.content)
        }
        assertEquals("math_plot", RendererCatalog.find("math_plan")!!.id)
        assertEquals("sigil", RendererCatalog.find("magic_circle")!!.id)
        val parameters = compileDeclarativeRenderSpec("math_plot", RendererCatalog.find("math_plot")!!.example)["parameters"]!!.jsonArray
        assertEquals("any", parameters.single().jsonObject["step"]!!.jsonPrimitive.content)
        assertEquals(1.0, parameters.single().jsonObject["value"]!!.jsonPrimitive.double, 0.0)
    }
    @Test fun `math permits typed curves and primitives without accepting raw board options`() {
        val result = compileDeclarativeRenderSpec("math_plot", """{
          "curves":[{"expression":"1/x"},{"type":"parametric","x":"cos(t)","y":"sin(t)"},{"type":"polar","r":"2*cos(3*theta)"}],
          "primitives":[{"type":"point","at":[1,2],"label":"<img>"},{"type":"circle","center":[0,0],"radius":2},{"type":"vector","from":[0,0],"to":[1,2]}]
        }""")
        assertEquals(3, result["curves"]!!.jsonArray.size)
        assertTrue(result["curves"]!!.jsonArray[0].jsonObject["expression"] is JsonArray)
        val invalid = listOf("""{"curves":[{"expression":"x"}],"javascript":"alert(1)"}""",
            """{"curves":[{"expression":"x","onClick":"evil"}]}""", """{"curves":[{"expression":"x"}],"xRange":[2,1]}""",
            """{"curves":[{"expression":"x"}],"grid":"true"}""", """{"parameters":[{"name":"x"}],"curves":[{"expression":"x"}]}""",
            """{"curves":[{"expression":"x","color":"url(https://evil.test)"}]}""", """{"curves":[]}""")
        invalid.forEach { assertTrue(it, runCatching { compileDeclarativeRenderSpec("math_plot", it) }.isFailure) }
    }
    @Test fun `sigils normalize hexagram topology and reject unsafe or excessive scene data`() {
        val spec = compileDeclarativeRenderSpec("sigil", """{"layers":[{"type":"star","points":6,"step":2}]}""")
        assertEquals(2, spec["layers"]!!.jsonArray[0].jsonObject["step"]!!.jsonPrimitive.int)
        assertFalse(spec["animate"]!!.jsonPrimitive.boolean)
        listOf("""{"layers":[{"type":"star","points":6,"step":0}]}""", """{"layers":[{"type":"path","d":"M0 0"}]}""",
            """{"layers":[{"type":"ring","speed":999}]}""", """{"layers":[{"type":"ring","radius":"90"}]}""",
            """{"layers":[{"type":"text_ring","text":""}]}""", """{"layers":[{"type":"ticks","count":129}]}""")
            .forEach { assertTrue(it, runCatching { compileDeclarativeRenderSpec("sigil", it) }.isFailure) }
        assertTrue(runCatching { compileDeclarativeRenderSpec("sigil", "{\"layers\":[" + List(24) { "{\"type\":\"ticks\",\"count\":128}" }.joinToString() + "]}") }.isFailure)
        assertTrue(runCatching { compileDeclarativeRenderSpec("sigil", " ".repeat(32769)) }.isFailure)
        assertTrue(runCatching { compileDeclarativeRenderSpec("sigil", "[".repeat(40) + "0" + "]".repeat(40)) }.isFailure)
    }

    @Test fun `sigil accepts common model metadata palettes advanced glow and choreography`() {
        val result = compileDeclarativeRenderSpec("sigil", """{
          "name":"七重守护阵","description":"decorative only","style":"occult",
          "colors":["#ff4fd8","#29d3ff","#ffd166"],
          "glow":{"amount":1.4,"color":"#29d3ff","spread":2.2,"pulse":true},
          "animation":{"mode":"sequence","duration":2,"stagger":0.1},
          "layers":[
            {"type":"ring","radius":94,"colors":["#ff4fd8","#29d3ff"],"symmetry":2,"animation":{"mode":"pulse","amplitude":0.2}},
            {"type":"path","points":[[0,-40],[12,-8],[40,0],[12,8],[0,40],[-12,8],[-40,0],[-12,-8]],"closed":true,"fill":"#19142f","label":"封印纹"}
          ]
        }""")
        assertEquals("七重守护阵", result["title"]!!.jsonPrimitive.content)
        assertEquals(3, result["palette"]!!.jsonArray.size)
        assertEquals(1.4, result["glow"]!!.jsonPrimitive.double, 0.0)
        assertEquals("path", result["layers"]!!.jsonArray[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(2, result["layers"]!!.jsonArray[0].jsonObject["symmetry"]!!.jsonPrimitive.int)
    }

    @Test fun `sigil normalizes circle hexagram aliases and preserves mirrored paths`() {
        val result = compileDeclarativeRenderSpec("sigil", """{
          "background":"#07111f","backgroundColor":"#101426","lineColor":"#29d3ff",
          "layers":[
            {"type":"circle","radius":90,"background":"#101820"},
            {"type":"hexagram","radius":60,"symmetry":2,"mirror":true},
            {"type":"freeform","points":[[-10,0],[0,-20],[10,0]],"closed":true,"mirror":true}
          ]
        }""")
        assertEquals("#07111f", result["background"]!!.jsonPrimitive.content)
        assertEquals("#101820", result["layers"]!!.jsonArray[0].jsonObject["background"]!!.jsonPrimitive.content)
        assertEquals("#29d3ff", result["palette"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals("ring", result["layers"]!!.jsonArray[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("star", result["layers"]!!.jsonArray[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertTrue(result["layers"]!!.jsonArray[1].jsonObject["mirror"]!!.jsonPrimitive.boolean)
    }
}
