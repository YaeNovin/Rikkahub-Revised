package me.rerere.rikkahub.ui.hooks

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

internal fun shouldPinAfterIme(wasAtBottom: Boolean, opening: Boolean, userScrolling: Boolean): Boolean =
    wasAtBottom && opening && !userScrolling

@Composable
fun ImeLazyListAutoScroller(
    lazyListState: LazyListState,
    enabled: Boolean = true,
) {
    val ime = WindowInsets.ime
    val localDensity = LocalDensity.current
    LaunchedEffect(lazyListState, localDensity, enabled) {
        var previousHeight = ime.getBottom(localDensity)
        var wasAtBottom = !lazyListState.canScrollForward
        var pinToBottom = false
        snapshotFlow {
            Triple(ime.getBottom(localDensity), lazyListState.canScrollForward, lazyListState.isScrollInProgress)
        }.collectLatest { (keyboardHeight, canScrollForward, scrolling) ->
            if (keyboardHeight == 0) {
                pinToBottom = false
                wasAtBottom = !canScrollForward
            } else if (previousHeight == 0) {
                pinToBottom = shouldPinAfterIme(wasAtBottom, opening = true, userScrolling = scrolling)
            }
            previousHeight = keyboardHeight
            if (scrolling) pinToBottom = false
            // Insets already resize the viewport. Per-frame scrollBy(delta) forced a
            // second message layout on every IME frame, and moved history being read.
            if (enabled && pinToBottom) {
                delay(64)
                val lastIndex = lazyListState.layoutInfo.totalItemsCount - 1
                if (lastIndex >= 0 && !lazyListState.isScrollInProgress) {
                    lazyListState.requestScrollToItem(lastIndex)
                }
            }
        }
    }
}
