package me.rerere.rikkahub.data.model

import me.rerere.rikkahub.data.datastore.*
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Test

class InspirationCardsTest {
    private val cards = (1..8).map { InspirationCard("builtin:$it", "Card $it", "Prompt $it", InspirationAudience.NORMAL) }

    @Test fun `old settings inherit four cards without changing suggestions or other preferences`() {
        val assistant = JsonInstant.decodeFromString<Assistant>("""{"name":"Existing"}""")
        val display = JsonInstant.decodeFromString<DisplaySetting>("{}")
        assertNull(assistant.inspirationSettings)
        assertEquals(4, display.inspirationSettings.cardCount)
        assertFalse(display.showInspirationCards)
    }

    @Test fun `assistant changes and reset do not change global defaults or another assistant`() {
        val first = Assistant(name = "A")
        val second = Assistant(name = "B")
        val settings = Settings(assistants = listOf(first, second), enableSuggestion = false)
        val changed = settings.withInspirationSettings(first.id) { it.copy(cardCount = 2).togglePin("builtin:1") }
        assertEquals(2, changed.inspirationSettings(first.id).cardCount)
        assertEquals(4, changed.inspirationSettings(second.id).cardCount)
        assertEquals(settings.displaySetting, changed.displaySetting)
        assertFalse(changed.enableSuggestion)
        assertEquals(second, changed.assistants.last())
        val restored = changed.copy(assistants = changed.assistants.map { it.copy(inspirationSettings = null) })
        assertEquals(settings.inspirationSettings(null), restored.inspirationSettings(first.id))
        val encoded = JsonInstant.encodeToString(Assistant.serializer(), changed.assistants.first())
        assertEquals(changed.assistants.first(), JsonInstant.decodeFromString<Assistant>(encoded))
    }

    @Test fun `mode selection includes matching custom cards and can omit builtins`() {
        val custom = InspirationCard("custom:all", "All", "All")
        val story = InspirationCard("custom:story", "Story", "Story", InspirationAudience.ENTERTAINMENT)
        val config = InspirationSettings(customCards = listOf(custom, story))
        assertEquals(listOf(custom, story), inspirationPool(cards, config, ExtensionManagementMode.ENTERTAINMENT))
        assertEquals(cards + custom, inspirationPool(cards, config, ExtensionManagementMode.NORMAL))
        assertEquals(listOf(custom), inspirationPool(cards, config.copy(includeBuiltIns = false), ExtensionManagementMode.NORMAL))
    }

    @Test fun `shuffling retains pinned cards in order and rotates remaining slots`() {
        val config = InspirationSettings(pinnedIds = listOf("builtin:3", "builtin:1"))
        val first = inspirationBatch(cards, config, 42, 0)
        val second = inspirationBatch(cards, config, 42, 1)
        assertEquals(listOf("builtin:3", "builtin:1"), first.take(2).map { it.id })
        assertEquals(first.take(2), second.take(2))
        assertTrue(first.drop(2).intersect(second.drop(2).toSet()).isEmpty())
        assertEquals(first, inspirationBatch(cards, config, 42, 0))
        assertEquals(4, second.distinctBy { it.id }.size)
    }

    @Test fun `invalid counts missing pins and deleted cards remain bounded`() {
        val config = InspirationSettings(cardCount = 999, pinnedIds = listOf("missing", "builtin:1", "builtin:1"))
        assertEquals(8, inspirationBatch(cards, config, 0, Int.MAX_VALUE).size)
        assertTrue(inspirationBatch(emptyList(), config, 0, 1).isEmpty())
        val pinned = InspirationSettings(cardCount = 1, pinnedIds = cards.map { it.id })
        assertEquals(listOf(cards.first()), inspirationBatch(cards, pinned, 0, 2))
        val custom = InspirationCard("custom:1", "One", "Prompt")
        val removed = InspirationSettings(customCards = listOf(custom), pinnedIds = listOf(custom.id)).removeCard(custom.id)
        assertTrue(removed.customCards.isEmpty())
        assertTrue(removed.pinnedIds.isEmpty())
    }

    @Test fun `automatic insertion never overwrites a draft and explicit replacement detects concurrent edits`() {
        assertNull(inspirationDraftEdit("draft", "new", InspirationInsert.AUTO))
        assertEquals("new", inspirationDraftEdit("", "new", InspirationInsert.AUTO)?.after)
        assertEquals("draft\nnew", inspirationDraftEdit("draft", "new", InspirationInsert.APPEND)?.after)
        assertEquals("new", inspirationDraftEdit("draft", "new", InspirationInsert.REPLACE, "draft")?.after)
        assertNull(inspirationDraftEdit("newer draft", "new", InspirationInsert.REPLACE, "draft"))
        assertNull(inspirationDraftEdit("draft", " ", InspirationInsert.APPEND))
    }

    @Test fun `variables use quick-message substitution and keep input literal`() {
        val assistant = Assistant(name = "角色")
        val card = InspirationCard(prompt = "{{char_name}} 在 {{地点}}，{{地点}}")
        assertEquals(listOf("char_name", "地点"), card.template().placeholderNames())
        assertEquals("角色 在 $5\\test，$5\\test", card.template().render(
            automaticQuickMessageValues(Settings(), assistant) + ("地点" to "$5\\test")))
    }

    @Test fun `layout handles narrow screens and large fonts and imported appearance values are safe`() {
        assertEquals(1, inspirationColumns(320f, 1f))
        assertEquals(1, inspirationColumns(800f, 1.5f))
        assertEquals(2, inspirationColumns(600f, 1f))
        assertEquals(3, inspirationColumns(900f, 1f))
        val normalized = InspirationAppearance(false, Float.NaN, Float.POSITIVE_INFINITY, -1f).normalized()
        assertEquals(.62f, normalized.surfaceOpacity, 0f)
        assertEquals(.34f, normalized.borderOpacity, 0f)
        assertEquals(0f, normalized.cornerRadius, 0f)
    }
}
