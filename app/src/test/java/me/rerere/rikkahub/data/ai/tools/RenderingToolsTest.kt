package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.render.RendererCatalog
import me.rerere.rikkahub.ui.components.richtext.InteractiveCodeRenderer
import me.rerere.rikkahub.ui.components.richtext.isTerminalFence
import org.junit.Assert.*
import org.junit.Test

class RenderingToolsTest {
    @Test fun `literary prose is discoverable bounded and respects its setting`() = runBlocking {
        val tools = createRenderingTools()
        val details = tools.first().query(buildJsonObject { put("renderer", "annotated_text") })
        val entry = details["renderers"]!!.jsonArray.single().jsonObject
        assertTrue(entry["notes"]!!.jsonPrimitive.content.contains("without fences"))
        val source = "<app><lem>春</lem><rdg wit=\"#A\">花</rdg></app>"
        val input = buildJsonObject { put("renderer", "annotated_text"); put("source", source) }
        assertEquals("accepted", tools.last().query(input)["status"]!!.jsonPrimitive.content)
        val disabled = createRenderingTools(me.rerere.rikkahub.data.render.LinguisticRenderingSettings(literary = false))
        assertEquals("renderer_disabled", disabled.last().query(input)["code"]!!.jsonPrimitive.content)
        assertEquals("error", tools.last().query(buildJsonObject { put("renderer", "annotated_text"); put("source", "<script>alert(1)</script>") })["status"]!!.jsonPrimitive.content)
    }
    @Test fun `curve formulas are discovered on demand and validated through existing tools`() = runBlocking {
        val tools = createRenderingTools()
        val compact = tools.first().query().toString()
        assertFalse(compact.contains("hypotrochoid"))
        for (id in listOf("sigil", "pattern")) {
            val details = tools.first().query(buildJsonObject { put("renderer", id) }).toString()
            for (formula in listOf("hypotrochoid", "epitrochoid", "lissajous", "harmonic")) {
                assertTrue(details.contains(formula))
                val accepted = tools.last().query(buildJsonObject {
                    put("renderer", id); put("source", """{"layers":[{"type":"curve","formula":"$formula"}]}""")
                })
                assertEquals("accepted", accepted["status"]!!.jsonPrimitive.content)
            }
            val rejected = tools.last().query(buildJsonObject {
                put("renderer", id); put("source", """{"layers":[{"type":"curve","formula":"alert(1)"}]}""")
            })
            assertEquals("error", rejected["status"]!!.jsonPrimitive.content)
        }
    }
    @Test fun `new geometry renderers validate inputs before acceptance and list usage on demand`() = runBlocking {
        val tools = createRenderingTools()
        val math = tools.first().query(buildJsonObject { put("renderer", "math_plan") })
        assertEquals(RendererCatalog.version, math["catalog_version"]!!.jsonPrimitive.int)
        val entry = math["renderers"]!!.jsonArray.single().jsonObject
        assertEquals("math_plot", entry["id"]!!.jsonPrimitive.content)
        assertTrue(entry["notes"]!!.jsonPrimitive.content.contains("parametric"))
        val rejected = tools.last().query(buildJsonObject {
            put("renderer", "jsxgraph"); put("source", """{"curves":[{"expression":"alert(1)"}]}""")
        })
        assertEquals("error", rejected["status"]!!.jsonPrimitive.content)
        val rejectedSigil = tools.last().query(buildJsonObject {
            put("renderer", "sigil"); put("source", """{"layers":[{"type":"script"}]}""")
        })
        assertEquals("error", rejectedSigil["status"]!!.jsonPrimitive.content)
        val backgroundSigil = tools.last().query(buildJsonObject {
            put("renderer", "sigil"); put("source", """{"background":"#101426","layers":[{"type":"ring","radius":90,"background":"#172033"}]}""")
        })
        assertEquals("accepted", backgroundSigil["status"]!!.jsonPrimitive.content)
        val pattern = tools.first().query(buildJsonObject { put("renderer", "generative_art") })["renderers"]!!.jsonArray.single().jsonObject
        assertEquals("pattern", pattern["id"]!!.jsonPrimitive.content)
        assertTrue(pattern["notes"]!!.jsonPrimitive.content.contains("mandala"))
        val rejectedPattern = tools.last().query(buildJsonObject {
            put("renderer", "p5_pattern"); put("source", "function setup() { alert(1) }")
        })
        assertEquals("error", rejectedPattern["status"]!!.jsonPrimitive.content)
    }

