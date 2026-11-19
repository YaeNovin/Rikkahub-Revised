package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable

/** Keep native text selection alive while content streams. Moving the completed
 * answer into a new host recreates every text layout and resets local state. */
@Composable
@Suppress("UNUSED_PARAMETER")
internal fun StreamingSelectionContainer(streaming: Boolean, content: @Composable () -> Unit) {
    SelectionContainer(content = content)
}
