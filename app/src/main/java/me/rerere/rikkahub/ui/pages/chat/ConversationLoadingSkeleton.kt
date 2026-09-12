package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import me.rerere.rikkahub.ui.modifier.shimmer

/** Placeholder geometry never participates in the actual message LazyList or its saved anchor. */
@Composable
internal fun ConversationLoadingSkeleton(modifier: Modifier = Modifier) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(120); visible = true }
    if (!visible) return
    Column(modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 28.dp)
        .semantics { contentDescription = "正在准备聊天内容" }, verticalArrangement = Arrangement.spacedBy(28.dp)) {
        repeat(3) { block ->
            Column(Modifier.fillMaxWidth(if (block == 1) .64f else .88f)
                .align(if (block == 1) Alignment.End else Alignment.Start), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                repeat(if (block == 1) 2 else 4) { line ->
                    Box(Modifier.fillMaxWidth(if (line == 3) .58f else if (line == 0) .75f else 1f)
                        .height(if (line == 0) 18.dp else 12.dp).clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = .08f))
                        .shimmer(true, shimmerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = .09f), durationMillis = 1600))
                }
            }
        }
    }
}
