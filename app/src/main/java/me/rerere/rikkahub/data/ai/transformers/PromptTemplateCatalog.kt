package me.rerere.rikkahub.data.ai.transformers

import me.rerere.rikkahub.R

/**
 * Optional prompt starters. They are inserted only when the user selects one
 * in the editor, so adding a template never changes an existing assistant.
 */
data class PromptTemplateDescriptor(
    val id: Id,
    val labelRes: Int,
    val content: String = "",
) {
    enum class Id {
        NONE,
        GENERAL_ASSISTANT,
        ROLEPLAY_CHARACTER,
        WORKSPACE_COPILOT,
    }
}

object PromptTemplateCatalog {
    val all: List<PromptTemplateDescriptor> = listOf(
        PromptTemplateDescriptor(
            id = PromptTemplateDescriptor.Id.NONE,
            labelRes = R.string.prompt_template_select,
        ),
        PromptTemplateDescriptor(
            id = PromptTemplateDescriptor.Id.GENERAL_ASSISTANT,
            labelRes = R.string.prompt_template_general_assistant,
            content = """
                You are {{assistant_name}}, a helpful and reliable AI assistant.
                Address the user as {{user_name}} when appropriate.
                Respond in {{language}} unless the user requests another language.
                Be clear, honest about uncertainty, and keep the answer focused on the user's goal.
            """.trimIndent(),
        ),
        PromptTemplateDescriptor(
            id = PromptTemplateDescriptor.Id.ROLEPLAY_CHARACTER,
            labelRes = R.string.prompt_template_roleplay_character,
            content = """
                You are {{char_name}} in an ongoing roleplay with {{user_name}}.
                Stay in character and preserve established facts, relationships, and tone.
                Describe the character's words, actions, and observations; do not decide the user's actions or feelings.
                Keep the scene moving with concrete, sensory details and leave room for the user to respond.
            """.trimIndent(),
        ),
        PromptTemplateDescriptor(
            id = PromptTemplateDescriptor.Id.WORKSPACE_COPILOT,
            labelRes = R.string.prompt_template_workspace_copilot,
            content = """
                You are {{assistant_name}}, a careful workspace copilot.
                Current workspace: {{workspace_name}}
                Workspace root: {{workspace_root}}
                Current directory: {{workspace_cwd}}
                Inspect relevant files before changing them, explain risky operations, and do not invent file contents.
                If no workspace is available, say so and ask the user for the required file or directory.
            """.trimIndent(),
        ),
    )
}
