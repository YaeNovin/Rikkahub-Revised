package me.rerere.rikkahub.data.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.ai.context.estimateTextTokens
import me.rerere.rikkahub.data.ai.transformers.LorebookVectorMatches
import me.rerere.rikkahub.data.ai.transformers.evaluateInjections
import me.rerere.rikkahub.data.ai.transformers.lorebookVectorScore
import me.rerere.rikkahub.data.export.decodeTavernLorebook
import org.junit.Assert.*
import org.junit.Test

class LorebookRuntimeIntegrationTest {
    private fun entry(key: String, content: String = "lore $key") = PromptInjection.RegexInjection(name = key, keywords = listOf(key), content = content, sourceFormat = LorebookSourceFormat.SILLY_TAVERN)
    private fun evaluate(book: Lorebook, text: List<UIMessage>) = evaluateInjections(text, Assistant(lorebookIds = setOf(book.id)), emptyList(), listOf(book), entertainmentMode = true)

    @Test fun `source labels alone never activate books and old assistant selection is retained`() {
        val book = Lorebook(entries = listOf(entry("x").copy(constantActive = true)))
        assertTrue(LorebookSources().resolve(Assistant()).isEmpty())
        assertTrue(evaluateInjections(listOf(UIMessage.user("x")), Assistant(), emptyList(), listOf(book), entertainmentMode = true).injections.isEmpty())
        assertEquals(setOf(book.id), LorebookSources().resolve(Assistant(lorebookIds = setOf(book.id))).keys)
    }

    @Test fun `source precedence deduplicates and conversation exclusions override inherited bindings`() {
        val id = kotlin.uuid.Uuid.random()
        val persona = LorebookPersona(name = "Player", lorebookIds = setOf(id))
        val sources = LorebookSources(globalIds = setOf(id), personas = listOf(persona), activePersonaId = persona.id)
        val assistant = Assistant(lorebookIds = setOf(id), allowConversationPromptInjection = true)
        assertEquals(LorebookSourceScope.PERSONA, sources.resolve(assistant)[id])
        assertEquals(LorebookSourceScope.CHAT, sources.resolve(assistant, setOf(id))[id])
        assertTrue(sources.resolve(assistant, setOf(id), setOf(id)).isEmpty())
        assertTrue(sources.resolve(Assistant(useGlobalLorebooks = false, usePersonaLorebooks = false)).isEmpty())
        val encoded = Json.encodeToString(LorebookSources.serializer(), sources)
        assertEquals(sources, Json.decodeFromString(LorebookSources.serializer(), encoded))
    }

    @Test fun `insertion strategy sorts character global layers without rewriting contents`() {
        val first = entry("character").copy(insertionOrder = 900, lorebookSourceRank = LorebookSourceScope.CHARACTER.insertionRank(LorebookInsertionStrategy.GLOBAL_FIRST))
        val second = entry("global").copy(insertionOrder = 1000, lorebookSourceRank = LorebookSourceScope.GLOBAL.insertionRank(LorebookInsertionStrategy.GLOBAL_FIRST))
        assertEquals(listOf(second, first), listOf(first, second).orderedForInsertion())
        assertEquals(0, LorebookSourceScope.GLOBAL.insertionRank(LorebookInsertionStrategy.LEGACY))
    }

    @Test fun `Tavern message boundaries prevent regex from spanning adjacent messages`() {
        val book = Lorebook(entries = listOf(entry("""/\x01Alice:[^\x01]*hello/""")), includeNames = true)
        val history = listOf(UIMessage.user("hello"), UIMessage.assistant("world"))
        val result = evaluateInjections(history, Assistant(lorebookIds = setOf(book.id)), emptyList(), listOf(book), entertainmentMode = true, userName = "Alice")
        assertEquals(1, result.injections.size)
        val negative = book.copy(entries = listOf(entry("""/\x01Alice:[^\x01]*world/""")))
        assertTrue(evaluateInjections(history, Assistant(lorebookIds = setOf(book.id)), emptyList(), listOf(negative), entertainmentMode = true, userName = "Alice").injections.isEmpty())
    }

    @Test fun `negative filters never inflate inclusion score`() {
        val result = entry("forest").copy(keywordExpression = "\"forest\" AND NOT (\"rain\" AND \"storm\")").evaluateKeywords("forest rain")
        assertTrue(result.matched)
        assertEquals(1, result.score)
        assertTrue("rain" in result.matchedTerms)
        val and = entry("forest").copy(keywordExpression = "\"forest\" AND (\"rain\" OR \"storm\")").evaluateKeywords("forest rain storm")
        assertEquals(3, and.score)
    }

