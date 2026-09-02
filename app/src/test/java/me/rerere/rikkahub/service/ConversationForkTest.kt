package me.rerere.rikkahub.service

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.LorebookEntryRuntimeState
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.uuid.Uuid

class ConversationForkTest {
    @Test
    fun `fork selects requested variant and rebases conversation runtime state`() = runBlocking {
        val selectedUser = UIMessage.user("selected")
        val targetVariant = UIMessage.user("target")
        val targetNode = MessageNode(
            messages = listOf(selectedUser, targetVariant),
            selectIndex = 0,
            isFavorite = true,
        )
        val runtimeEntryId = Uuid.random()
        val temporaryModeId = Uuid.random()
        val branchedAt = Instant.parse("2026-09-01T10:15:30Z")
        val source = Conversation(
            assistantId = Uuid.random(),
            title = "Source conversation",
            messageNodes = listOf(
                targetNode,
                MessageNode.of(UIMessage.assistant("answer")),
                MessageNode.of(UIMessage.user("later turn")),
            ),
            temporaryModeInjections = mapOf(temporaryModeId to 4),
            lorebookRuntimeStates = mapOf(
                runtimeEntryId to LorebookEntryRuntimeState(activeUntilTurn = 5),
            ),
        )

        val fork = createForkConversationSnapshot(
            currentConversation = source,
            messageId = targetVariant.id,
            branchedAt = branchedAt,
        ) { it }

        assertEquals(1, fork.messageNodes.size)
        assertEquals(1, fork.messageNodes.single().messages.size)
        assertEquals(0, fork.messageNodes.single().selectIndex)
        assertEquals(targetVariant.id, fork.messageNodes.single().currentMessage.id)
        assertNotEquals(targetNode.id, fork.messageNodes.single().id)
        assertFalse(fork.messageNodes.single().isFavorite)
        assertEquals(mapOf(temporaryModeId to 3), fork.temporaryModeInjections)
        assertTrue(fork.lorebookRuntimeStates.isEmpty())
        assertEquals(source.id, fork.sourceConversationId)
        assertEquals(targetVariant.id, fork.sourceMessageId)
        assertEquals(branchedAt, fork.branchedAt)
        assertEquals(branchedAt, fork.createAt)
        assertEquals(branchedAt, fork.updateAt)
        assertEquals(source.title, fork.sourceConversationTitle)
    }

    @Test
    fun `fork copies only the visible path and truncates later nodes`() = runBlocking {
        val hiddenFirst = UIMessage.user("hidden first")
        val visibleFirst = UIMessage.user("visible first")
        val target = UIMessage.assistant("visible target")
        val hiddenTarget = UIMessage.assistant("hidden target")
        val later = UIMessage.user("later")
        val source = Conversation(
            assistantId = Uuid.random(),
            messageNodes = listOf(
                MessageNode(
                    messages = listOf(hiddenFirst, visibleFirst),
                    selectIndex = 1,
                ),
                MessageNode(
                    messages = listOf(target, hiddenTarget),
                    selectIndex = 0,
                ),
                MessageNode.of(later),
            ),
        )
        val copiedText = mutableListOf<String>()

        val fork = createForkConversationSnapshot(source, target.id) { part ->
            if (part is UIMessagePart.Text) copiedText += part.text
            part
        }

        assertEquals(listOf(visibleFirst.id, target.id), fork.currentMessages.map { it.id })
        assertTrue(fork.messageNodes.all { it.messages.size == 1 && it.selectIndex == 0 })
        assertEquals(listOf("visible first", "visible target"), copiedText)
        assertTrue(fork.currentMessages.none { it.id == later.id })

        val editedFork = fork.copy(
            messageNodes = fork.messageNodes.dropLast(1) +
                MessageNode.of(UIMessage.assistant("fork edit")),
        )
        val editedSource = source.copy(messageNodes = source.messageNodes.dropLast(1))
        assertEquals(
            "visible target",
            (source.currentMessages[1].parts.single() as UIMessagePart.Text).text,
        )
        assertEquals(
            "visible target",
            (fork.currentMessages[1].parts.single() as UIMessagePart.Text).text,
        )
        assertEquals(
            "fork edit",
            (editedFork.currentMessages[1].parts.single() as UIMessagePart.Text).text,
        )
        assertEquals(2, editedSource.currentMessages.size)
        assertEquals(3, source.currentMessages.size)
    }

    @Test
    fun `fork attachment copy descends into tool output`() = runBlocking {
        val part = UIMessagePart.Tool(
            toolCallId = "tool-1",
            toolName = "read_image",
            input = "{}",
            output = listOf(
                UIMessagePart.Image("file:///old/image.png"),
                UIMessagePart.Document("https://example.com/doc.pdf", "doc.pdf"),
            ),
        )

        val copied = part.copyForFork { url ->
            if (url.startsWith("file:")) "file:///new/image.png" else url
        } as UIMessagePart.Tool

        assertEquals("file:///new/image.png", (copied.output[0] as UIMessagePart.Image).url)
        assertEquals(
            "https://example.com/doc.pdf",
            (copied.output[1] as UIMessagePart.Document).url,
        )
    }

    @Test
    fun `fork keeps stale local attachment reference when it cannot be copied`() = runBlocking {
        val staleUrl = "file:///data/user/0/me.rerere.rikkahub/files/upload/missing.png"

        assertEquals(
            staleUrl,
            copyForkAttachmentUrl(staleUrl) { null },
        )
        assertEquals(
            "https://example.com/image.png",
            copyForkAttachmentUrl("https://example.com/image.png") {
                error("Remote URLs must not be copied")
            },
        )
    }

    @Test
    fun `legacy conversation json defaults branch metadata`() {
        val source = Conversation(
            assistantId = Uuid.random(),
            messageNodes = emptyList(),
            sourceConversationId = Uuid.random(),
            sourceMessageId = Uuid.random(),
            branchedAt = Instant.parse("2026-09-01T10:15:30Z"),
            sourceConversationTitle = "Source conversation",
        )
        val legacyFields = JsonInstant
            .encodeToJsonElement(Conversation.serializer(), source)
            .jsonObject
            .filterKeys { key ->
                key !in setOf(
                    "sourceConversationId",
                    "sourceMessageId",
                    "branchedAt",
                    "sourceConversationTitle",
                )
            }

        val restored = JsonInstant.decodeFromJsonElement(
            Conversation.serializer(),
            JsonObject(legacyFields),
        )

        assertNull(restored.sourceConversationId)
        assertNull(restored.sourceMessageId)
        assertNull(restored.branchedAt)
        assertNull(restored.sourceConversationTitle)
    }
}
