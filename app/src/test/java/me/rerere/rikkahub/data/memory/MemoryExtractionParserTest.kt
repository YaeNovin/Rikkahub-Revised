package me.rerere.rikkahub.data.memory

import kotlinx.serialization.json.Json
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.MemoryType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryExtractionParserTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `parses standard memories response`() {
        val result = parseMemoryExtractionCandidates(
            json,
            """{"memories":[{"type":"fact","content":"Uses dark mode","confidence":0.9}]}""",
        )

        assertNotNull(result)
        assertEquals(1, result?.size)
        assertEquals(MemoryType.FACT, result?.single()?.type)
        assertEquals("Uses dark mode", result?.single()?.content)
        assertEquals(0.9f, result?.single()?.confidence ?: 0f, 0.0001f)
    }

    @Test
    fun `parses fenced facts and episodic arrays`() {
        val result = parseMemoryExtractionCandidates(
            json,
            """
                ```json
                {
                  "facts": ["Prefers concise replies"],
                  "episodic": [{"content":"Planned a September release","confidence":0.8}]
                }
                ```
            """.trimIndent(),
        )

        assertEquals(2, result?.size)
        assertEquals(MemoryType.FACT, result?.get(0)?.type)
        assertEquals(MemoryType.EPISODIC, result?.get(1)?.type)
    }

    @Test
    fun `parses top level arrays and encoded json strings`() {
        val array = parseMemoryExtractionCandidates(json, """["First", {"memory":"Second"}]""")
        val encoded = parseMemoryExtractionCandidates(
            json,
            """"{\"memories\":[{\"content\":\"Encoded\"}]}"""",
        )

        assertEquals(listOf("First", "Second"), array?.map { it.content })
        assertEquals("Encoded", encoded?.single()?.content)
    }

    @Test
    fun `parses a single memory object and empty result`() {
        val single = parseMemoryExtractionCandidates(
            json,
            """{"memory":{"type":"episodic","text":"A decision was made"}}""",
        )
        val empty = parseMemoryExtractionCandidates(json, """{"memories":[]}""")

        assertEquals(MemoryType.EPISODIC, single?.single()?.type)
        assertEquals("A decision was made", single?.single()?.content)
        assertEquals(emptyList<ExtractedMemoryCandidate>(), empty)
    }

    @Test
    fun `parses episodic lifecycle actions and related ids`() {
        val result = parseMemoryExtractionCandidates(
            json,
            """{"memories":[
                {"action":"complete","type":"episodic","related_memory_ids":[4,4,9],"confidence":0.9},
                {"action":"supersede","content":"The new plan is shipping in October","related_memory_ids":[9]}
            ]}""",
        )

        assertEquals(2, result?.size)
        assertEquals(MemoryExtractionAction.COMPLETE, result?.get(0)?.action)
        assertEquals(listOf(4, 9), result?.get(0)?.relatedMemoryIds)
        assertEquals(MemoryExtractionAction.SUPERSEDE, result?.get(1)?.action)
        assertEquals(MemoryType.EPISODIC, result?.get(1)?.type)
    }

    @Test
    fun `accepts common alternative confidence fields`() {
        val result = parseMemoryExtractionCandidates(
            json,
            """{"memories":[{"content":"One","score":0.7},{"content":"Two","importance":0.8}]}""",
        )

        assertEquals(listOf(0.7f, 0.8f), result?.map { it.confidence })
    }

    @Test
    fun `rejects malformed or unrelated responses`() {
        assertNull(parseMemoryExtractionCandidates(json, "not json"))
        assertNull(parseMemoryExtractionCandidates(json, """{"answer":"nothing"}"""))
    }

    @Test
    fun `uses a system instruction separate from untrusted transcript`() {
        val request = buildMemoryExtractionRequest(
            transcript = "User: Please remember that I prefer concise replies",
            allowEpisodic = true,
        )

        assertEquals(listOf(MessageRole.SYSTEM, MessageRole.USER), request.map { it.role })
        assertTrue(request.first().toText().contains("strong memory signal"))
        assertTrue(request.last().toText().contains("<conversation>"))
    }

    @Test
    fun `compacts long model replies before extraction`() {
        val transcript = buildMemoryExtractionTranscript(
            listOf(
                UIMessage.user("My name is Lin"),
                UIMessage.assistant("x".repeat(20_000)),
            )
        )

        assertNotNull(transcript)
        assertTrue(transcript!!.length <= 6_000)
        assertTrue(transcript.contains("My name is Lin"))
    }

    @Test
    fun `falls back to an explicit durable memory when model returns no candidates`() {
        val candidate = explicitMemoryFallbackCandidate(
            messages = listOf(
                UIMessage.user("请记住：我更喜欢简洁且直接的回答"),
                UIMessage.assistant("好的"),
            ),
            allowEpisodic = false,
        )

        assertEquals(MemoryType.FACT, candidate?.type)
        assertEquals("我更喜欢简洁且直接的回答", candidate?.content)
    }

    @Test
    fun `explicit fallback rejects credentials and ordinary questions`() {
        val credential = explicitMemoryFallbackCandidate(
            messages = listOf(
                UIMessage.user("请记住我的 API key 是 abc123"),
                UIMessage.assistant("好的"),
            ),
            allowEpisodic = false,
        )
        val question = explicitMemoryFallbackCandidate(
            messages = listOf(
                UIMessage.user("我的名字是什么？"),
                UIMessage.assistant("我不知道"),
            ),
            allowEpisodic = false,
        )

        assertNull(credential)
        assertNull(question)
    }
}
