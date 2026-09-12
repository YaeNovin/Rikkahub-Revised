package me.rerere.rikkahub.data.ai.tools.local

import me.rerere.ai.core.Tool
import me.rerere.ai.ui.AskUserContract
import me.rerere.ai.ui.AskUserProtocol

internal fun buildAskUserTool(): Tool = Tool(
    name = AskUserProtocol.TOOL_NAME,
    description = AskUserContract.description,
    parameters = AskUserContract::schema,
    needsApproval = { true },
    execute = { error("ask_user tool should be handled by HITL flow") },
)
