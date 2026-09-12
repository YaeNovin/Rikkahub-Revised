package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.Dp

/** Measure the suggestion footer before the weighted message viewport, in the same layout pass. */
@Composable
internal fun ChatSuggestionLayout(
    showSuggestions: Boolean,
    bottomInset: Dp,
    suggestions: @Composable () -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds(), content = content)
        if (showSuggestions) {
            Box(Modifier.fillMaxWidth().padding(bottom = bottomInset)) {
                suggestions()
            }
        }
    }
}
