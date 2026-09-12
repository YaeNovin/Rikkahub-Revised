package me.rerere.rikkahub.data.model

enum class SuggestionInsertLocation { APPEND, REPLACE, CURSOR }
data class SuggestionDraftEdit(val before: String, val after: String, val cursor: Int)

fun prepareSuggestionInsertion(before: String, text: String, location: SuggestionInsertLocation,
    selectionStart: Int = before.length, selectionEnd: Int = selectionStart): SuggestionDraftEdit {
    val start = minOf(selectionStart, selectionEnd).coerceIn(0, before.length)
    val end = maxOf(selectionStart, selectionEnd).coerceIn(start, before.length)
    val inserted = when (location) {
        SuggestionInsertLocation.REPLACE -> text
        SuggestionInsertLocation.APPEND -> before + (if (before.isBlank()) "" else "\n") + text
        SuggestionInsertLocation.CURSOR -> before.substring(0, start) + text + before.substring(end)
    }
    return SuggestionDraftEdit(before, inserted, if (location == SuggestionInsertLocation.CURSOR) start + text.length else inserted.length)
}
