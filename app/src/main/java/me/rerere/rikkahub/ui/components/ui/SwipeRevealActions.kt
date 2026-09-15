package me.rerere.rikkahub.ui.components.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/** End-to-start actions may occupy only the strip exposed by the moving foreground. */
internal fun swipeActionRevealWidth(offset: Float, width: Float, rtl: Boolean): Float {
    if (!offset.isFinite() || !width.isFinite() || width <= 0f) return 0f
    return (if (rtl) offset else -offset).coerceIn(0f, width)
}

@Composable
internal fun SwipeRevealActions(state: SwipeToDismissBoxState, content: @Composable RowScope.() -> Unit) {
    val density = LocalDensity.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    var width by remember { mutableIntStateOf(0) }
    val exposed by remember(state, rtl) { derivedStateOf {
        swipeActionRevealWidth(runCatching { state.requireOffset() }.getOrDefault(0f), width.toFloat(), rtl)
    } }
    Box(Modifier.fillMaxSize().onSizeChanged { width = it.width }.clipToBounds(), contentAlignment = Alignment.CenterEnd) {
        // No invisible controls remain clickable or accessible when the card is at rest.
        if (exposed > 0f) {
            Box(Modifier.width(with(density) { exposed.toDp() }).fillMaxHeight().clipToBounds(), contentAlignment = Alignment.CenterEnd) {
                Row(Modifier.requiredWidth(120.dp).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically, content = content)
            }
        }
    }
}