    @Test fun `old catalog queries cannot silently hide new renderers from discovery guidance`() = runBlocking {
        val list = createRenderingTools().first()
        val model = me.rerere.ai.provider.Model()
        assertEquals("", list.systemPrompt(model, emptyList()))
        val old = me.rerere.ai.ui.UIMessage.assistant("").copy(parts = listOf(UIMessagePart.Tool("old", LIST_RENDERERS_TOOL_NAME, "{}",
            listOf(UIMessagePart.Text("""{"renderers":[]}""")))))
        assertTrue(list.systemPrompt(model, listOf(old)).contains("query list_renderers again"))
        val current = old.copy(parts = listOf(old.getTools().single().copy(output = list.execute(buildJsonObject {}))))
        assertEquals("", list.systemPrompt(model, listOf(current)))
    }
    private suspend fun Tool.query(input: JsonObject = buildJsonObject {}) =
        Json.parseToJsonElement((execute(input).single() as UIMessagePart.Text).text).jsonObject

    @Test fun `all renderer aliases are unique and match the UI registry`() {
        val aliases = RendererCatalog.entries.flatMap { it.aliases }
        assertEquals(aliases.size, aliases.distinct().size)
        InteractiveCodeRenderer.entries.forEach { renderer -> renderer.aliases.forEach { alias ->
            assertNotNull(RendererCatalog.find(alias))
            assertEquals(renderer, InteractiveCodeRenderer.fromLanguage(alias))
        } }
        RendererCatalog.find("terminal")!!.aliases.forEach { assertTrue(isTerminalFence(it)) }
        assertNull(RendererCatalog.find("p5"))
        assertNull(RendererCatalog.find("pty"))
    }

    @Test fun `compact list and targeted details are requested on demand`() = runBlocking {
        val tool = createRenderingTools().first()
        val catalog = tool.query()["renderers"]!!.jsonArray
        assertEquals(RendererCatalog.entries.size, catalog.size)
        assertTrue(catalog.all { "example" !in it.jsonObject })
        val details = tool.query(buildJsonObject { put("renderer", "xterm") })["renderers"]!!.jsonArray.single().jsonObject
        assertEquals("terminal", details["id"]!!.jsonPrimitive.content)
        assertTrue("example" in details)
        assertEquals("error", tool.query(buildJsonObject { put("renderer", "missing") })["status"]!!.jsonPrimitive.content)
    }

    @Test fun `model tool registration and capability listing use the same enabled names`() = runBlocking {
        for (enabled in listOf(true, false)) {
            val tools = emptyList<Tool>().withRenderingTools(enabled).withRenderingTools(enabled)
            assertEquals(if (enabled) 2 else 0, tools.size)
            val capabilities = createSessionCapabilitiesTool(Assistant(), enabled, { tools.map { it.name } }).query()
            assertEquals(enabled, capabilities["rendering"]!!.jsonObject["enabled"]!!.jsonPrimitive.boolean)
            assertEquals(tools.map { it.name }.sorted(), capabilities["availableTools"]!!.jsonArray.map { it.jsonPrimitive.content })
            assertFalse(capabilities.toString().contains("SMILES"))
        }
    }

    @Test fun `HTML submission never grants automatic rendering or script privileges and does not echo source`() = runBlocking {
        val source = "<script>alert('xss')</script>"
        val result = createRenderingTools().last().query(buildJsonObject {
            put("renderer", "html"); put("source", source); put("allow_scripts", true)
        })
        assertEquals("accepted", result["status"]!!.jsonPrimitive.content)
        assertEquals("manual_static_preview", result["display"]!!.jsonPrimitive.content)
        assertFalse(result["runtime_verified"]!!.jsonPrimitive.boolean)
        assertFalse(result.toString().contains(source))
    }

    @Test fun `invalid empty oversized and wrong typed inputs fail without creating an artifact`() = runBlocking {
        val tool = createRenderingTools().last()
        val invalid = listOf(
            buildJsonObject { put("renderer", "not-installed"); put("source", "hello") },
            buildJsonObject { put("renderer", "svg"); put("source", " ") },
            buildJsonObject { put("renderer", "svg"); put("source", 12) },
            buildJsonObject { put("renderer", "smiles"); put("source", "C".repeat(4001)) },
            buildJsonObject { put("renderer", "code"); put("source", "test"); put("language", "html\n<script>") },
        )
        invalid.forEach { assertEquals("error", tool.query(it)["status"]!!.jsonPrimitive.content) }
    }

    @Test fun `each listed example can be submitted and aliases resolve to canonical IDs`() = runBlocking {
        val tool = createRenderingTools().last()
        RendererCatalog.entries.forEach { spec ->
            val result = tool.query(buildJsonObject { put("renderer", spec.aliases.last()); put("source", spec.example) })
            assertEquals(spec.id, result["renderer"]!!.jsonPrimitive.content)
            assertEquals("accepted", result["status"]!!.jsonPrimitive.content)
        }
    }
}
