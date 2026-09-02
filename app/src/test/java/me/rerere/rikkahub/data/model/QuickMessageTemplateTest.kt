package me.rerere.rikkahub.data.model

import kotlin.uuid.Uuid
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.datastore.ExtensionManagementMode
import me.rerere.rikkahub.data.datastore.QuickMessageSortMode
import me.rerere.rikkahub.data.datastore.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickMessageTemplateTest {
    @Test
    fun `extracts distinct placeholders and renders supplied values`() {
        val message = QuickMessage(
            content = "{{角色名}} 在 {{ 地点 }} 寻找 {{角色名}}和{{目标}}"
        )

        assertEquals(listOf("角色名", "地点", "目标"), message.placeholderNames())
        assertEquals(
            "Alice 在 Station 寻找 Alice和Key",
            message.render(mapOf("角色名" to "Alice", "地点" to "Station", "目标" to "Key")),
        )
    }

    @Test
    fun `missing placeholder values remain visible`() {
        val message = QuickMessage(content = "Go to {{location}} with {{target}}")
        assertEquals(
            "Go to Harbor with {{target}}",
            message.render(mapOf("location" to "Harbor")),
        )
    }

    @Test
    fun `automatic values resolve assistant user and roleplay aliases`() {
        val values = automaticQuickMessageValues(
            settings = Settings(
                displaySetting = DisplaySetting(userNickname = "Mina"),
                extensionManagementMode = ExtensionManagementMode.ENTERTAINMENT,
            ),
            assistant = Assistant(name = "Ari"),
        )

        assertEquals("Ari", values["assistant_name"])
        assertEquals("Ari", values["char_name"])
        assertEquals("Ari", values["character_name"])
        assertEquals("Mina", values["user_name"])
        assertEquals("Mina", values["player_name"])
        assertEquals("true", values["roleplay_mode"])
    }

    @Test
    fun `search covers category and tags`() {
        val message = QuickMessage(
            title = "Greeting",
            content = "Hello",
            category = "Dialogue",
            tags = listOf("Roleplay", "Friendly"),
        )
        assertTrue(message.matchesQuery("dialogue"))
        assertTrue(message.matchesQuery("ROLEPLAY"))
        assertFalse(message.matchesQuery("combat"))
    }

    @Test
    fun `favorites stay first while recent and frequent modes use statistics`() {
        val favorite = QuickMessage(title = "Favorite", favorite = true)
        val frequent = QuickMessage(title = "Frequent", useCount = 10, lastUsedAt = 1)
        val recent = QuickMessage(title = "Recent", useCount = 1, lastUsedAt = 20)
        val messages = listOf(recent, favorite, frequent)

        assertEquals(
            listOf("Favorite", "Recent", "Frequent"),
            messages.sortedForDisplay(QuickMessageSortMode.RECENT).map { it.title },
        )
        assertEquals(
            listOf("Favorite", "Frequent", "Recent"),
            messages.sortedForDisplay(QuickMessageSortMode.FREQUENT).map { it.title },
        )
    }

    @Test
    fun `upserting a group keeps message membership exclusive`() {
        val firstId = Uuid.random()
        val secondId = Uuid.random()
        val firstGroup = QuickMessageGroup(name = "Actions", quickMessageIds = setOf(firstId, secondId))
        val secondGroup = QuickMessageGroup(name = "Dialogue")
        val assistant = Assistant(
            quickMessageIds = setOf(firstId, secondId),
            quickMessageGroups = listOf(firstGroup, secondGroup),
        )

        val updated = assistant.upsertQuickMessageGroup(
            secondGroup.copy(quickMessageIds = setOf(secondId))
        )

        assertEquals(setOf(firstId), updated.quickMessageGroups[0].quickMessageIds)
        assertEquals(setOf(secondId), updated.quickMessageGroups[1].quickMessageIds)
        assertEquals(
            emptySet<Uuid>(),
            updated.withQuickMessageIds(setOf(firstId)).quickMessageGroups[1].quickMessageIds,
        )
    }

    @Test
    fun `tag normalization trims removes blanks and ignores case duplicates`() {
        assertEquals(
            listOf("Action", "dialogue"),
            normalizeQuickMessageTags(listOf(" Action ", "", "action", "dialogue")),
        )
    }
}
