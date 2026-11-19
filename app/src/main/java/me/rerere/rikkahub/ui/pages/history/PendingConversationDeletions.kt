package me.rerere.rikkahub.ui.pages.history

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlin.uuid.Uuid

/** Keep the database, attachments and related records intact throughout the undo window. */
internal class PendingConversationDeletions {
    private val lock = Any()
    private val pending = MutableStateFlow<Set<Uuid>>(emptySet())
    val ids = pending.asStateFlow()

    suspend fun run(
        id: Uuid,
        confirmDeletion: suspend () -> Boolean,
        delete: suspend () -> Unit,
    ) {
        synchronized(lock) {
            if (id in pending.value) return
            pending.value += id
        }
        try {
            // Cancellation (for example leaving the page) cancels the pending deletion.
            if (confirmDeletion()) {
                // Once committed, finish the database/file operation even if the page closes.
                withContext(NonCancellable) { delete() }
            }
        } finally {
            synchronized(lock) { pending.value -= id }
        }
    }
}
