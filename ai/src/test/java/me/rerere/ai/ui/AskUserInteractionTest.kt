package me.rerere.ai.ui

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AskUserInteractionTest {
    private val input = """{"questions":[
      {"id":"mode","question":"Mode"},
      {"id":"first","question":"Proceed?","selection_type":"confirm","timeout_seconds":5},
      {"id":"later","question":"Danger?","selection_type":"confirm","danger":true,"timeout_seconds":10,"visible_if":{"question_id":"mode","value":"advanced"}}
    ]}"""
    private val request = AskUserProtocol.parseRequest(input).getOrThrow()
    private val start = AskUserInteraction.Clock(100_000, 10_000, 2)
    private fun answers(source: String) = Json.parseToJsonElement(source).jsonObject

    @Test fun `countdown starts on display and hidden confirmations never shorten another deadline`() {
        val initial = AskUserInteraction.initial(input, start.wall)
        assertNull(AskUserInteraction.remainingMillis(input, initial, "first", start))
        val state = AskUserInteraction.update(input, request, initial, answers("""{"mode":"basic"}"""), setOf("first", "later"), start)
        assertEquals(5_000L, AskUserInteraction.remainingMillis(input, state, "first", start))
        assertNull(AskUserInteraction.remainingMillis(input, state, "later", start))
        val clock = start.copy(wall = start.wall + 7000, elapsed = start.elapsed + 7000)
        val expanded = AskUserInteraction.update(input, request, state, answers("""{"mode":"advanced"}"""), setOf("first", "later"), clock)
        assertEquals(JsonPrimitive(false), AskUserInteraction.answers(input, expanded)["first"])
        assertEquals(10_000L, AskUserInteraction.remainingMillis(input, expanded, "later", clock))
    }

    @Test fun `draft restoration keeps countdown and ignores stale updates or replaced tool input`() {
        val first = AskUserInteraction.update(input, request, AskUserInteraction.initial(input, start.wall),
            answers("""{"mode":"draft"}"""), setOf("first"), start, revision = 20)
        val restored = Json.parseToJsonElement(first.toString()).jsonObject
        val stale = AskUserInteraction.update(input, request, restored, answers("""{"mode":"old"}"""), setOf("first"), start, revision = 10)
        assertEquals(restored, stale)
        assertEquals("draft", AskUserInteraction.answers(input, restored)["mode"]!!.jsonPrimitive.content)
        assertTrue(AskUserInteraction.answers(input + " ", restored).isEmpty())
        assertEquals(3_000L, AskUserInteraction.remainingMillis(input, restored, "first", start.copy(wall = 10_000_000, elapsed = 12_000)))
        assertEquals(2_000L, AskUserInteraction.remainingMillis(input, restored, "first", start.copy(wall = 103_000, elapsed = 200, boot = 3)))
    }

    @Test fun `late approval is coerced to false but an on time explicit answer survives submission delay`() {
        val state = AskUserInteraction.update(input, request, AskUserInteraction.initial(input, start.wall),
            answers("""{"mode":"basic"}"""), setOf("first"), start)
        val late = start.copy(wall = 110_000, elapsed = 20_000)
        val payload = """{"answers":{"mode":"basic","first":true}}"""
        assertEquals(JsonPrimitive(false), Json.parseToJsonElement(AskUserInteraction.submit(input, request, state, payload, late).getOrThrow()).jsonObject["answers"]!!.jsonObject["first"])
        val ack = AskUserInteraction.update(input, request, state, answers("""{"mode":"basic","first":true}"""), setOf("first"), start.copy(wall = 102_000, elapsed = 12_000))
        assertEquals(JsonPrimitive(true), Json.parseToJsonElement(AskUserInteraction.submit(input, request, ack, payload, late).getOrThrow()).jsonObject["answers"]!!.jsonObject["first"])
        assertFalse(AskUserInteraction.timedOut(input, ack, "first", late))
        assertTrue(AskUserInteraction.submit(input, request, AskUserInteraction.initial(input, start.wall), payload, start).isFailure)
    }

    @Test fun `cleared or hidden confirmation cannot reuse an earlier acknowledgement`() {
        val state = AskUserInteraction.update(input, request, AskUserInteraction.initial(input, start.wall),
            answers("""{"mode":"advanced","first":true,"later":true}"""), setOf("first", "later"), start)
        val hidden = AskUserInteraction.update(input, request, state, answers("""{"mode":"basic"}"""), emptySet(), start.copy(elapsed = 11_000))
        val late = start.copy(wall = 120_000, elapsed = 30_000)
        assertTrue(AskUserInteraction.timedOut(input, hidden, "first", late))
        assertTrue(AskUserInteraction.timedOut(input, hidden, "later", late))
        val shown = AskUserInteraction.update(input, request, hidden,
            answers("""{"mode":"advanced","first":true,"later":true}"""), setOf("first", "later"), late)
        assertEquals(JsonPrimitive(false), AskUserInteraction.answers(input, shown)["first"])
        assertEquals(JsonPrimitive(false), AskUserInteraction.answers(input, shown)["later"])
    }

    @Test fun `an expired parent removes conditional answers in the same update`() {
        val source = """{"questions":[{"id":"go","question":"Go?","selection_type":"confirm","timeout_seconds":1},
            {"id":"detail","question":"Detail","visible_if":{"question_id":"go","value":"true"}}]}"""
        val req = AskUserProtocol.parseRequest(source).getOrThrow()
        val initial = AskUserInteraction.update(source, req, AskUserInteraction.initial(source, start.wall), answers("{}"), setOf("go"), start)
        val expired = AskUserInteraction.update(source, req, initial, answers("""{"go":true,"detail":"must be hidden"}"""), emptySet(), start.copy(elapsed = 20_000))
        assertEquals(answers("""{"go":false}"""), AskUserInteraction.answers(source, expired))
    }

    @Test fun `optional malformed answers are rejected rather than dropped by the draft filter`() {
        val source = """{"questions":[{"id":"text","question":"Text","required":false,"max_length":3},
            {"id":"choices","question":"Choices","selection_type":"multi","options":["a","b"],"required":false}]}"""
        val req = AskUserProtocol.parseRequest(source).getOrThrow()
        val state = AskUserInteraction.initial(source, start.wall)
        for (payload in listOf("""{"answers":{"text":"too long"}}""", """{"answers":{"text":{}}}""",
            """{"answers":{"choices":["invalid"]}}""", """{"answers":{"unknown":"a"}}""")) {
            assertTrue(payload, AskUserInteraction.submit(source, req, state, payload, start).isFailure)
        }
        assertTrue(AskUserInteraction.submit(source, req, state, """{"answers":{}}""", start).isSuccess)
    }

    @Test fun `answered conditional fields remain visible after draft metadata is removed`() {
        val submitted = """{"answers":{"mode":"advanced","first":false,"later":true}}"""
        val restored = AskUserInteraction.displayAnswers(input, null, ToolApprovalState.Answered(submitted))
        assertTrue(AskUserProtocol.isQuestionVisible(request.questions.last(), restored))
        assertEquals(JsonPrimitive(true), restored["later"])
        assertTrue(AskUserInteraction.displayAnswers(input, null, ToolApprovalState.Pending).isEmpty())
    }
}
