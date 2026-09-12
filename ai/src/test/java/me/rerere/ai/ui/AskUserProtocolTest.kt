package me.rerere.ai.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AskUserProtocolTest {
    @Test
    fun `valid request supports optional text and multi select`() {
        val request = AskUserProtocol.parseRequest(
            """
            {
              "questions": [
                {"id":"name","question":"Name"},
                {"id":"style","question":"Style","selection_type":"multi","options":["a","b"],"required":false}
              ]
            }
            """.trimIndent()
        ).getOrThrow()

        assertEquals(2, request.questions.size)
        assertEquals(AskUserProtocol.SelectionType.MULTI, request.questions[1].selectionType)
        assertFalse(request.questions[1].required)
    }

    @Test
    fun `invalid request cannot become pending`() {
        assertTrue(
            AskUserProtocol.parseRequest("{\"questions\":[]}").isFailure
        )
        assertTrue(
            AskUserProtocol.parseRequest(
                "{\"questions\":[{\"id\":\"same\",\"question\":\"a\"},{\"id\":\"same\",\"question\":\"b\"}]}"
            ).isFailure
        )
        assertTrue(
            AskUserProtocol.parseRequest(
                "{\"questions\":[{\"id\":\"q\",\"question\":\"a\",\"selection_type\":\"single\"}]}"
            ).isFailure
        )
    }

    @Test
    fun `answer is normalized and legacy multi string remains compatible`() {
        val request = AskUserProtocol.parseRequest(
            "{\"questions\":[{\"id\":\"style\",\"question\":\"Style\",\"selection_type\":\"multi\",\"options\":[\"a\",\"b\"]}]}"
        ).getOrThrow()
        val normalized = AskUserProtocol.validateAnswer(
            request,
            "{\"answers\":{\"style\":\"a, b\"}}"
        ).getOrThrow()
        assertEquals("{\"answers\":{\"style\":[\"a\",\"b\"]}}", normalized)
    }

    @Test
    fun `answer rejects missing required and invalid option`() {
        val request = AskUserProtocol.parseRequest(
            "{\"questions\":[{\"id\":\"choice\",\"question\":\"Choice\",\"selection_type\":\"single\",\"options\":[\"a\"]}]}"
        ).getOrThrow()
        assertTrue(AskUserProtocol.validateAnswer(request, "{\"answers\":{}}").isFailure)
        assertTrue(
            AskUserProtocol.validateAnswer(
                request,
                "{\"answers\":{\"choice\":\"invalid\"}}"
            ).isFailure
        )
    }

    @Test
    fun `optional single answer accepts an explicit blank value`() {
        val request = AskUserProtocol.parseRequest(
            "{\"questions\":[{\"id\":\"choice\",\"question\":\"Choice\",\"selection_type\":\"single\",\"options\":[\"a\"],\"required\":false}]}"
        ).getOrThrow()

        assertEquals(
            "{\"answers\":{}}",
            AskUserProtocol.validateAnswer(request, "{\"answers\":{\"choice\":\"\"}}")
                .getOrThrow(),
        )
    }

    @Test
    fun `request preserves defaults and validates answer constraints`() {
        val request = AskUserProtocol.parseRequest(
            """
            {
              "questions": [
                {"id":"name","question":"Name","placeholder":"Your name","default":"Ada","min_length":2,"max_length":8},
                {"id":"tags","question":"Tags","selection_type":"multi","options":["a","b","c"],"default":["a"],"min_items":1,"max_items":2}
              ]
            }
            """.trimIndent()
        ).getOrThrow()

        assertEquals("Ada", request.questions[0].defaultValue)
        assertEquals("Your name", request.questions[0].placeholder)
        assertEquals(listOf("a"), request.questions[1].defaultValues)
        assertTrue(
            AskUserProtocol.validateAnswer(
                request,
                """{"answers":{"name":"A","tags":["a"]}}""",
            ).isFailure,
        )
        assertTrue(
            AskUserProtocol.validateAnswer(
                request,
                """{"answers":{"name":"Ada","tags":["a","b","c"]}}""",
            ).isFailure,
        )
    }

    @Test
    fun `invalid defaults and incompatible constraints are rejected`() {
        assertTrue(
            AskUserProtocol.parseRequest(
                """{"questions":[{"id":"q","question":"Q","selection_type":"single","options":["a"],"default":"b"}]}"""
            ).isFailure,
        )
        assertTrue(
            AskUserProtocol.parseRequest(
                """{"questions":[{"id":"q","question":"Q","min_items":1}]}"""
            ).isFailure,
        )
        assertTrue(
            AskUserProtocol.parseRequest(
                """{"questions":[{"id":"q","question":"Q","selection_type":"multi","options":["a"],"min_items":2}]}"""
            ).isFailure,
        )
    }

    @Test
    fun `numeric rating confirmation and date time questions are validated`() {
        val request = AskUserProtocol.parseRequest(
            """
            {
              "questions": [
                {"id":"volume","question":"Volume","selection_type":"slider","min_value":0,"max_value":1,"step":0.25,"default":0.5},
                {"id":"score","question":"Score","selection_type":"rating","default":4},
                {"id":"danger","question":"Delete?","selection_type":"confirm","danger":true,"timeout_seconds":10},
                {"id":"day","question":"Day","selection_type":"date"},
                {"id":"at","question":"Time","selection_type":"time"}
              ]
            }
            """.trimIndent()
        ).getOrThrow()

        assertEquals(0.0, request.questions[0].minValue)
        assertEquals(5.0, request.questions[1].maxValue)
        assertEquals("false", AskUserProtocol.parseRequest(
            """{"questions":[{"id":"ok","question":"Continue","selection_type":"confirm","default":false}]}"""
        ).getOrThrow().questions.single().defaultValue)

        val normalized = AskUserProtocol.validateAnswer(
            request,
            """{"answers":{"volume":0.75,"score":5,"danger":false,"day":"2026-09-03","at":"14:30"}}""",
        ).getOrThrow()
        assertTrue(normalized.contains("\"volume\":0.75"))
        assertTrue(normalized.contains("\"danger\":false"))
        assertTrue(
            AskUserProtocol.validateAnswer(
                request,
                """{"answers":{"volume":0.7,"score":5,"danger":false,"day":"2026-09-03","at":"14:30"}}""",
            ).isFailure,
        )
        assertTrue(
            AskUserProtocol.parseRequest(
                """{"questions":[{"id":"score","question":"Score","selection_type":"rating","min_value":1,"max_value":20,"step":1}]}"""
            ).isFailure,
        )
        assertTrue(
            AskUserProtocol.validateAnswer(
                request,
                """{"answers":{"volume":0.75,"score":5,"danger":false,"day":"2026-02-30","at":"14:30"}}""",
            ).isFailure,
        )
    }

    @Test
    fun `optional confirmation may be omitted`() {
        val request = AskUserProtocol.parseRequest(
            """{"questions":[{"id":"confirm","question":"Continue?","selection_type":"confirm","required":false}]}""",
        ).getOrThrow()

        assertEquals("{\"answers\":{}}", AskUserProtocol.validateAnswer(request, "{\"answers\":{}}").getOrThrow())
    }

    @Test
    fun `rich options and nested visibility conditions are supported`() {
        val request = AskUserProtocol.parseRequest(
            """
            {
              "questions": [
                {"id":"mode","question":"Mode","selection_type":"single","options":[{"value":"basic","label":"Basic","badge":"Fast","icon":"B"},{"value":"advanced","label":"Advanced","image":"https://example.com/a.png"}]},
                {"id":"details","question":"Details","visible_if":{"all":[{"question_id":"mode","operator":"equals","value":"advanced"}]}}
              ]
            }
            """.trimIndent()
        ).getOrThrow()

        assertEquals("Fast", request.questions[0].optionDetails[0].badge)
        assertEquals("https://example.com/a.png", request.questions[0].optionDetails[1].imageUrl)
        assertEquals(
            "{\"answers\":{\"mode\":\"basic\"}}",
            AskUserProtocol.validateAnswer(request, "{\"answers\":{\"mode\":\"basic\"}}")
                .getOrThrow(),
        )
        assertTrue(
            AskUserProtocol.validateAnswer(request, "{\"answers\":{\"mode\":\"advanced\"}}")
                .isFailure,
        )
        assertTrue(
            AskUserProtocol.validateAnswer(
                request,
                "{\"answers\":{\"mode\":\"advanced\",\"details\":\"configured\"}}",
            ).isSuccess,
        )
    }

    @Test
    fun `condition references reject cycles and unknown ids`() {
        assertTrue(
            AskUserProtocol.parseRequest(
                """{"questions":[{"id":"a","question":"A","visible_if":{"question_id":"missing","operator":"exists"}}]}"""
            ).isFailure,
        )
        assertTrue(
            AskUserProtocol.parseRequest(
                """{"questions":[{"id":"a","question":"A","visible_if":{"question_id":"b","operator":"exists"}},{"id":"b","question":"B","visible_if":{"question_id":"a","operator":"exists"}}]}"""
            ).isFailure,
        )
    }

    @Test
    fun `conditions support aliases and multi select in matching`() {
        val request = AskUserProtocol.parseRequest(
            """
            {
              "questions": [
                {"id":"tags","question":"Tags","selection_type":"multi","options":["red","blue"]},
                {"id":"details","question":"Details","visible_if":{"question_id":"tags","operator":"IN","value":["blue"]}}
              ]
            }
            """.trimIndent()
        ).getOrThrow()

        val matching = AskUserProtocol.validateAnswer(
            request,
            """{"answers":{"tags":["red","blue"],"details":"ok"}}""",
        )
        assertTrue(matching.exceptionOrNull()?.message.orEmpty(), matching.isSuccess)
        val nonMatching = AskUserProtocol.validateAnswer(
            request,
            """{"answers":{"tags":["red"]}}""",
        )
        assertEquals("{\"answers\":{\"tags\":[\"red\"]}}", nonMatching.getOrThrow())
    }
}
