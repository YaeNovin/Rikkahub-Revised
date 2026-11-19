package me.rerere.rikkahub.data.ai

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.*
import org.junit.Assert.*
import org.junit.Test

class ImageToolPolicyTest {
    private val question = """{"questions":[{"id":"generate","question":"生成图片？","selection_type":"confirm"}]}"""
    private fun ask(state: ToolApprovalState) = UIMessagePart.Tool("ask", "ask_user", question, approvalState = state)
    private fun messages(tool: UIMessagePart.Tool) = listOf(UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("画图"))),
        UIMessage.assistant("").copy(parts = listOf(tool)))

    @Test fun `a batched question defers generation without consuming a task`() {
        assertTrue(shouldDeferImageForQuestions(listOf(ask(ToolApprovalState.Auto))))
        assertTrue(shouldDeferImageForQuestions(listOf(ask(ToolApprovalState.Pending))))
        assertFalse(shouldDeferImageForQuestions(listOf(ask(ToolApprovalState.Answered("{}"))
            .copy(output = listOf(UIMessagePart.Text("answered"))))))
    }

    @Test fun `false expired cancelled and invalid answers cannot authorize autonomous images`() {
        val decisions = listOf(ToolApprovalState.Answered("""{"answers":{"generate":false}}"""),
            ToolApprovalState.Answered("invalid"), ToolApprovalState.Denied("no"),
            ToolApprovalState.Cancelled("cancel"), ToolApprovalState.Expired("expired"))
        decisions.forEach { assertTrue(messages(ask(it)).imageAutonomyBlockedByAnswer()) }
    }

    @Test fun `valid clarification allows invocation but does not provide parameters`() {
        assertFalse(messages(ask(ToolApprovalState.Answered("""{"answers":{"generate":true}}"""))).imageAutonomyBlockedByAnswer())
        val text = ask(ToolApprovalState.Answered("""{"answers":{"style":"blue bird"}}"""))
            .copy(input = """{"questions":[{"id":"style","question":"风格？","selection_type":"text"}]}""")
        assertFalse(messages(text).imageAutonomyBlockedByAnswer())
    }

    @Test fun `previous turn rejection does not block a new user request`() {
        val old = messages(ask(ToolApprovalState.Denied("no")))
        assertFalse((old + UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("现在画图")))).imageAutonomyBlockedByAnswer())
    }
}
