package me.rerere.rikkahub.ui.components.message

import androidx.compose.ui.util.fastForEachIndexed
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.ServerToolMetadata
import me.rerere.ai.ui.ServerToolProtocol
import me.rerere.ai.ui.metadataAs
import me.rerere.rikkahub.data.ai.tools.RENDER_CONTENT_TOOL_NAME
import me.rerere.rikkahub.utils.JsonInstant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 思考步骤类型，用于分组 Reasoning、客户端 Tool 和 ServerTool
 */
sealed interface ThinkingStep {
    data class ReasoningStep(
        val reasoning: UIMessagePart.Reasoning,
    ) : ThinkingStep

    data class ToolStep(
        val tool: UIMessagePart.Tool,
    ) : ThinkingStep

    data class ServerToolStep(
        val tool: UIMessagePart.ServerTool,
    ) : ThinkingStep
}

/**
 * 消息部分块类型，用于保持渲染顺序
 */
sealed interface MessagePartBlock {
    data class ThinkingBlock(val steps: List<ThinkingStep>) : MessagePartBlock
    data class ContentBlock(val part: UIMessagePart, val index: Int) : MessagePartBlock

    /**
     * An accepted renderer artifact belongs to the assistant answer, not the
     * chain-of-thought surface. Keep the original tool for replay/export.
     */
    data class RenderedContentBlock(val tool: UIMessagePart.Tool, val index: Int) : MessagePartBlock
}

/**
 * 将 parts 分组成 ThinkingBlock、正文 ContentBlock 和渲染正文块。
 * Reasoning、普通客户端 Tool 和 ServerTool 会按原顺序分组到 ThinkingBlock；
 * 已接受的自动渲染工具结果单独进入正文。
 */
fun List<UIMessagePart>.groupMessageParts(): List<MessagePartBlock> {
    val googleCallIds = filterIsInstance<UIMessagePart.ServerTool>().mapNotNull {
        it.metadataAs<ServerToolMetadata>()?.takeIf { it.protocol == ServerToolProtocol.GOOGLE_GENERATE_CONTENT }
            ?.call?.get("toolCall")?.let { it as? JsonObject }?.get("id")?.jsonPrimitive?.contentOrNull
    }.toSet()
    val result = mutableListOf<MessagePartBlock>()
    var currentThinkingSteps = mutableListOf<ThinkingStep>()

    fun flushThinkingSteps() {
        if (currentThinkingSteps.isNotEmpty()) {
            result.add(MessagePartBlock.ThinkingBlock(currentThinkingSteps.toList()))
            currentThinkingSteps = mutableListOf()
        }
    }

    this.fastForEachIndexed { index, part ->
        when (part) {
            is UIMessagePart.Reasoning -> {
                currentThinkingSteps.add(ThinkingStep.ReasoningStep(part))
            }

            is UIMessagePart.Tool -> {
                if (part.isAcceptedAutomaticRender()) {
                    flushThinkingSteps()
                    result.add(MessagePartBlock.RenderedContentBlock(part, index))
                } else {
                    currentThinkingSteps.add(ThinkingStep.ToolStep(part))
                }
            }

            is UIMessagePart.ServerTool -> {
                // The result retains its wire position for replay, while the paired call
                // already displays its completed status. Do not draw a duplicate step.
                val responseId = part.metadataAs<ServerToolMetadata>()
                    ?.takeIf { it.protocol == ServerToolProtocol.GOOGLE_GENERATE_CONTENT }
                    ?.result?.get("toolResponse")?.let { it as? JsonObject }?.get("id")?.jsonPrimitive?.contentOrNull
                if (responseId != null && responseId in googleCallIds) return@fastForEachIndexed
                currentThinkingSteps.add(ThinkingStep.ServerToolStep(part))
            }

            else -> {
                flushThinkingSteps()
                result.add(MessagePartBlock.ContentBlock(part, index))
            }
        }
    }
    flushThinkingSteps()
    return result
}

/**
 * Promote only accepted automatic previews. Failed, pending, and manual-only
 * (HTML) requests remain visible as ordinary tool steps.
 */
private fun UIMessagePart.Tool.isAcceptedAutomaticRender(): Boolean {
    if (toolName != RENDER_CONTENT_TOOL_NAME || !isExecuted) return false
    val result = output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
    return runCatching {
        val payload = JsonInstant.parseToJsonElement(result).jsonObject
        payload["status"]?.jsonPrimitive?.content == "accepted" &&
            payload["display"]?.jsonPrimitive?.content == "chat_preview"
    }.getOrDefault(false)
}
