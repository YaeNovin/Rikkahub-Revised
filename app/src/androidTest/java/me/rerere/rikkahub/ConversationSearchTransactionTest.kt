package me.rerere.rikkahub

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.fts.MessageFtsManager
import org.junit.Assert.*
import org.junit.Test

class ConversationSearchTransactionTest {
    @Test fun searchDeletionSharesTheRoomTransactionAndRollsBackWithIt() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val db = database.openHelper.writableDatabase
            // The delete statement needs only this column, not the native tokenizer extension.
            db.execSQL("CREATE TABLE message_fts (conversation_id TEXT)")
            db.execSQL("INSERT INTO message_fts VALUES ('chat')")
            val manager = MessageFtsManager(database)
            withTimeout(5_000) {
                val result = runCatching {
                    database.withTransaction {
                        manager.deleteConversationInTransaction("chat")
                        error("rollback")
                    }
                }
                assertEquals("rollback", result.exceptionOrNull()?.message)
                db.query("SELECT COUNT(*) FROM message_fts").use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
                database.withTransaction { manager.deleteConversationInTransaction("chat") }
                db.query("SELECT COUNT(*) FROM message_fts").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
            }
        } finally { database.close() }
    }
}
