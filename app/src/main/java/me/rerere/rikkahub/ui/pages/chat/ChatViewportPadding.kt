package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

// Scaffold updates its padding while measuring each IME animation frame. Keep
// these reads in layout so they do not recompose the entire message tree.
internal fun chatPagePadding(source: PaddingValues, headerHeight: () -> Dp): PaddingValues =
    object : PaddingValues by source {
        override fun calculateTopPadding() = maxOf(source.calculateTopPadding() + 56.dp, headerHeight())
    }

internal fun chatOverlayPadding(source: PaddingValues, reserveComposer: Boolean): PaddingValues =
    object : PaddingValues by source {
        override fun calculateBottomPadding() = if (reserveComposer) source.calculateBottomPadding() else 0.dp
    }

internal fun chatMessagePadding(source: PaddingValues): PaddingValues = object : PaddingValues {
    override fun calculateLeftPadding(layoutDirection: LayoutDirection) = 16.dp
    override fun calculateRightPadding(layoutDirection: LayoutDirection) = 16.dp
    override fun calculateTopPadding() = 16.dp + source.calculateTopPadding()
    override fun calculateBottomPadding() = 48.dp + source.calculateBottomPadding()
}

internal fun chatComposerPadding(source: PaddingValues): PaddingValues =
    object : PaddingValues by PaddingValues(0.dp) {
        override fun calculateBottomPadding() = source.calculateBottomPadding()
    }
