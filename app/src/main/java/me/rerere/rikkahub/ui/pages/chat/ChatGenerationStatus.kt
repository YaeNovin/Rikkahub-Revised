package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.ui.components.ui.RabbitLoadingIndicator

/** A lazy-list footer whose status may change while a fling is placing its children. */
@Composable
internal fun ChatGenerationStatus(processingStatus: String?, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Keep one measured slot. AnimatedVisibility here can retain an unmeasured
        // lookahead child when the status changes during a lazy-list scroll pass.
        Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
            if (processingStatus == null) {
                RabbitLoadingIndicator(Modifier.size(28.dp))
            } else {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        }
        if (processingStatus != null) {
            Text(
                text = processingStatus,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
