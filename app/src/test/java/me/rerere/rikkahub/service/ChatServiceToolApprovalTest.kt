package me.rerere.rikkahub.service

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.AskUserProtocol
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ChatServiceToolApprovalTest {
    @Test fun `new hidden confirmation does not expire the whole form and expired visible answers become false`() {
        val input = """{"questions":[{"id":"text","question":"Text"},{"id":"confirm","question":"Sure?","selection_type":"confirm","timeout_seconds":1}]}"""
        val request = me.rerere.ai.ui.AskUserProtocol.parseRequest(input).getOrThrow()
        val start = me.rerere.ai.ui.AskUserInteraction.Clock(10_000, 1_000, 2)
        val metadata = me.rerere.ai.ui.AskUserInteraction.update(input, request,
            me.rerere.ai.ui.AskUserInteraction.initial(input, start.wall), buildJsonObject {}, setOf("confirm"), start)
        val message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Tool(
            toolCallId = "new-confirm", toolName = "ask_user", input = input, approvalState = ToolApprovalState.Pending, metadata = metadata)))
        val conversation = Conversation.ofId(Uuid.random(), messages = listOf(message.toMessageNode()))
        val normalized = conversation.normalizeAskUserPendingStates(12_000, start.copy(wall = 12_000, elapsed = 3_000))
        val tool = normalized.currentMessages.single().getTools().single()
        assertTrue(tool.approvalState is ToolApprovalState.Pending)
        assertEquals(kotlinx.serialization.json.JsonPrimitive(false), me.rerere.ai.ui.AskUserInteraction.answers(input, tool.metadata)["confirm"])
    }

    @Test fun `single displayed confirmation still rejects on reopen after timeout`() {
        val input = """{"questions":[{"id":"confirm","question":"Sure?","selection_type":"confirm","timeout_seconds":1}]}"""
        val request = me.rerere.ai.ui.AskUserProtocol.parseRequest(input).getOrThrow()
        val clock = me.rerere.ai.ui.AskUserInteraction.Clock(10_000, 1_000, 2)
        val metadata = me.rerere.ai.ui.AskUserInteraction.update(input, request,
            me.rerere.ai.ui.AskUserInteraction.initial(input, clock.wall), buildJsonObject {}, setOf("confirm"), clock)
        val message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Tool(
            toolCallId = "new-confirm", toolName = "ask_user", input = input, approvalState = ToolApprovalState.Pending, metadata = metadata)))
        val normalized = Conversation.ofId(Uuid.random(), messages = listOf(message.toMessageNode()))
            .normalizeAskUserPendingStates(12_000, clock.copy(wall = 12_000, elapsed = 3_000))
        assertTrue(normalized.currentMessages.single().getTools().single().approvalState is ToolApprovalState.Denied)
    }
    @Test
    fun `legacy pending ask user receives a timestamp without expiring`() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = "ask-1",
                    toolName = AskUserProtocol.TOOL_NAME,
                    input = "{\"questions\":[{\"id\":\"q\",\"question\":\"Q\"}]}",
                    approvalState = ToolApprovalState.Pending,
                )
            ),
        )
        val conversation = Conversation.ofId(
            id = Uuid.random(),
            messages = listOf(message.toMessageNode()),
        )

        val normalized = conversation.normalizeAskUserPendingStates(10_000L)
        val tool = normalized.currentMessages.single().parts.single() as UIMessagePart.Tool
        assertEquals(
            10_000L,
            tool.metadata?.get(AskUserProtocol.PENDING_AT_METADATA_KEY)?.toString()?.toLong(),
        )
        assertTrue(tool.approvalState is ToolApprovalState.Pending)
        assertTrue(tool.output.isEmpty())
    }

    @Test
    fun `stale pending ask user becomes an executed expired result`() {
        val pendingAt = 1_000L
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = "ask-2",
                    toolName = AskUserProtocol.TOOL_NAME,
                    input = "{\"questions\":[{\"id\":\"q\",\"question\":\"Q\"}]}",
                    approvalState = ToolApprovalState.Pending,
                    metadata = buildJsonObject {
                        put(AskUserProtocol.PENDING_AT_METADATA_KEY, pendingAt)
                    },
                )
            ),
        )
        val conversation = Conversation.ofId(
            id = Uuid.random(),
            messages = listOf(message.toMessageNode()),
        )

        val normalized = conversation.normalizeAskUserPendingStates(
            pendingAt + AskUserProtocol.APPROVAL_TIMEOUT_MILLIS,
        )
        val tool = normalized.currentMessages.single().parts.single() as UIMessagePart.Tool
        assertTrue(tool.approvalState is ToolApprovalState.Expired)
        assertTrue(tool.isExecuted)
        assertTrue(tool.output.first().toString().contains("expired"))
    }

    @Test
    fun `ordinary confirmation does not receive a countdown deadline`() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = "ask-ordinary-confirm",
                    toolName = AskUserProtocol.TOOL_NAME,
                    input = "{\"questions\":[{\"id\":\"q\",\"question\":\"Continue?\",\"selection_type\":\"confirm\"}]}",
                    approvalState = ToolApprovalState.Pending,
                )
            ),
        )
        val conversation = Conversation.ofId(
            id = Uuid.random(),
            messages = listOf(message.toMessageNode()),
        )

        val normalized = conversation.normalizeAskUserPendingStates(10_000L)
        val tool = normalized.currentMessages.single().parts.single() as UIMessagePart.Tool
        assertTrue(tool.approvalState is ToolApprovalState.Pending)
        assertTrue(tool.metadata?.get(AskUserProtocol.CONFIRM_DEADLINE_METADATA_KEY) == null)
    }

    @Test
    fun `expired confirmation deadline is honored even without legacy timestamp`() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = "ask-expired-confirm",
                    toolName = AskUserProtocol.TOOL_NAME,
                    input = "{\"questions\":[{\"id\":\"q\",\"question\":\"Continue?\",\"selection_type\":\"confirm\",\"danger\":true}]}",
                    approvalState = ToolApprovalState.Pending,
                    metadata = buildJsonObject {
                        put(AskUserProtocol.CONFIRM_DEADLINE_METADATA_KEY, 1_000L)
                    },
                )
            ),
        )
        val conversation = Conversation.ofId(
            id = Uuid.random(),
            messages = listOf(message.toMessageNode()),
        )

        val normalized = conversation.normalizeAskUserPendingStates(2_000L)
        val tool = normalized.currentMessages.single().parts.single() as UIMessagePart.Tool
        assertTrue(tool.approvalState is ToolApprovalState.Denied)
        assertTrue(tool.isExecuted)
    }
}
