package me.rerere.rikkahub.data.model

import kotlinx.datetime.LocalDateTime
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantPromptSupportTest {
    @Test
    fun `preset messages receive conversation-local identity and runtime state`() {
        val preset = UIMessage.user("hello").copy(
            finishedAt = LocalDateTime(2025, 1, 2, 3, 4, 5),
            usage = TokenUsage(promptTokens = 1, completionTokens = 2),
            translation = "translated",
            interrupted = true,
        )
        val conversationTime = LocalDateTime(2026, 9, 2, 10, 11, 12)

        val instantiated = instantiatePresetMessages(listOf(preset), conversationTime).single()

        assertNotEquals(preset.id, instantiated.id)
        assertEquals(conversationTime, instantiated.createdAt)
        assertEquals(MessageRole.USER, instantiated.role)
        assertEquals("hello", instantiated.toText())
        assertNull(instantiated.finishedAt)
        assertNull(instantiated.modelId)
        assertNull(instantiated.usage)
        assertNull(instantiated.translation)
        assertFalse(instantiated.interrupted)
    }

    @Test
    fun `prompt merge cannot overwrite unrelated assistant settings`() {
        val current = Assistant(name = "Current", temperature = 0.4f)
        val draft = current.copy(
            name = "Stale name",
            temperature = 1.2f,
            systemPrompt = "New prompt",
            messageTemplate = "[{{ message }}]",
        )

        val merged = current.withPromptSettingsFrom(draft)

        assertEquals("Current", merged.name)
        assertEquals(0.4f, merged.temperature)
        assertEquals("New prompt", merged.systemPrompt)
        assertEquals("[{{ message }}]", merged.messageTemplate)
    }

    @Test
    fun `regex validation accepts edge whitespace and rejects bad replacement group`() {
        assertTrue(
            validateAssistantRegex(
                AssistantRegex(
                    id = kotlin.uuid.Uuid.random(),
                    findRegex = " ^ value $ ",
                    replaceString = "ok",
                )
            ).isValid
        )
        assertFalse(
            validateAssistantRegex(
                AssistantRegex(
                    id = kotlin.uuid.Uuid.random(),
                    findRegex = "(value)",
                    replaceString = "\$2",
                )
            ).isValid
        )
    }

    @Test
    fun `preset normalization repairs duplicate ids and skips unfinished messages`() {
        val duplicateId = kotlin.uuid.Uuid.random()
        val normalized = normalizePresetMessages(
            listOf(
                UIMessage.user("ready").copy(id = duplicateId),
                UIMessage.assistant("").copy(id = duplicateId),
            )
        )

        assertEquals(2, normalized.size)
        assertNotEquals(normalized[0].id, normalized[1].id)
        assertEquals(1, instantiatePresetMessages(normalized).size)
        assertEquals("ready", instantiatePresetMessages(normalized).single().toText())
    }

    @Test
    fun `editing preset text retains non-text parts`() {
        val message = UIMessage.user("old").copy(
            parts = listOf(
                UIMessagePart.Text("old"),
                UIMessagePart.Image("content://image"),
            )
        )

        val edited = message.withPresetText("new")

        assertEquals("new", (edited.parts[0] as UIMessagePart.Text).text)
        assertEquals("content://image", (edited.parts[1] as UIMessagePart.Image).url)
    }

    @Test
    fun `inline flags remain valid and regex preview reports matches`() {
        val regex = AssistantRegex(
            id = kotlin.uuid.Uuid.random(),
            findRegex = "(?i)secret",
            replaceString = "[hidden]",
        )

        assertTrue(validateAssistantRegex(regex).isValid)
        val result = testAssistantRegex(regex, "SECRET value")
        assertEquals("[hidden] value", result.output)
        assertEquals(1, result.matchCount)
    }

    @Test
    fun `named replacement groups are accepted`() {
        val regex = AssistantRegex(
            id = kotlin.uuid.Uuid.random(),
            findRegex = "(?<word>secret)",
            replaceString = "[\u0024{word}]",
        )

        assertTrue(validateAssistantRegex(regex).isValid)
        assertEquals("[secret]", testAssistantRegex(regex, "secret").output)
    }

    @Test
    fun `numbered replacement follows java group prefix rules`() {
        val regex = AssistantRegex(
            id = kotlin.uuid.Uuid.random(),
            findRegex = "(x)",
            replaceString = "\u0024{not-a-group}",
        )
        // A malformed named reference is rejected even when the expression
        // itself has a valid numbered capture.
        assertFalse(validateAssistantRegex(regex).isValid)

        val prefixed = regex.copy(replaceString = "\u002412")
        assertTrue(validateAssistantRegex(prefixed).isValid)
        assertEquals("x2", testAssistantRegex(prefixed, "x").output)
    }

    @Test
    fun `regex normalization repairs duplicate ids without reordering rules`() {
        val duplicateId = kotlin.uuid.Uuid.random()
        val normalized = normalizeAssistantRegexes(
            listOf(
                AssistantRegex(id = duplicateId, name = "first"),
                AssistantRegex(id = duplicateId, name = "second"),
            )
        )

        assertEquals(listOf("first", "second"), normalized.map { it.name })
        assertEquals(2, normalized.map { it.id }.toSet().size)
    }
}
