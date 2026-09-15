package me.rerere.rikkahub.ui.components.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.ui.theme.JetbrainsMono

internal fun isLongDisplayText(text: String): Boolean = text.length > 1600 || text.count { it == '\n' } >= 16

/** Display chunks only: the source is never rewritten, and concatenation is lossless. */
internal fun splitDisplayText(text: String, limit: Int = 1200): List<String> {
    require(limit >= 2)
    if (text.isEmpty()) return emptyList()
    return buildList {
        var start = 0
        while (start < text.length) {
            var end = minOf(start + limit, text.length)
            if (end < text.length) {
                val lineEnd = text.lastIndexOf('\n', end - 1)
                if (lineEnd >= start + limit / 2) end = lineEnd + 1
                if (text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
            }
            add(text.substring(start, end))
            start = end
        }
    }
}

/** Caller supplies a finite height; only visible chunks are laid out. */
@Composable
internal fun LongTextContent(text: String, modifier: Modifier = Modifier) {
    val chunks = remember(text) { splitDisplayText(text) }
    LazyColumn(modifier, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(chunks) { chunk ->
            SelectionContainer {
                Text(chunk, fontFamily = JetbrainsMono, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}
