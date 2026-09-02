package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ServerToolStatus
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

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
        )!!

        assertEquals(request.size + 1, continuation.size)
        assertEquals(MessageRole.ASSISTANT, continuation.last().role)
        assertEquals("The result is 42", continuation.last().toText())
        assertEquals(1, continuation.last().parts.size)
        assertSame(partial, current.last())
        assertEquals(request.size + 1, current.size)
    }

    @Test
    fun `completed tools are replayed while interrupted tools become notes`() {
        val request = listOf(UIMessage.user("continue"))
        val completedTool = UIMessagePart.Tool(
            toolCallId = "done-call",
            toolName = "lookup",
            input = "{\"q\":\"answer\"}",
            output = listOf(UIMessagePart.Text("done")),
        )
        val interruptedTool = UIMessagePart.Tool(
            toolCallId = "partial-call",
            toolName = "lookup",
            input = "{\"q\":\"incomplete",
            output = listOf(UIMessagePart.Text("{\"status\":\"interrupted\"}")),
            approvalState = ToolApprovalState.Denied("Generation interrupted before tool execution completed"),
        )
        val completedServerTool = UIMessagePart.ServerTool(
            toolCallId = "server-done",
            toolName = "search",
            output = JsonPrimitive("result"),
            status = ServerToolStatus.COMPLETED,
        )
        val interruptedServerTool = UIMessagePart.ServerTool(
            toolCallId = "server-partial",
            toolName = "search",
            input = JsonPrimitive("partial"),
            output = buildJsonObject { put("status", "interrupted") },
            status = ServerToolStatus.FAILED,
        )
        val current = request + UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text("partial"),
                completedTool,
                interruptedTool,
                completedServerTool,
                interruptedServerTool,
                UIMessagePart.Image("data:image/png;base64,AA=="),
            ),
        )

        val continuation = buildInterruptedStreamContinuation(
            requestMessages = current,
            currentMessages = current,
        )!!
        val safeAssistant = continuation.last()

        assertEquals("partial", safeAssistant.parts.filterIsInstance<UIMessagePart.Text>().first().text)
        assertTrue(safeAssistant.parts.any { it == completedTool })
        assertTrue(safeAssistant.parts.any { it == completedServerTool })
        assertTrue(safeAssistant.toText().contains("partial"))
        assertTrue(safeAssistant.parts.none { it == interruptedTool })
        assertTrue(safeAssistant.parts.none { it == interruptedServerTool })
    }

    @Test
    fun `unfinished tool and server tool are marked inert without finishing assistant`() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text("before tool"),
                UIMessagePart.Tool("call", "lookup", "{\"q\":"),
                UIMessagePart.ServerTool(
                    toolCallId = "server-call",
                    toolName = "search",
                    input = JsonPrimitive("partial"),
                    status = ServerToolStatus.IN_PROGRESS,
                ),
            ),
        )

        val marked = message.markInterruptedToolsForContinuation()

        assertNull(marked.finishedAt)
        assertTrue(marked.interrupted)
        val tool = marked.parts.filterIsInstance<UIMessagePart.Tool>().single()
        assertTrue(tool.isExecuted)
        assertEquals(ToolApprovalState.Denied("Generation interrupted before tool execution completed"), tool.approvalState)
        val server = marked.parts.filterIsInstance<UIMessagePart.ServerTool>().single()
        assertEquals(ServerToolStatus.FAILED, server.status)
        assertTrue(server.output.toString().contains("interrupted"))
        assertTrue(marked.isInterruptedAssistantResponse())
    }

    @Test
    fun `pending approval survives generation cancellation`() {
        val pending = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text("before approval"),
                UIMessagePart.Tool(
                    toolCallId = "pending-call",
                    toolName = "workspace_list_local_files",
                    input = "{\"grant_id\":\"grant\"}",
                    approvalState = ToolApprovalState.Pending,
                ),
            ),
        )

        assertEquals(pending, pending.markInterruptedToolsForContinuation())
        val stopped = pending.markInterruptedToolsForContinuation(forcePendingApprovals = true)
        val tool = stopped.parts.filterIsInstance<UIMessagePart.Tool>().single()
        assertTrue(tool.isExecuted)
        assertTrue(tool.approvalState is ToolApprovalState.Denied)
    }

    @Test
    fun `explicit approval is never rewritten as an interruption`() {
        val approved = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = "approved-call",
                    toolName = "workspace_list_local_files",
                    input = "{}",
                    approvalState = ToolApprovalState.Approved,
                ),
            ),
        )

        assertEquals(approved, approved.markInterruptedToolsForContinuation())
    }

    @Test
    fun `completed assistant response is not considered interrupted`() {
        val completed = UIMessage.assistant("done").copy(
            finishedAt = kotlinx.datetime.LocalDateTime(2026, 8, 21, 12, 0),
        )
        assertTrue(!completed.isInterruptedAssistantResponse())
        assertTrue(!UIMessage.assistant("preset message").isInterruptedAssistantResponse())
    }

    @Test
    fun `only current final interrupted assistant response uses continuation`() {
        val user = UIMessage.user("question")
        val interrupted = UIMessage.assistant("partial").markInterruptedToolsForContinuation()
        val conversation = Conversation.ofId(
            id = Uuid.random(),
            messages = listOf(user.toMessageNode(), interrupted.toMessageNode()),
        )

        assertTrue(conversation.shouldResumeInterruptedResponseAt(interrupted))
        assertTrue(!conversation.shouldResumeInterruptedResponseAt(user))

        val completed = interrupted.copy(
            finishedAt = kotlinx.datetime.LocalDateTime(2026, 8, 21, 12, 0),
            interrupted = false,
        )
        val completedConversation = conversation.updateCurrentMessages(listOf(user, completed))
        assertTrue(!completedConversation.shouldResumeInterruptedResponseAt(completed))

        val laterUser = UIMessage.user("later")
        val nonFinalConversation = conversation.copy(
            messageNodes = conversation.messageNodes + laterUser.toMessageNode(),
        )
        assertTrue(!nonFinalConversation.shouldResumeInterruptedResponseAt(interrupted))
    }

    @Test
    fun `plain continuation keeps partial text without persisting prompt`() {
        val request = listOf(UIMessage.user("continue"))
        val current = request + UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Reasoning("internal reasoning"),
                UIMessagePart.Text("The result is "),
                UIMessagePart.Text("42"),
            ),
        )

        val continuation = buildInterruptedStreamContinuation(
            requestMessages = current,
            currentMessages = current,
        )!!

        assertEquals(request.size + 1, continuation.size)
        assertEquals(MessageRole.ASSISTANT, continuation.last().role)
        assertEquals("The result is 42", continuation.last().toText())
        assertEquals(1, continuation.last().parts.size)
        assertSame(current.last(), current.last())
        assertEquals(request.count { it.role == MessageRole.USER }, continuation.count { it.role == MessageRole.USER })
    }

    @Test
    fun `media-only interruption is not offered as a native continuation`() {
        val request = listOf(UIMessage.user("continue"))
        val current = request + UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Image("data:image/png;base64,AA==")),
        )

        assertNull(buildInterruptedStreamContinuation(request, current))
    }

    @Test
    fun `legacy tool parts are represented as interruption notes`() {
        val request = listOf(UIMessage.user("continue"))
        val current = request + UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.ToolCall("call", "lookup", "{\"q\":")),
        )

        assertNull(buildInterruptedStreamContinuation(request, current))
    }

    @Test
    fun `server tool in progress is not replayed as completed`() {
        val request = listOf(UIMessage.user("continue"))
        val current = request + UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.ServerTool(
                    toolCallId = "server-call",
                    toolName = "search",
                    status = ServerToolStatus.IN_PROGRESS,
                ),
            ),
        )

        assertNull(buildInterruptedStreamContinuation(request, current))
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
