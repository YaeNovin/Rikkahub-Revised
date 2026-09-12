package me.rerere.rikkahub.data.model

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.transformers.evaluateInjections
import me.rerere.rikkahub.data.export.decodeTavernLorebook
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class LorebookUpgradeTest {
    private fun message(text: String, role: MessageRole = MessageRole.USER) = UIMessage(role = role, parts = listOf(UIMessagePart.Text(text)))
    private fun entry() = PromptInjection.RegexInjection(name = "Scene", content = "hello", keywords = listOf("forest"))

    @Test fun `quoted operators and escaped quotes match literally`() {
        val rule = entry().copy(keywordExpression = "\"NOT\" AND \"AND\" AND ${quoteLorebookKeyword("a\"b")}")
        assertTrue(rule.evaluateKeywords("NOT AND a\"b").matched)
        assertNull(rule.evaluateKeywords("NOT AND a\"b").error)
    }
    @Test fun `invalid regex in NOT cannot silently activate an entry`() {
        val result = entry().copy(useRegex = true, keywordExpression = "NOT \"[\"").evaluateKeywords("hello")
        assertFalse(result.matched); assertNotNull(result.error)
    }
    @Test fun `negative scan depths cannot crash legacy matching`() {
        assertEquals("forest", extractContextForMatching(listOf(message("forest")), -1))
        assertTrue(entry().copy(scanDepth = -1).validationErrors(false).isNotEmpty())
    }
    @Test fun `scan source excludes assistant replies`() {
        val messages = listOf(message("plain"), message("forest", MessageRole.ASSISTANT))
        assertFalse(entry().evaluateKeywords(lorebookScanText(messages, 4, LorebookScanSource.USER)).matched)
    }
    @Test fun `edited rule invalidates sticky state`() {
        val old = entry().copy(stickyTurns = 5)
        val book = Lorebook(entries = listOf(old)); val assistant = Assistant(lorebookIds = setOf(book.id))
        val messages = listOf(message("forest"))
        val first = evaluateInjections(messages, assistant, emptyList(), listOf(book), entertainmentMode = true)
        val changed = book.copy(entries = listOf(old.copy(keywords = listOf("ocean"))))
        val next = evaluateInjections(messages + message("plain"), assistant, emptyList(), listOf(changed), runtimeStates = first.runtimeStates, entertainmentMode = true)
        assertTrue(next.injections.isEmpty())
    }
    @Test fun `conversation seeds are deterministic and independent`() {
        val id = entry().id
        assertEquals(passesDeterministicProbability(id, 2, 50, "a"), passesDeterministicProbability(id, 2, 50, "a"))
        assertTrue((1..100).any { passesDeterministicProbability(id, it, 50, "a") != passesDeterministicProbability(id, it, 50, "b") })
    }
    @Test fun `total budget caps multiple books and does not activate skipped entries`() {
        val books = List(2) { Lorebook(entries = listOf(entry().copy(constantActive = true, content = "a".repeat(100)))) }
        val result = evaluateInjections(listOf(message("x")), Assistant(lorebookIds = books.map { it.id }.toSet()), emptyList(), books, entertainmentMode = true, totalTokenBudget = 1)
        assertTrue(result.injections.isEmpty()); assertTrue(result.runtimeStates.isEmpty())
    }
    @Test fun `exclusive group picks only highest priority without weights`() {
        val first = entry().copy(constantActive = true, exclusiveGroup = "scene", priority = 1)
        val second = first.copy(id = kotlin.uuid.Uuid.random(), priority = 2)
        val book = Lorebook(entries = listOf(first, second))
        val result = evaluateInjections(listOf(message("x")), Assistant(lorebookIds = setOf(book.id)), emptyList(), listOf(book), entertainmentMode = true)
        assertEquals(listOf(second.id), result.injections.map { it.id })
    }
    @Test fun `standalone and embedded entry shapes use same mapping`() {
        val raw = """{"name":"B","entries":[{"keys":["forest"],"secondary_keys":["rain"],"selective":true,"content":"x","position":"after_char","extensions":{"useProbability":true,"probability":25,"sticky":3,"cooldown":2}}]}"""
        val result = decodeTavernLorebook(Json.parseToJsonElement(raw).jsonObject, "B").entries.single()
        assertEquals(25, result.triggerProbability); assertEquals(3, result.stickyTurns); assertEquals(2, result.cooldownTurns)
        assertFalse(result.evaluateKeywords("forest").matched); assertTrue(result.evaluateKeywords("forest rain").matched)
    }
    @Test fun `history is bounded and can restore content`() {
        var book = Lorebook(name = "old", entries = listOf(entry()))
        repeat(12) { book = book.copy(name = "version $it").withRevisionOf(book) }
        assertEquals(10, book.revisions.size)
        assertEquals(book.revisions.first().name, book.restore(book.revisions.first()).name)
    }
    @Test fun `ordinary mode diagnoses malformed regex without throwing`() {
        val book = Lorebook(entries = listOf(entry().copy(useRegex = true, keywords = listOf("["))))
        val result = evaluateInjections(listOf(message("x")), Assistant(lorebookIds = setOf(book.id)), emptyList(), listOf(book))
        assertEquals(LorebookEntryStatus.INVALID_EXPRESSION, result.diagnostics.entries.single().status)
        assertTrue(result.injections.isEmpty())
    }
    @Test fun `editing original user message resets previous activation`() {
        val book = Lorebook(entries = listOf(entry().copy(stickyTurns = 5, scanDepth = 1)))
        val assistant = Assistant(lorebookIds = setOf(book.id))
        val original = message("forest")
        val first = evaluateInjections(listOf(original), assistant, emptyList(), listOf(book), entertainmentMode = true)
        val edited = original.copy(parts = listOf(UIMessagePart.Text("plain")))
        val second = evaluateInjections(listOf(edited, message("plain")), assistant, emptyList(), listOf(book), runtimeStates = first.runtimeStates, entertainmentMode = true)
        assertTrue(second.injections.isEmpty())
    }
    @Test fun `concurrent different entry edits are both preserved`() {
        val first = entry(); val second = entry()
        val base = Lorebook(entries = listOf(first, second))
        val edited = base.copy(entries = listOf(first.copy(content = "edited"), second))
        val current = base.copy(entries = listOf(first, second.copy(content = "new")))
        val merged = me.rerere.rikkahub.ui.pages.extensions.mergeLorebookEdits(base, edited, current)
        assertEquals(listOf("edited", "new"), merged.entries.map { it.content })
    }
    @Test fun `rule fingerprint survives serialization and detects changes`() {
        val rule = entry()
        val json = me.rerere.rikkahub.utils.JsonInstant
        val restored = json.decodeFromString(PromptInjection.RegexInjection.serializer(), json.encodeToString(PromptInjection.RegexInjection.serializer(), rule))
        assertEquals(rule.ruleFingerprint(), restored.ruleFingerprint())
        assertNotEquals(rule.ruleFingerprint(), restored.copy(stickyTurns = 2).ruleFingerprint())
    }
    @Test fun `export excludes historical contents`() {
        val old = Lorebook(name = "B", entries = listOf(entry().copy(content = "private historical content")))
        val current = old.copy(entries = listOf(entry().copy(content = "public"))).withRevisionOf(old)
        val exported = me.rerere.rikkahub.data.export.LorebookSerializer.export(current).data.toString()
        assertFalse(exported.contains("private historical content"))
    }
}
