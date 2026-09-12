package me.rerere.rikkahub.data.ai.prompts

internal val DEFAULT_SUGGESTION_PROMPT = """
    I will provide you with some chat content in the `<content>` block, including conversations between the User and the AI assistant.
    You need to act as the **User** to reply to the assistant, generating {suggestion_count} appropriate and contextually relevant responses.

    Rules:
    1. Return a JSON object with a `suggestions` array. Each item has `text`, `description`, `category`, `action`, and `payload`.
    2. Use {locale} language.
    3. Ensure each suggestion is valid.
    4. Each suggestion should not exceed {suggestion_max_length} characters.
    5. Imitate the user's previous conversational style.
    6. Act as a User, not an Assistant!
    7. Requested style: {suggestion_style}.
    8. Choose `category` from the local suggestion contract supplied after this prompt; roleplay may allow additional categories.
    9. `action` must be one of the locally allowed actions supplied after this prompt. Prefer `insert_text`; use another action only when it precisely matches the suggestion.
    10. `payload` contains the text or query used by the action. Never invent a tool name, command, file path, or capability.

    <content>
    {content}
    </content>
""".trimIndent()
