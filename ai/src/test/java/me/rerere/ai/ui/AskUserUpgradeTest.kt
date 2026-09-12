package me.rerere.ai.ui

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AskUserUpgradeTest {
    private fun request(questions: String) = AskUserProtocol.parseRequest("""{"questions":$questions}""").getOrThrow()

    @Test fun `all advertised examples are executable by the canonical validator`() {
        val examples = AskUserContract.capabilities(true)["examples"]!!.jsonArray
        examples.forEach { assertTrue(it.toString(), AskUserProtocol.parseRequest(it).isSuccess) }
        val fields = AskUserContract.schema().properties["questions"]!!.jsonObject["items"]!!.jsonObject["properties"]!!.jsonObject
        assertEquals(AskUserContract.questionFields, fields.keys)
        assertEquals(8, fields["selection_type"]!!.jsonObject["enum"]!!.jsonArray.size)
        assertEquals(4, fields["presentation"]!!.jsonObject["enum"]!!.jsonArray.size)
    }

    @Test fun `typed defaults preserve commas and unknown fields fail clearly`() {
        assertEquals(listOf("a,b"), request("""[{"id":"q","question":"Q","selection_type":"multi","options":["a,b","c"],"default_values":["a,b"]}]""").questions.single().defaultValues)
        assertTrue(AskUserProtocol.parseRequest("""{"questions":[{"id":"q","question":"Q","layout":"unknown"}]}""").isFailure)
        assertTrue(AskUserProtocol.parseRequest("""{"questions":[{"id":"q","question":"Q","selection_type":"date","presentation":"cards"}]}""").isFailure)
        assertNull(request("""[{"id":"q","question":"Q","selection_type":"confirm","danger":true,"default":"true"}]""").questions.single().defaultValue)
    }

    @Test fun `small decimal steps survive formatting snapping and answer validation`() {
        val q = request("""[{"id":"q","question":"Q","selection_type":"slider","min_value":0,"max_value":1,"step":0.0001}]""")
        assertEquals("0.1234", AskUserNumbers.format(0.1234))
        assertEquals("0.000000001", AskUserNumbers.format(1e-9))
        assertEquals("3000000000", AskUserNumbers.format(3e9))
        val value = AskUserNumbers.snapFraction(0.0, 1.0, .0001, .1234f)
        assertEquals("0.1234", value)
        assertTrue(AskUserProtocol.validateAnswer(q, """{"answers":{"q":$value}}""").isSuccess)
        assertEquals("9", AskUserNumbers.snapFraction(0.0, 10.0, 3.0, 1f))
        assertTrue(AskUserProtocol.parseRequest("""{"questions":[{"id":"q","question":"Q","selection_type":"slider","min_value":0,"max_value":1e300}]}""").isFailure)
        assertFalse(AskUserNumbers.isStepAligned(.001, 0.0, .0003))
    }

    @Test fun `conditions reject ambiguous groups and hidden parent answers do not activate children`() {
        assertTrue(AskUserProtocol.parseRequest("""{"questions":[{"id":"q","question":"Q","visible_if":{"all":[],"any":[]}}]}""").isFailure)
        val q = request("""[
            {"id":"mode","question":"Mode"},
            {"id":"hidden","question":"Hidden","visible_if":{"question_id":"mode","value":"advanced"}},
            {"id":"child","question":"Child","visible_if":{"question_id":"hidden","operator":"exists"}}
        ]""")
        val result = AskUserProtocol.validateAnswer(q, """{"answers":{"mode":"basic","hidden":"stale"}}""").getOrThrow()
        assertEquals("""{"answers":{"mode":"basic"}}""", result)
    }

    @Test fun `flat nested conditions support AND OR NOT and reject unused cyclic or explosive graphs`() {
        val graph = """{"root":"all","nodes":[
          {"id":"all","kind":"all","children":["any","not"]},
          {"id":"any","kind":"any","children":["work","play"]},
          {"id":"work","kind":"predicate","question_id":"mode","value":"work"},
          {"id":"play","kind":"predicate","question_id":"mode","value":"play"},
          {"id":"not","kind":"not","children":["blocked"]},
          {"id":"blocked","kind":"predicate","question_id":"mode","value":"blocked"}
        ]}"""
        val q = request("""[{"id":"mode","question":"Mode"},{"id":"child","question":"Child","visible_if":$graph}]""")
        assertTrue(AskUserProtocol.isQuestionVisible(q.questions[1], mapOf("mode" to JsonPrimitive("play"))))
        assertFalse(AskUserProtocol.isQuestionVisible(q.questions[1], mapOf("mode" to JsonPrimitive("blocked"))))
        assertTrue(runCatching { request("""[{"id":"mode","question":"Mode"},{"id":"child","question":"Child","visible_if":${graph.replace("[\"any\",\"not\"]", "[\"all\"]")}}]""") }.isFailure)
        val withoutNot = graph.replace("[\"any\",\"not\"]", "[\"any\"]")
        assertTrue(runCatching { request("""[{"id":"mode","question":"Mode"},{"id":"child","question":"Child","visible_if":$withoutNot}]""") }.isFailure)
    }

    @Test fun `optional null fields from gateways are omitted while invalid required fields fail`() {
        val q = request("""[{"id":"q","question":"Question","description":null,"default":null,"visible_if":null}]""")
        assertEquals("", q.questions.single().description)
        assertTrue(AskUserProtocol.parseRequest("""{"questions":[{"id":null,"question":"Q"}]}""").isFailure)
    }
}
