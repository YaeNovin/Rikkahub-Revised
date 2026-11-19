package me.rerere.rikkahub

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.entity.ChatImageToolTaskEntity
import me.rerere.rikkahub.data.db.entity.ConversationEntity
import me.rerere.rikkahub.data.db.migrations.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ChatImageToolPersistenceTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java,
        listOf(Migration_8_9(), Migration_16_17(), Migration_22_23()), FrameworkSQLiteOpenHelperFactory())

    @Test fun migrationPreservesExistingDataAndAddsTaskLedger() {
        val name = "image-tool-migration-${System.nanoTime()}"
        try {
            helper.createDatabase(name, 43).use { db ->
                db.execSQL("INSERT INTO conversation_folder (id, assistant_id, name, sort_index, create_at) VALUES ('keep', 'assistant', 'Existing folder', 1, 100)")
            }
            helper.runMigrationsAndValidate(name, 44, true).use { db ->
                db.query("SELECT name FROM conversation_folder WHERE id = 'keep'").use {
                    assertTrue(it.moveToFirst()); assertEquals("Existing folder", it.getString(0))
                }
                db.query("SELECT COUNT(*) FROM chat_image_tool_task").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
            }
        } finally { InstrumentationRegistry.getInstrumentation().targetContext.deleteDatabase(name) }
    }

    @Test fun claimsAreAtomicAndDeletingConversationRemovesItsTasks() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            database.conversationDao().insert(ConversationEntity("chat", "assistant", "title", "[]", 0, 0, "[]", false))
            val dao = database.chatImageToolTaskDao()
            val task = ChatImageToolTaskEntity("task", "chat", "message", "source", "call", "hash", "original", "proposed", "{}", createdAt = 0, updatedAt = 0)
            assertTrue(dao.insert(task) > 0)
            assertEquals(-1L, dao.insert(task.copy(id = "duplicate", toolCallId = "other-call")))
            assertEquals(1, dao.approve("task", "confirmed", 1))
            assertEquals(0, dao.approve("task", "modified", 2))
            assertEquals("confirmed", dao.get("task")?.requestJson)
            assertEquals(1, dao.transition("task", listOf("approved"), "running", null, 3))
            assertEquals(0, dao.transition("task", listOf("approved"), "running", null, 4))
            dao.recoverInterrupted(5)
            assertEquals("unknown", dao.get("task")?.status)
            database.conversationDao().deleteById("chat")
            assertNull(dao.get("task"))
            assertEquals(0, dao.saveImages("task", "[]", 6))
        } finally { database.close() }
    }
}
