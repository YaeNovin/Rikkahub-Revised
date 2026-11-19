package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.*
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.AskUserContract
import me.rerere.ai.ui.AskUserProtocol
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.data.db.entity.ChatImageToolTaskEntity
import kotlin.uuid.Uuid

internal fun validateImageInspection(task: ChatImageToolTaskEntity, conversationId: Uuid, messages: List<UIMessage>) {
    require(task.conversationId == conversationId.toString()) { "不能访问其他对话的图片" }
    val owner = messages.find { it.id.toString() == task.messageId } ?: error("图片不属于当前分支")
    val tool = owner.getTools().find { it.toolCallId == task.toolCallId } ?: error("原生图调用不存在")
    validateImageToolBinding(task, conversationId, owner.id, tool)
}

/** Questions must finish before the model can formulate the actual image prompt. */
internal fun shouldDeferImageForQuestions(tools: List<UIMessagePart.Tool>): Boolean =
    tools.any { it.toolName == AskUserProtocol.TOOL_NAME && !it.isExecuted }

/** Fail closed after a declined/expired form, including a typed false confirmation. */
internal fun List<UIMessage>.imageAutonomyBlockedByAnswer(): Boolean =
    drop(indexOfLast { it.role == MessageRole.USER } + 1).flatMap { it.getTools() }
        .filter { it.toolName == AskUserProtocol.TOOL_NAME }.any { tool ->
            when (val approval = tool.approvalState) {
                is ToolApprovalState.Answered -> runCatching {
                    val request = AskUserProtocol.parseRequest(tool.input).getOrThrow()
                    val result = JsonInstant.parseToJsonElement(AskUserContract.answerResult(request, approval.answer)).jsonObject
                    val answers = result.getValue("answers").jsonObject
                    request.questions.any { question ->
                        question.selectionType == AskUserProtocol.SelectionType.CONFIRM &&
                            answers[question.id]?.jsonPrimitive?.booleanOrNull == false
                    }
                }.getOrDefault(true)
                is ToolApprovalState.Denied, is ToolApprovalState.Cancelled, is ToolApprovalState.Expired -> true
                else -> false
            }
        }
