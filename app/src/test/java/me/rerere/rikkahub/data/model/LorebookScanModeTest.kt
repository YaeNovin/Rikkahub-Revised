package me.rerere.rikkahub.data.model

import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.export.decodeTavernLorebook
import me.rerere.rikkahub.data.ai.transformers.evaluateInjections
import org.junit.Assert.*
import org.junit.Test

class LorebookScanModeTest {
    private fun rule() = PromptInjection.RegexInjection(name = "r", content = "body", keywords = listOf("forest"))
    private fun evaluate(book: Lorebook, messages: List<UIMessage> = listOf(UIMessage.user("forest")), entertainment: Boolean = true) =
        evaluateInjections(messages, Assistant(lorebookIds = setOf(book.id)), emptyList(), listOf(book), entertainmentMode = entertainment)

    @Test fun `zero does not trigger NOT expressions or empty matching regex`() {
        listOf(rule().copy(scanDepth = 0, keywordExpression = "NOT ocean"), rule().copy(scanDepth = 0, useRegex = true, keywords = listOf(".*"))).forEach {
            assertTrue(evaluate(Lorebook(entries = listOf(it))).injections.isEmpty())
            assertTrue(evaluate(Lorebook(entries = listOf(it)), entertainment = false).injections.isEmpty())
        }
    }
    @Test fun `constant zero is selected only when book is bound and still obeys budget`() {
        val book = Lorebook(entries = listOf(rule().copy(scanDepth = 0, constantActive = true, content = "a".repeat(100))), tokenBudget = 1)
        assertTrue(evaluate(book).injections.isEmpty())
        assertEquals(1, evaluate(book.copy(tokenBudget = 0)).injections.size)
        assertTrue(evaluateInjections(listOf(UIMessage.user("forest")), Assistant(), emptyList(), listOf(book), entertainmentMode = true).injections.isEmpty())
    }
    @Test fun `current input ignores older user input and later assistant output`() {
        val book = Lorebook(entries = listOf(rule().copy(scanMode = LorebookScanMode.CURRENT_INPUT, scanDepth = 0)))
        assertTrue(evaluate(book, listOf(UIMessage.user("forest"), UIMessage.user("ocean"))).injections.isEmpty())
        assertEquals(1, evaluate(book, listOf(UIMessage.user("forest"), UIMessage.assistant("ocean"))).injections.size)
    }
    @Test fun `null depth inherits while explicit zero survives import`() {
        val book = decodeTavernLorebook(Json.parseToJsonElement("""{"scan_depth":7,"entries":{"0":{"key":["forest"],"content":"x","scanDepth":null},"1":{"key":["forest"],"content":"y","scanDepth":0}}}""").jsonObject, "B")
        assertEquals(LorebookScanMode.INHERIT, book.entries[0].scanMode)
        assertEquals(7, book.entries[0].resolvedScanDepth(book))
        assertEquals(0, book.entries[1].scanDepth)
        assertEquals(1, evaluate(book).injections.size)
    }
    @Test fun `sample structural fixture retains all entries and five zero constants`() {
        // User's sample, with prose and keywords replaced; structural settings preserved.
        val json = javaClass.getResourceAsStream("/lorebook-zero-depth-sample.json")!!.bufferedReader().use { it.readText() }
        val book = decodeTavernLorebook(Json.parseToJsonElement(json).jsonObject, "sample")
        assertEquals(34, book.entries.size)
        assertEquals(5, book.entries.count { it.scanDepth == 0 && it.constantActive })
        assertEquals(4, book.entries.count { !it.enabled && it.keywords.isEmpty() })
        assertEquals(3, book.entries.count { it.exclusiveGroup == "zm_platform_mode" })
        assertEquals(5, evaluate(book, listOf(UIMessage.user("unmatched"))).injections.size)
        assertTrue(book.importWarnings.none { it.contains("扫描深度") || it.contains("已跳过") })
        assertFalse(book.importWarnings.any { it.contains("useGroupScoring") })
    }
    @Test fun `native round trip preserves scan choices`() {
        val book = Lorebook(defaultScanDepth = 0, entries = listOf(rule().copy(scanMode = LorebookScanMode.INHERIT), rule().copy(scanDepth = 0)))
        val json = me.rerere.rikkahub.utils.JsonInstant
        val restored = json.decodeFromString(Lorebook.serializer(), json.encodeToString(Lorebook.serializer(), book))
        assertEquals(book, restored)
    }
    @Test fun `zero depth still honors valid sticky state without keyword matching`() {
        val rule = rule().copy(scanDepth = 0)
        val book = Lorebook(entries = listOf(rule))
        val state = LorebookEntryRuntimeState(lastTriggeredTurn = 1, activeUntilTurn = 3, cooldownUntilTurn = 3, ruleFingerprint = rule.ruleFingerprint())
        val result = evaluateInjections(listOf(UIMessage.user("plain")), Assistant(lorebookIds = setOf(book.id)), emptyList(), listOf(book),
            runtimeStates = mapOf(rule.id to state), currentUserTurn = 2, entertainmentMode = true)
        assertEquals(LorebookEntryStatus.ACTIVE_FROM_PREVIOUS_TURN, result.diagnostics.entries.single().status)
    }

    @Test fun `include names uses participant names instead of generic roles`() {
        val rule = rule().copy(keywords = listOf("Alice"), scanDepth = 1)
        val book = Lorebook(entries = listOf(rule), includeNames = true)
        val result = evaluateInjections(
            listOf(UIMessage.user("hello")),
            Assistant(name = "Bob", lorebookIds = setOf(book.id)),
            emptyList(), listOf(book), entertainmentMode = true,
            userName = "Alice", assistantName = "Bob",
        )
        assertEquals(1, result.injections.size)
    }

    @Test fun `imported book matching defaults apply when entry omits overrides`() {
        val book = decodeTavernLorebook(Json.parseToJsonElement("""
            {"case_sensitive":true,"match_whole_words":true,"entries":[{"key":["Cat"],"content":"x"}]}
        """.trimIndent()).jsonObject, "B")
        assertTrue(evaluateInjections(listOf(UIMessage.user("cat")), Assistant(lorebookIds = setOf(book.id)), emptyList(), listOf(book), entertainmentMode = true).injections.isEmpty())
        assertEquals(1, evaluateInjections(listOf(UIMessage.user("Cat")), Assistant(lorebookIds = setOf(book.id)), emptyList(), listOf(book), entertainmentMode = true).injections.size)
    }
}
