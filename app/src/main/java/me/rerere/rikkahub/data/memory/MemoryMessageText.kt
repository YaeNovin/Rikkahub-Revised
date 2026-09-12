package me.rerere.rikkahub.data.memory

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

/** Only text and attachment metadata: never URLs, encoded media, reasoning or tool arguments. */
internal fun UIMessage.memoryText(): String = parts.mapNotNull { part ->
    when (part) {
        is UIMessagePart.Text -> part.text.take(12_000)
        is UIMessagePart.Image -> "[Image attachment; use accompanying text and response for context, not as verified user facts]"
        is UIMessagePart.Document -> "[Document attachment: ${part.fileName.take(160)}]"
        is UIMessagePart.Audio -> "[Audio attachment; content unavailable unless transcribed in the conversation]"
        is UIMessagePart.Video -> "[Video attachment; content unavailable unless described in the conversation]"
        else -> null
    }
}.joinToString("\n").take(16_000).trim()
