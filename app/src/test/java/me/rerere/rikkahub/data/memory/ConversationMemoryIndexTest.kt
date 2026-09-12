package me.rerere.rikkahub.data.memory

import me.rerere.ai.ui.UIMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationMemoryIndexTest {
    @Test
    fun `groups each user and assistant pair into a searchable turn`() {
        val firstUser = UIMessage.user("My preferred editor is Vim")
        val messages = listOf(
            firstUser,
            UIMessage.assistant("I will remember that."),
            UIMessage.user("The project ships Friday"),
            UIMessage.assistant("Understood."),
        )

        val drafts = buildConversationMemoryDrafts("conversation", "assistant", messages)

        assertEquals(2, drafts.size)
        assertEquals(firstUser.id.toString(), drafts.first().sourceMessageId)
        assertTrue(drafts.first().content.contains("User:\nMy preferred editor is Vim"))
        assertTrue(drafts.first().content.contains("Assistant:\nI will remember that."))
        assertEquals(listOf(0, 1000), drafts.map { it.ordinal })
    }

    @Test
    fun `splits long turns with overlap and stable source identity`() {
        val user = UIMessage.user("x".repeat(6_000))

        val drafts = buildConversationMemoryDrafts(
            conversationId = "conversation",
            assistantId = "assistant",
            messages = listOf(user, UIMessage.assistant("done")),
        )

        assertTrue(drafts.size >= 3)
        assertTrue(drafts.all { it.sourceMessageId == user.id.toString() })
        assertEquals(drafts.map { it.id }.distinct().size, drafts.size)
        assertTrue(drafts.zipWithNext().all { (left, right) ->
            left.content.takeLast(200) == right.content.take(200)
        })
    }

    @Test
    fun `editing a message changes its hash without changing chunk identity`() {
        val user = UIMessage.user("Original preference")
        val original = buildConversationMemoryDrafts(
            "conversation",
            "assistant",
            listOf(user, UIMessage.assistant("Noted")),
        ).single()
        val edited = buildConversationMemoryDrafts(
            "conversation",
            "assistant",
            listOf(user.copy(parts = UIMessage.user("Updated preference").parts), UIMessage.assistant("Noted")),
        ).single()

        assertEquals(original.id, edited.id)
        assertNotEquals(original.contentHash, edited.contentHash)
    }

    @Test
    fun `merges the active branch while reusing matching stored vectors`() {
        val sharedUser = UIMessage.user("Shared turn")
        val oldBranchUser = UIMessage.user("Old branch only")
        val newBranchUser = UIMessage.user("New branch only")
        val oldDrafts = buildConversationMemoryDrafts(
            "conversation",
            "assistant",
            listOf(
                sharedUser,
                UIMessage.assistant("Shared answer"),
                oldBranchUser,
                UIMessage.assistant("Old answer"),
            ),
        )
        val stored = oldDrafts.mapIndexed { index, draft ->
            draft.copy(
                embedding = byteArrayOf(index.toByte(), 42),
                embeddingModelId = "embedding-model",
                embeddingDimension = 2,
            )
        }
        val activeDrafts = buildConversationMemoryDrafts(
            "conversation",
            "assistant",
            listOf(
                sharedUser,
                UIMessage.assistant("Shared answer"),
                newBranchUser,
                UIMessage.assistant("New answer"),
            ),
        )

        val merged = mergeConversationMemoryEntities(stored, activeDrafts)

        assertEquals(activeDrafts.map { it.id }, merged.map { it.id })
        assertTrue(merged.first().embedding!!.contentEquals(stored.first().embedding!!))
        assertNull(merged.last().embedding)
        assertTrue(merged.none { it.sourceMessageId == oldBranchUser.id.toString() })
    }

    @Test
    fun `edited active content never reuses a stale vector`() {
        val user = UIMessage.user("Original preference")
        val stored = buildConversationMemoryDrafts(
            "conversation",
            "assistant",
            listOf(user, UIMessage.assistant("Original answer")),
        ).single().copy(
            embedding = byteArrayOf(1, 2, 3, 4),
            embeddingModelId = "embedding-model",
            embeddingDimension = 1,
        )
        val edited = buildConversationMemoryDrafts(
            "conversation",
            "assistant",
            listOf(user, UIMessage.assistant("Edited answer")),
        ).single()

        val merged = mergeConversationMemoryEntities(listOf(stored), listOf(edited)).single()

        assertEquals(edited.contentHash, merged.contentHash)
        assertNull(merged.embedding)
        assertNull(merged.embeddingModelId)
    }
}
