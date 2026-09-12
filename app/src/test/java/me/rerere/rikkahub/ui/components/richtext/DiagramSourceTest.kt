package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import kotlinx.serialization.json.*
import me.rerere.rikkahub.ui.pages.webview.prepareFullscreenPreviewHtml
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class DiagramSourceTest {
    @Test fun `Stack keeps multi row sequencing and rejects empty or executable arguments`() {
        val normalized = normalizeRailroadSource("Diagram(Stack(Terminal('A'), Sequence(Terminal('B'), NonTerminal('C')))).addTo();")
        val stack = Json.parseToJsonElement(normalized).jsonObject["items"]!!.jsonArray.single().jsonObject
        assertEquals("stack", stack["type"]!!.jsonPrimitive.content)
        assertEquals(2, stack["items"]!!.jsonArray.size)
        assertEquals("railroad", resolveDiagramLanguage("json", stack.toString()))
        assertEquals("railroad", resolveDiagramLanguage("javascript", "Stack('A', 'B')"))
        assertEquals("stack", Json.parseToJsonElement(normalizeRailroadSource("Stack('A', 'B')")).jsonObject["type"]!!.jsonPrimitive.content)
        assertTrue(normalizeRailroadSource("Diagram(Stack())").contains("rikkaRenderError"))
        assertTrue(normalizeRailroadSource("Diagram(Stack(fetch('https://example.com')))").contains("rikkaRenderError"))
    }
    @Test fun `GeoJSON variants survive Markdown extraction and select the same renderer`() {
        val point = """{"type":"Point","coordinates":[120,30]}"""
        listOf("json", "JSON", "jsonc", "json5", "javascript", "application/json", "application/geo+json").forEach { language ->
            listOf("", "<div>Heading</div>\n\n").forEach { prefix ->
                val doc = buildMarkdownDocument(prefix + "```$language\n$point\n```\n")
                val code = doc.selectFirst("pre > code")!!
                val declared = code.classNames().first { it.startsWith("language-") }.removePrefix("language-")
                assertEquals(language, "geojson", resolveDiagramLanguage(declared, code.wholeText()))
                assertFalse(doc.selectFirst("pre")!!.attr("data-rikka-complete") == "false")
            }
        }
    }
    @Test fun `recognition and rendering normalize JSON comments trailing commas and feature arrays together`() {
        val samples = mapOf(
            "comments" to "// map\n{\"type\":\"Point\",\"coordinates\":[120,30,],}",
            "literal" to "{type:'Point',coordinates:[120,30]}",
            "array" to """[{"type":"Point","coordinates":[120,30]},{"type":"Feature","properties":{},"geometry":{"type":"Point","coordinates":[121,31]}}]""",
            "route" to """{"polylines":[{"points":[[30,120],[31,121]]}]}""",
        )
        val output = File("build/reports/webview-renderers/maps").apply { mkdirs() }
        samples.forEach { (name, source) ->
            assertEquals(name, "geojson", resolveDiagramLanguage("json", source))
            val normalized = Json.parseToJsonElement(normalizeDiagramData(source, InteractiveCodeRenderer.LEAFLET))
            assertTrue(normalized is JsonObject)
            output.resolve("inferred-$name.html").writeText(buildInteractiveRendererHtml(InteractiveCodeRenderer.LEAFLET, source, darkColorScheme()))
        }
    }
    @Test fun `JSON diagrams without a closing fence only render after streaming finishes`() {
        val point = """{"type":"Point","coordinates":[120,30]}"""
        assertFalse(canRenderFinishedJsonFence("json", point, streaming = true))
        assertTrue(canRenderFinishedJsonFence("json", point, streaming = false))
        assertFalse(canRenderFinishedJsonFence("json", point.dropLast(1), streaming = false))
        assertFalse(canRenderFinishedJsonFence("python", point, streaming = false))
        assertFalse(canRenderFinishedJsonFence("html", "<script>alert(1)</script>", streaming = false))
        assertFalse(canRenderFinishedJsonFence("json", "{\"temperature\":0.5}", streaming = false))
        val doc = buildMarkdownDocument("```json\n$point")
        assertEquals("false", doc.selectFirst("pre")!!.attr("data-rikka-complete"))
        assertTrue(canRenderFinishedJsonFence("json", doc.selectFirst("code")!!.wholeText(), streaming = false))
    }
    @Test fun `generic JSON chart variants are recognized but functions and unrelated data remain code`() {
        assertEquals("echarts", resolveDiagramLanguage("jsonc", "{series:{type:'bar',data:[1,2],},}"))
        assertEquals("vega", resolveDiagramLanguage("json", """{"data":{"values":[]},"layer":[{"mark":"bar","encoding":{}}]}"""))
        for (source in listOf("{type:'Point',coordinates:fetch('https://example.com')}",
            "{type:'Point',coordinates:Diagram(Terminal('x'))}", "{type:'Point',coordinates:[120,30]}; alert(1)",
            "{type:'Point',coordinates:coords}", "{value:NaN}")) {
            assertNull(source, parseDiagramData(source))
            assertEquals("json", resolveDiagramLanguage("json", source))
        }
        assertEquals("json", resolveDiagramLanguage("json", "[]"))
        assertEquals("json", resolveDiagramLanguage("json", "[{\"type\":\"Point\",\"price\":1}]"))
    }
    @Test fun `generic fences recognize complete svg and geojson only`() {
        assertEquals("svg", resolveDiagramLanguage("xml", "<?xml version='1.0'?><svg xmlns='http://www.w3.org/2000/svg'><path/></svg>"))
        assertEquals("xml", resolveDiagramLanguage("xml", "<svg><path/>"))
        assertEquals("xml", resolveDiagramLanguage("xml", "<document><svg/></document>"))
        assertEquals("geojson", resolveDiagramLanguage("json", """{"type":"FeatureCollection","features":[]}"""))
        assertEquals("json", resolveDiagramLanguage("json", """{"type":"Point","price":10}"""))
        assertEquals("python", resolveDiagramLanguage("python", "<svg></svg>"))
    }
    @Test fun `railroad constructor DSL becomes data preserving alternatives and repetition`() {
        val source = "// railroad\nDiagram(Terminal('IF'), NonTerminal('condition'), Optional(Sequence(Terminal('ELSE'), NonTerminal('statement')))).addTo();"
        assertEquals("railroad", resolveDiagramLanguage("javascript", source))
        val value = Json.parseToJsonElement(normalizeRailroadSource(source)).jsonObject
        assertEquals("sequence", value["type"]?.jsonPrimitive?.content)
        assertEquals("optional", value["items"]?.jsonArray?.last()?.jsonObject?.get("type")?.jsonPrimitive?.content)
        val repeat = DiagramLiteralParser("Diagram(OneOrMore(NonTerminal('column'), Terminal(',')))").railroad().toString()
        assertTrue(repeat.contains("oneOrMore"))
        assertTrue(repeat.contains(","))
    }
    @Test fun `W3C productions support C style comments and character ranges`() {
        val spec = parseEbnfRailroad("/* JSON array */\nJsonArray ::= '[' (Value (',' Value)*)? ']'\n/* number */\nNumber ::= '-'? [0-9]+ ('.' [0-9]+)?")
        assertEquals("choice", spec.type)
        assertEquals(2, spec.items.size)
        assertTrue(spec.toString().contains("[0-9]"))
    }
    @Test fun `chart literals support comments single quotes declarations and registered GeoJSON`() {
        val source = """
            const chinaGeoJson = {type:'FeatureCollection',features:[]};
            echarts.registerMap('china', chinaGeoJson);
            const option = {geo:{map:'china'}, series:[{type:'scatter',data:[[120,30,2]]}],};
        """.trimIndent()
        assertEquals("echarts", resolveDiagramLanguage("javascript", source))
        val config = Json.parseToJsonElement(normalizeChartSource(source)).jsonObject
        assertNotNull(config["maps"]?.jsonObject?.get("china"))
        assertEquals("china", config["option"]?.jsonObject?.get("geo")?.jsonObject?.get("map")?.jsonPrimitive?.content)
    }
    @Test fun `missing chart data is explained without executing code`() {
        val source = "echarts.registerMap('custom', unknownGeoJson); const option={geo:{map:'custom'}};"
        assertEquals("echarts", resolveDiagramLanguage("javascript", source))
        assertTrue(normalizeChartSource(source).contains("缺少本代码块中的数据定义"))
        assertTrue(normalizeRailroadSource("Diagram(fetch('https://example.com'))").contains("rikkaRenderError"))
        assertTrue(normalizeChartSource("const option = {series:[]}; while(true){}").contains("rikkaRenderError"))
        assertEquals("javascript", resolveDiagramLanguage("javascript", "console.log('Diagram(hello)')"))
    }
    @Test fun `built in map placeholders resolve only in registerMap and explicit data wins`() {
        val fallback = Json.parseToJsonElement(normalizeChartSource("echarts.registerMap('china', chinaGeoJson); const option={series:[{type:'map',map:'china'}]};")).jsonObject
        assertEquals("china", fallback["maps"]!!.jsonObject["china"]!!.jsonObject["rikkaBuiltinMap"]!!.jsonPrimitive.content)
        val custom = Json.parseToJsonElement(normalizeChartSource("const chinaGeoJson={type:'FeatureCollection',features:[]}; echarts.registerMap('china',chinaGeoJson); const option={geo:{map:'china'}};")).jsonObject
        assertEquals("FeatureCollection", custom["maps"]!!.jsonObject["china"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertTrue(normalizeChartSource("const option={data:chinaGeoJson};").contains("rikkaRenderError"))
        assertTrue(normalizeChartSource("echarts.registerMap('china',chinaGeoJson()); const option={};").contains("rikkaRenderError"))
    }
    @Test fun `deep malformed input is bounded`() {
        assertTrue(normalizeChartSource("const option = " + "[".repeat(100) + "0" + "]".repeat(100)).contains("rikkaRenderError"))
        assertEquals("xml", resolveDiagramLanguage("xml", "<svg>" + "x".repeat(MAX_DIAGRAM_SOURCE_LENGTH) + "</svg>"))
    }
    @Test fun `generate screenshot regression pages from real renderer output`() {
        val dir = File("build/reports/diagram-regressions").apply { mkdirs() }
        // Reproduce dark surfaces with black foreground inherited from a bright wallpaper.
        val scheme = darkColorScheme().copy(onSurface = Color.Black, onBackground = Color.Black,
            onPrimaryContainer = Color.Black, onSecondaryContainer = Color.Black, onTertiaryContainer = Color.Black)
        val diagrams = mapOf(
            "sequence" to "sequenceDiagram\nparticipant U as 用户\nparticipant A as 服务\nU->>A: 创建订单\nA-->>U: 支付成功",
            "mindmap" to "mindmap\n  root((项目架构))\n    前端\n      React\n      Vue\n    后端\n      Node.js\n      Go",
            "flowchart" to "flowchart LR\n A[开始] --> B{完成?}\n B --> C[结束]",
            "invalid" to "this is not a diagram",
        )
        diagrams.forEach { (name, code) ->
            val html = buildMermaidHtml(code, scheme)
            File(dir, "$name.html").writeText(html)
            File(dir, "$name-full.html").writeText(prepareFullscreenPreviewHtml(html))
        }
        File(dir, "railroad.html").writeText(buildInteractiveRendererHtml(InteractiveCodeRenderer.RAILROAD,
            "Diagram(Terminal('IF'), NonTerminal('condition'), Optional(Sequence(Terminal('ELSE'), NonTerminal('statement'))))", scheme))
        File(dir, "railroad-stack.html").writeText(buildInteractiveRendererHtml(InteractiveCodeRenderer.RAILROAD,
            "Diagram(Stack(Terminal('FIRST'), Sequence(Terminal('SECOND'), Optional(NonTerminal('THIRD'))), Terminal('LAST')))", scheme))
        File(dir, "ebnf.html").writeText(buildInteractiveRendererHtml(InteractiveCodeRenderer.RAILROAD,
            "/* JSON */\nJsonArray ::= '[' (Value (',' Value)*)? ']'\nNumber ::= '-'? [0-9]+", scheme))
        File(dir, "chart-missing.html").writeText(buildInteractiveRendererHtml(InteractiveCodeRenderer.ECHARTS,
            "const option={geo:{map:'not-provided'},series:[{type:'scatter',data:[[120,30,2]]}]}", scheme))
        val maps = mapOf(
            "china" to "echarts.registerMap('china',chinaGeoJson); const option={visualMap:{min:0,max:100},series:[{type:'map',map:'china',data:[{name:'浙江',value:70},{name:'广东',value:40}]}]};",
            "world" to "const option={geo:{map:'world'},series:[{type:'scatter',coordinateSystem:'geo',data:[[120,30,2],[0,30,5]]}]};",
            "world-lines" to "const option={geo:{map:'world'},series:[{type:'lines',coordinateSystem:'geo',data:[{coords:[[120,30],[0,30]]}]}]};",
        )
        maps.forEach { (name, source) ->
            File(dir, "map-$name.html").writeText(buildInteractiveRendererHtml(InteractiveCodeRenderer.ECHARTS, source, scheme))
            File(dir, "map-$name-full.html").writeText(prepareFullscreenPreviewHtml(buildInteractiveRendererHtml(InteractiveCodeRenderer.ECHARTS, source, scheme)))
        }
        File(dir, "chart.html").writeText(buildInteractiveRendererHtml(InteractiveCodeRenderer.ECHARTS,
            "const option={xAxis:{data:['A','B']},yAxis:{},series:[{type:'bar',data:[1,2]}]}", scheme))
    }
}
