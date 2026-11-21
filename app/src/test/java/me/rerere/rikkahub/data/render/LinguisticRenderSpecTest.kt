package me.rerere.rikkahub.data.render

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.*
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.*
import org.junit.Test

class LinguisticRenderSpecTest {
    @Test fun advertisedExamplesCompile() {
        linguisticRendererSpecs.forEach { spec ->
            assertEquals(spec.id, compileLinguisticSpec(spec.id,spec.example)["renderer"]!!.jsonPrimitive.content)
            spec.aliases.forEach { assertEquals(spec,RendererCatalog.find(it)) }
        }
        val aliases=RendererCatalog.entries.flatMap { it.aliases }
        assertEquals(aliases.size,aliases.toSet().size)
        assertNull(RendererCatalog.find("ruby")) // Preserve Ruby programming fences.
        assertEquals("ruby_annotation",RendererCatalog.find("ruby-annotation")!!.id)
    }
    @Test fun ipaCombiningSequencesStayUnmodified() {
        val text="a\u0303ː t\u0361ʃ ɹ̠ˤ ˥˩ ʰ n̩ e\u0301"
        assertEquals(text,compileLinguisticSpec("ipa",buildJsonObject { put("version",1);put("text",text) }.toString())["text"]!!.jsonPrimitive.content)
    }
    @Test fun maliciousAndOversizedInputFailsBeforeRendering() {
        listOf("""{"version":1,"text":"x","onload":"alert(1)"}""","""{"version":2,"text":"x"}""",
            "[".repeat(49)+"0"+"]".repeat(49),"x".repeat(32769)).forEach { invalid ->
            assertTrue(runCatching { compileLinguisticSpec("ipa",invalid) }.isFailure)
        }
    }
    @Test fun wordAlignmentRequiresMatchingBoundariesAndCompleteLayers() {
        val good="""{"version":1,"words":[{"text":"evler","morphemes":"ev-ler=de","gloss":"house-PL=LOC"}],"translation":"at the houses"}"""
        compileLinguisticSpec("gloss",good)
        listOf(good.replace("PL=LOC","PL-LOC"),good.replace("house-PL=LOC","house PL=LOC"),
            good.replace("}],","},{\"text\":\"a\",\"gloss\":\"A\"}],")).forEach {
            assertTrue(runCatching { compileLinguisticSpec("gloss",it) }.isFailure)
        }
    }
    @Test fun udPreservesMultiwordAndEmptyNodesAndEnhancedDependencies() {
        val source="# sent_id = 1\n# text = can't go\n"+
            "1-2\tcan't\t_\t_\t_\tTypo=Yes\t_\t_\t_\tSpaceAfter=No\n"+
            "1\tca\tcan\tAUX\tMD\t_\t3\taux\t3:aux\t_\n"+
            "2\tn't\tnot\tPART\tRB\t_\t3\tadvmod\t3:advmod\t_\n"+
            "3\tgo\tgo\tVERB\tVB\t_\t0\troot\t0:root\t_\n"+
            "3.1\tthere\tthere\tADV\t_\t_\t_\t_\t3:advmod\t_\n"
        val sentence=compileLinguisticSpec("dependency",source)["sentences"]!!.jsonArray.single().jsonObject
        assertEquals(5,sentence["rows"]!!.jsonArray.size)
        assertTrue(sentence["arcs"]!!.jsonArray.any { it.jsonObject["enhanced"]==JsonPrimitive(true) })
        assertTrue(sentence.toString().contains("SpaceAfter=No"))
        listOf(source.replace("3\taux","1\taux"),source.replace("3:advmod","9:advmod"),
            source.replace("3.1\t","3.2\t"),source.replace("3\tgo","4\tgo"),source.replace("\tMD\t"," MD "),
            source.replace("there\tthere","e\u0301\tthere")).forEach { bad ->
            assertTrue(bad,runCatching { compileLinguisticSpec("dependency",bad) }.isFailure)
        }
        assertTrue(runCatching { compileLinguisticSpec("dependency","1\ta\t_\t_\t_\t_\t2\tdep\t_\t_\n2\tb\t_\t_\t_\t_\t1\tdep\t_\t_") }.isFailure)
    }
    @Test fun emptyNodesBeforeTheFirstWordAndCrLfAreAccepted() {
        val source="0.1\tit\t_\tPRON\t_\t_\t_\t_\t1:nsubj\t_\r\n1\trains\t_\tVERB\t_\t_\t0\troot\t_\t_\r\n"
        val rows=compileLinguisticSpec("dependency",source)["sentences"]!!.jsonArray.single().jsonObject["rows"]!!.jsonArray
        assertEquals("0.1",rows.first().jsonArray.first().jsonPrimitive.content)
    }
    @Test fun treeLimitsPreventRecursiveAndWideLayouts() {
        var root=buildJsonObject { put("label","x") }
        repeat(17) { val child=root;root=buildJsonObject { put("label","S");put("children",JsonArray(listOf(child))) } }
        assertTrue(runCatching { compileLinguisticSpec("syntax_tree",buildJsonObject { put("version",1);put("root",root) }.toString()) }.isFailure)
    }
    @Test fun disabledRenderersAreDiscoveredAndRejectedAndOldPreferencesWork() = runBlocking {
        val settings=LinguisticRenderingSettings(ipa=false,dependency=false)
        val tools=createRenderingTools(settings)
        val catalog=Json.parseToJsonElement((tools.first().execute(buildJsonObject {}).single() as UIMessagePart.Text).text).jsonObject
        val ipa=catalog["renderers"]!!.jsonArray.single { it.jsonObject["id"]==JsonPrimitive("ipa") }.jsonObject
        assertEquals(JsonPrimitive(false),ipa["enabled"])
        val result=tools.last().execute(buildJsonObject { put("renderer","ipa");put("source",linguisticRendererSpecs.first().example) })
        assertTrue((result.single() as UIMessagePart.Text).text.contains("renderer_disabled"))
        val session=createSessionCapabilitiesTool(Assistant(),true,{tools.map { it.name }},linguisticRendering=settings)
        assertTrue((session.execute(buildJsonObject {}).single() as UIMessagePart.Text).text.contains("\"ipa\":false"))
        assertTrue(Json.decodeFromString<DisplaySetting>("{}").linguisticRendering.ipa)
        assertEquals(settings,Json.decodeFromString<LinguisticRenderingSettings>(Json.encodeToString(settings)))
    }
}
