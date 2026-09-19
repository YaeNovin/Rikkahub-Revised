package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first

/** Material3 ignores open() while its drawer anchors still have a NaN offset. */
internal suspend fun DrawerState.openWhenReady() {
    if (targetValue == DrawerValue.Open) return
    if (!currentOffset.isFinite()) {
        snapshotFlow { currentOffset }.first { it.isFinite() }
    }
    if (targetValue != DrawerValue.Open) {
        open()
    }
}

internal fun drawerOccludesChat(current: DrawerValue, target: DrawerValue): Boolean =
    current != DrawerValue.Closed || target != DrawerValue.Closed
