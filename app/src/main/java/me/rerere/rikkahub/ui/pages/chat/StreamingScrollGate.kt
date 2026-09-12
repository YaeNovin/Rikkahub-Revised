package me.rerere.rikkahub.ui.pages.chat

/** A pending correction is invalidated even if a short drag ends during its delay. */
internal class StreamingScrollGate {
    var interactionVersion = 0L
        private set
    var browsing = false
        private set

    fun onUserScroll() {
        interactionVersion++
        browsing = true
    }

    fun onScrollSettled(atBottom: Boolean) {
        if (atBottom) browsing = false
    }

    fun allowsPending(version: Long, index: Int, offset: Int, currentIndex: Int, currentOffset: Int): Boolean =
        !browsing && version == interactionVersion && index == currentIndex && offset == currentOffset
}
