package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import me.rerere.rikkahub.ui.components.richtext.RichTextLayoutReadiness
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal data class ChatPreviewAnchor(val key: String, val index: Int, val offset: Int)

internal fun resolvePreviewReturnIndex(anchor: ChatPreviewAnchor, messageKeys: List<String>): Int =
    messageKeys.indexOf(anchor.key).takeIf { it >= 0 }
        ?: anchor.index.coerceIn(0, messageKeys.lastIndex.coerceAtLeast(0))

internal class ChatPreviewReturnState {
    var anchor by mutableStateOf<ChatPreviewAnchor?>(null)
    var restoring by mutableStateOf(false)
    var departed by mutableStateOf(false)
    val layoutReadiness = RichTextLayoutReadiness()

    fun capture(state: LazyListState) {
        val index = state.firstVisibleItemIndex
        val item = state.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } ?: return
        anchor = ChatPreviewAnchor(item.key.toString(), index, state.firstVisibleItemScrollOffset)
        departed = false
    }

    fun leave() { if (anchor != null) departed = true }
    fun cancel() { anchor = null; departed = false }

    companion object {
        val Saver = listSaver<ChatPreviewReturnState, Any>(
            save = { state -> state.anchor?.let { listOf(it.key, it.index, it.offset) } ?: emptyList() },
            restore = { values -> ChatPreviewReturnState().apply {
                if (values.size == 3) {
                    anchor = ChatPreviewAnchor(values[0] as String, values[1] as Int, values[2] as Int)
                    departed = true
                }
            } },
        )
    }
}

internal val LocalChatPreviewReturn = staticCompositionLocalOf<ChatPreviewReturnState?> { null }

@Composable
internal fun rememberChatPreviewReturn(
    conversationKey: String,
    listState: LazyListState,
    messageKeys: List<String>,
    ready: Boolean,
    active: Boolean = true,
): ChatPreviewReturnState {
    val state = rememberSaveable(conversationKey, saver = ChatPreviewReturnState.Saver) { ChatPreviewReturnState() }
    val currentKeys by rememberUpdatedState(messageKeys)
    LaunchedEffect(state, listState, active, ready, messageKeys.isEmpty(), state.departed) {
        if (!active) { state.leave(); return@LaunchedEffect }
        val anchor = state.anchor ?: return@LaunchedEffect
        if (!state.departed || !ready || currentKeys.isEmpty()) return@LaunchedEffect
        state.restoring = true
        val restoreJob = coroutineContext[kotlinx.coroutines.Job]!!
        val dragMonitor = launch {
            listState.interactionSource.interactions.collect { interaction ->
                if (interaction is DragInteraction.Start) {
                    state.cancel()
                    restoreJob.cancel() // User input always wins over delayed relayout corrections.
                }
            }
        }
        try {
            // A single long message can initially measure smaller than its saved offset. Keep
            // its parsing alive, then restore once; do not expire during the exit transition.
            withTimeoutOrNull(10_000) {
                while (state.anchor == anchor) {
                    val layout = listState.layoutInfo
                    if (layout.totalItemsCount > 0) {
                        val index = resolvePreviewReturnIndex(anchor, currentKeys)
                            .coerceAtMost(layout.totalItemsCount - 1)
                        if (listState.firstVisibleItemIndex != index) {
                            // An offset longer than the temporary placeholder would skip this
                            // message, cancel its parsing, and continuously scroll it back in.
                            listState.scrollToItem(index, 0)
                        }
                        val item = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
                        if (item != null && state.layoutReadiness.ready && item.size > anchor.offset) {
                            // Allow readiness effects and measured layout to agree before moving.
                            withFrameNanos { }
                            if (state.layoutReadiness.ready) {
                                listState.scrollToItem(index, anchor.offset)
                                delay(160)
                                if (state.layoutReadiness.ready && listState.firstVisibleItemIndex == index &&
                                    listState.firstVisibleItemScrollOffset == anchor.offset) break
                            }
                        }
                    }
                    val measured = listState.layoutInfo
                    val wasReady = state.layoutReadiness.ready
                    snapshotFlow { listState.layoutInfo to state.layoutReadiness.ready }
                        .first { it.first !== measured || it.second != wasReady }
                }
            }
            state.cancel()
        } finally {
            dragMonitor.cancel()
            state.restoring = false
        }
    }
    return state
}
