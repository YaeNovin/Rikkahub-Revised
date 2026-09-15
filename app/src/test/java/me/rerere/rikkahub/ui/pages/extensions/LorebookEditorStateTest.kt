package me.rerere.rikkahub.ui.pages.extensions

import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.ai.transformers.evaluateInjections
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.*
import org.junit.Assert.*
import org.junit.Test

class LorebookEditorStateTest {
    private fun entry() = PromptInjection.RegexInjection(name = "Scene", content = "Original lore\n\\n 原文", keywords = listOf("Star Port", "/foo,bar/i"))

    @Test fun `draft keeps separators and untouched content until explicit save`() {
        val original = entry().copy(exclusiveGroup = "legacy")
        val draft = LorebookEntryDraft(original)
        assertFalse(draft.dirty)
        draft.groups = "alpha, "
        draft.characters = "Alice, Bob, "
        draft.characterTags = "story， 日常\nwork"
        assertEquals("alpha, ", draft.groups)
        assertEquals(listOf("alpha"), draft.candidate().inclusionGroups)
        assertEquals(listOf("Alice", "Bob"), draft.candidate().characterFilter)
        assertEquals(listOf("story", "日常", "work"), draft.candidate().characterFilterTags)
        assertEquals(original.content, draft.candidate().content)
        assertEquals(original.keywords, draft.candidate().keywords)
        assertEquals(listOf("Star Port", "/foo,bar/i"), original.keywords)
    }

    @Test fun `numeric drafts allow clear and negative priority without mutating prior value`() {
        val draft = LorebookEntryDraft(entry())
        draft.numbers["优先级"] = "-"
        draft.numbers["递归级别"] = ""
        assertEquals(2, draft.numberErrors(true).size)
        assertTrue(draft.dirty)
        draft.numbers["优先级"] = "-10"
        draft.numbers["递归级别"] = "2"
        draft.numbers["插入顺序"] = ""
        assertTrue(draft.numberErrors(true).isEmpty())
        assertEquals(-10, draft.candidate().priority)
        assertEquals(2, draft.candidate().recursionLevel)
        assertNull(draft.candidate().insertionOrder)
    }

    @Test fun `condition helper quotes operators and preserves regex quantifier commas`() {
        val expression = buildLorebookExpression("Star Port", "/a{1,3}/i\nOR", "dream")
        val rule = entry().copy(sourceFormat = LorebookSourceFormat.SILLY_TAVERN, keywordExpression = expression)
        assertTrue(rule.evaluateKeywords("Star Port AAA").matched)
        assertFalse(rule.evaluateKeywords("Star Port AAA dream").matched)
        assertTrue(expression.contains("a{1,3}"))
        assertTrue(rule.evaluateKeywords("Star Port OR").matched)
    }

    @Test fun `all editable book defaults survive three way merge`() {
        val base = Lorebook(name = "Original")
        val edited = base.copy(recursiveScanning = true, maxRecursionSteps = 4, minActivations = 2, maxScanDepth = 40,
            includeNames = true, useGroupScoring = true, defaultCaseSensitive = true, defaultMatchWholeWords = true,
            alertOnOverflow = true, sourceScope = LorebookSourceScope.CHARACTER)
        assertEquals(edited.copy(description = "concurrent description"), mergeLorebookEdits(base, edited, base.copy(description = "concurrent description")))
        assertTrue(runCatching { mergeLorebookEdits(base, edited, base.copy(maxRecursionSteps = 6)) }.isFailure)
    }

    @Test fun `invalid book number edits require discard even if numeric model is unchanged`() {
        val session = LorebookEditorSession(Settings.dummy(), Lorebook(name = "Book"))
        assertFalse(session.dirty)
        session.maxRecursionSteps = ""
        assertTrue(session.dirty)
        assertFalse(session.validNumbers)
    }

    @Test fun `native and imported explicit matching overrides are honored`() {
        val book = Lorebook(defaultCaseSensitive = false, defaultMatchWholeWords = false)
        val native = entry().copy(keywords = listOf("CAT"), caseSensitive = true)
        assertTrue(native.withLorebookMatchingDefaults(book).caseSensitive)
        assertTrue(evaluateInjections(listOf(UIMessage.user("cat")), Assistant(lorebookIds = setOf(book.id)), emptyList(), listOf(book.copy(entries = listOf(native))), entertainmentMode = true).injections.isEmpty())
        val imported = native.copy(sourceFormat = LorebookSourceFormat.SILLY_TAVERN, sourceData = buildJsonObject {
            put("vendor", "preserved"); putJsonObject("extensions") { put("case_sensitive", true) }
        })
        assertTrue(imported.withLorebookMatchingDefaults(book).caseSensitive)
        val changed = imported.withExplicitLorebookMatching(caseSensitive = false)
        assertFalse(changed.withLorebookMatchingDefaults(book.copy(defaultCaseSensitive = true)).caseSensitive)
        assertEquals(JsonPrimitive("preserved"), changed.sourceData?.get("vendor"))
    }

    @Test fun `V3 regex ignores dormant expression without erasing it`() {
        val rule = entry().copy(sourceFormat = LorebookSourceFormat.CHARACTER_CARD_V3, keywords = listOf("star"), useRegex = true, keywordExpression = "NOT star")
        assertTrue(rule.evaluateKeywords("star").matched)
        assertFalse(rule.copy(useRegex = false).evaluateKeywords("star").matched)
        assertEquals("NOT star", rule.keywordExpression)
    }

    @Test fun `help topics and generation type labels are complete and distinct`() {
        assertEquals(8, lorebookHelpTopics.size)
        assertEquals(lorebookHelpTopics.size, lorebookHelpTopics.map { it.id }.distinct().size)
        assertTrue(lorebookHelpTopics.all { it.title.isNotBlank() && it.steps.isNotEmpty() })
        assertEquals(6, LorebookGenerationTrigger.entries.map(::lorebookTriggerLabel).distinct().size)
    }
}
