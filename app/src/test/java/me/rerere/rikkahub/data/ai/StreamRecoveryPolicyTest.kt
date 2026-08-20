package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ServerToolStatus
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamRecoveryPolicyTest {
    @Test
    fun `plain text interruption creates a provider-only continuation request`() {
        val request = listOf(UIMessage.user("Explain the result"))
        val partial = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Reasoning("internal reasoning"),
                UIMessagePart.Text("The result is "),
                UIMessagePart.Text("42"),
            ),
        )
        val current = request + partial

        val continuation = buildInterruptedStreamContinuation(
            requestMessages = request,
            currentMessages = current,
            hasClientTools = false,
            hasServerTools = false,
        )!!

        assertEquals(request.size + 2, continuation.size)
        assertEquals("The result is 42", continuation[continuation.lastIndex - 1].toText())
        assertEquals(1, continuation[continuation.lastIndex - 1].parts.size)
        assertTrue(continuation.last().toText().contains("Do not repeat"))
        assertSame(partial, current.last())
        assertEquals(request.size + 1, current.size)
    }

    @Test
    fun `tool and media output never creates a continuation request`() {
        val request = listOf(UIMessage.user("continue"))
        val unsafeParts = listOf<UIMessagePart>(
            UIMessagePart.Image("data:image/png;base64,AA=="),
            UIMessagePart.Tool("call", "lookup", "{}"),
            UIMessagePart.ServerTool(
                toolCallId = "server-call",
                toolName = "search",
                output = JsonPrimitive("result"),
                status = ServerToolStatus.COMPLETED,
            ),
        )

        unsafeParts.forEach { unsafePart ->
            val current = request + UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(UIMessagePart.Text("partial"), unsafePart),
            )
            assertNull(
                buildInterruptedStreamContinuation(request, current, false, false)
            )
        }
        assertNull(
            buildInterruptedStreamContinuation(
                request,
                request + UIMessage.assistant("partial"),
                hasClientTools = true,
                hasServerTools = false,
            )
        )
        assertNull(
            buildInterruptedStreamContinuation(
                request,
                request + UIMessage.assistant("partial"),
                hasClientTools = false,
                hasServerTools = true,
            )
        )
    }

    @Test
    fun `adjacent continuation text is merged without changing non-text parts`() {
        val reasoning = UIMessagePart.Reasoning("reasoning")
        val messages = listOf(
            UIMessage.user("question"),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(reasoning, UIMessagePart.Text("first"), UIMessagePart.Text(" second")),
            ),
        )

        val merged = messages.mergeLastAssistantTextParts()

        assertEquals(listOf(reasoning, UIMessagePart.Text("first second")), merged.last().parts)
    }
}