    @Test fun `minimum activations stops before unnecessarily scanning oldest messages`() {
        val recent = entry("recent").copy(scanDepth = 1)
        val oldest = entry("oldest").copy(scanDepth = 1)
        val book = Lorebook(entries = listOf(recent, oldest), minActivations = 1)
        assertEquals(listOf(recent.id), evaluate(book, listOf(UIMessage.user("oldest"), UIMessage.assistant("recent"), UIMessage.user("plain"))).injections.map { it.id })
    }

    @Test fun `recursive book budget is cumulative rather than reset each pass`() {
        val first = entry("start", "castle facts")
        val second = entry("castle", "more castle facts")
        val budget = estimateTextTokens(first.content)
        val book = Lorebook(entries = listOf(first, second), recursiveScanning = true, maxRecursionSteps = 3, tokenBudget = budget)
        val result = evaluate(book, listOf(UIMessage.user("start")))
        assertEquals(listOf(first.id), result.injections.map { it.id })
        assertTrue(result.diagnostics.totalEstimatedTokens <= budget)
    }

    @Test fun `numeric recursive delay stages advance after lower stage finds nothing`() {
        val book = decodeTavernLorebook(Json.parseToJsonElement("""{"recursive_scanning":true,"entries":[{"key":["start"],"content":"castle"},{"key":["missing"],"content":"nothing","delayUntilRecursion":1},{"key":["castle"],"content":"deep lore","delayUntilRecursion":5}]}""").jsonObject, "staged")
        assertTrue(book.entries[2].delayUntilRecursion)
        assertEquals(5, book.entries[2].recursionLevel)
        assertTrue(evaluate(book, listOf(UIMessage.user("start"))).injections.any { it.content == "deep lore" })
    }

    @Test fun `multiple inclusion groups cannot admit two entries sharing a group`() {
        val first = entry("x").copy(priority = 2, inclusionGroups = listOf("a", "shared"))
        val second = entry("x").copy(priority = 1, inclusionGroups = listOf("b", "shared"))
        assertEquals(1, evaluate(Lorebook(entries = listOf(first, second)), listOf(UIMessage.user("x"))).injections.size)
    }

    @Test fun `decorator names are exact tokens not prefixes`() {
        val rule = entry("missing", "@@activate_only_after 0\nbody").copy(sourceFormat = LorebookSourceFormat.CHARACTER_CARD_V3)
        assertTrue(evaluate(Lorebook(entries = listOf(rule)), listOf(UIMessage.user("plain"))).injections.isEmpty())
    }

    @Test fun `semantic hit bypasses scan depth but obeys probability budget and binding`() {
        val rule = entry("missing").copy(vectorized = true, scanDepth = 0)
        val book = Lorebook(entries = listOf(rule))
        fun run(book: Lorebook, assistant: Assistant = Assistant(lorebookIds = setOf(book.id))) = evaluateInjections(listOf(UIMessage.user("unrelated text")), assistant, emptyList(), listOf(book), entertainmentMode = true, vectorMatches = LorebookVectorMatches(mapOf(rule.id to .9f)))
        assertEquals(1, run(book).injections.size)
        assertTrue(run(book.copy(entries = listOf(rule.copy(triggerProbability = 0)))).injections.isEmpty())
        assertTrue(run(book, Assistant()).injections.isEmpty())
        assertTrue(run(book.copy(tokenBudget = 1, entries = listOf(rule.copy(content = "long body ".repeat(100))))).injections.isEmpty())
    }

    @Test fun `extra sources are opt in and can activate even when chat scanning is off`() {
        val rule = entry("scientist").copy(scanMode = LorebookScanMode.NONE, additionalMatchingSources = setOf("matchPersonaDescription"))
        val book = Lorebook(entries = listOf(rule))
        val result = evaluateInjections(listOf(UIMessage.user("plain")), Assistant(lorebookIds = setOf(book.id)), emptyList(), listOf(book), entertainmentMode = true, additionalMatchingText = mapOf("matchPersonaDescription" to "I am a scientist"))
        assertEquals(1, result.injections.size)
        assertTrue(evaluate(book.copy(entries = listOf(rule.copy(additionalMatchingSources = emptySet()))), listOf(UIMessage.user("scientist"))).injections.isEmpty())
    }

    @Test fun `cosine rejects incompatible dimensions invalid and zero vectors`() {
        assertEquals(1f, lorebookVectorScore(listOf(1f, 0f), listOf(1f, 0f))!!, .0001f)
        assertNull(lorebookVectorScore(listOf(1f), listOf(1f, 0f)))
        assertNull(lorebookVectorScore(listOf(Float.NaN), listOf(1f)))
        assertNull(lorebookVectorScore(listOf(0f), listOf(0f)))
    }
}
