package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.AnnotatedString

internal const val CHAT_LITERAL_TAG = "rikkahub.literal"

@Immutable
internal data class ChatInlineOptions(val hexSwatches: Boolean = false, val quotedText: Boolean = false)

internal val LocalChatInlineOptions = staticCompositionLocalOf { ChatInlineOptions() }

// Code renderers can contain nested Markdown previews. They must not re-enable
// prose decoration just because the user's display preferences are enabled.
internal val LocalChatProseDecorationsEnabled = staticCompositionLocalOf { true }

@Immutable
internal data class ChatTextRange(val start: Int, val end: Int)

@Immutable
internal data class ChatHexSwatch(val start: Int, val end: Int, val argb: Int)

internal data class ChatInlinePlan(
    val hexSwatches: List<ChatHexSwatch> = emptyList(),
    val quotes: List<ChatTextRange> = emptyList(),
)

private val proseUrl = Regex("(?:https?://|mailto:|www\\.)[^\\s<>\"“”＂«»]+", RegexOption.IGNORE_CASE)

/** Offsets address displayed prose, never the stored message or request body. */
internal fun analyzeChatInline(text: AnnotatedString, options: ChatInlineOptions): ChatInlinePlan {
    if (text.isEmpty() || (!options.hexSwatches && !options.quotedText)) return ChatInlinePlan()
    val excluded = buildList {
        text.getStringAnnotations(0, text.length).filter {
            it.tag == CHAT_LITERAL_TAG || it.tag == "androidx.compose.foundation.text.inlineContent"
        }.forEach { add(ChatTextRange(it.start, it.end)) }
        proseUrl.findAll(text.text).forEach { add(ChatTextRange(it.range.first, it.range.last + 1)) }
    }.sortedBy { it.start }
    val blocked = mergeChatRanges(excluded)
    var blockedIndex = 0
    val swatches = mutableListOf<ChatHexSwatch>()
    val quotes = mutableListOf<ChatTextRange>()
    val opened = ArrayDeque<Pair<Char, Int>>()
    var index = 0
    while (index < text.length) {
        while (blockedIndex < blocked.size && blocked[blockedIndex].end <= index) blockedIndex++
        val block = blocked.getOrNull(blockedIndex)
        if (block != null && index >= block.start) {
            index = block.end
            continue
        }
        val character = text[index]
        if (options.hexSwatches && character == '#' && index + 7 <= text.length &&
            (index == 0 || !text[index - 1].isHexWordBoundary()) &&
            (index + 7 == text.length || !text[index + 7].isHexWordBoundary()) &&
            (block == null || block.start >= index + 7)
        ) {
            val rgb = text.text.substring(index + 1, index + 7)
            if (rgb.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
                swatches += ChatHexSwatch(index, index + 7, 0xFF000000.toInt() or rgb.toInt(16))
            }
        }
        if (options.quotedText && character in "\"“”＂«»„" && !text.text.isEscapedAt(index)) {
            if (opened.lastOrNull()?.first == character) {
                val (_, start) = opened.removeLast()
                if (index > start + 1) quotes += ChatTextRange(start + 1, index)
            } else {
                val close = when (character) {
                    '"' -> '"'
                    '“' -> '”'
                    '＂' -> '＂'
                    '«' -> '»'
                    '„' -> '“'
                    else -> null
                }
                if (close != null) opened.addLast(close to index)
            }
        }
        index++
    }
    // Nested quotes must not create repeated, overlapping color spans. Preserve literal islands.
    val proseQuotes = mutableListOf<ChatTextRange>()
    var blockIndex = 0
    for (quote in mergeChatRanges(quotes.sortedBy { it.start })) {
        var start = quote.start
        while (blockIndex < blocked.size && blocked[blockIndex].end <= start) blockIndex++
        var cursor = blockIndex
        while (cursor < blocked.size && blocked[cursor].start < quote.end) {
            val literal = blocked[cursor++]
            if (literal.start > start) proseQuotes += ChatTextRange(start, literal.start)
            start = maxOf(start, literal.end)
        }
        if (start < quote.end) proseQuotes += ChatTextRange(start, quote.end)
    }
    return ChatInlinePlan(swatches, proseQuotes)
}

private fun Char.isHexWordBoundary() = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9' || this == '_' || this == '#'

private fun String.isEscapedAt(index: Int): Boolean {
    var cursor = index - 1
    while (cursor >= 0 && this[cursor] == '\\') cursor--
    return (index - cursor - 1) % 2 == 1
}

private fun mergeChatRanges(ranges: List<ChatTextRange>): List<ChatTextRange> = buildList {
    for (range in ranges) {
        if (range.start >= range.end) continue
        val previous = lastOrNull()
        if (previous != null && range.start <= previous.end) {
            this[lastIndex] = ChatTextRange(previous.start, maxOf(previous.end, range.end))
        } else add(range)
    }
}

internal inline fun AnnotatedString.Builder.withChatLiteral(block: AnnotatedString.Builder.() -> Unit) {
    val marker = pushStringAnnotation(CHAT_LITERAL_TAG, "literal")
    block()
    pop(marker)
}
