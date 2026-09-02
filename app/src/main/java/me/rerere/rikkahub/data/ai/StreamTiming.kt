package me.rerere.rikkahub.data.ai

import me.rerere.ai.ui.StreamChunk

/** Wire-level keepalives never become content chunks; structural stream events stay excluded here. */
internal fun StreamChunk.isFirstModelContentToken(): Boolean = when (this) {
    is StreamChunk.TextDelta -> text.isNotBlank()
    is StreamChunk.ReasoningDelta -> text.isNotBlank()
    else -> false
}
