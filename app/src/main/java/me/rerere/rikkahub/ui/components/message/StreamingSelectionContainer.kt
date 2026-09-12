package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState

/** Keep the same rich-content tree when selection is enabled at the end of a response.
 * Streaming still has no selection registrar (avoids the selectable-list mutation crash).
 */
@Composable
internal fun StreamingSelectionContainer(streaming: Boolean, content: @Composable () -> Unit) {
    val currentContent by rememberUpdatedState(content)
    val body = remember { movableContentOf { currentContent() } }
    if (streaming) body() else SelectionContainer { body() }
}
