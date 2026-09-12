package me.rerere.rikkahub.data.db.migrations

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.db.AppDatabase
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import kotlin.uuid.Uuid

class MemoryExclusionMigrationTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java,
        listOf(Migration_8_9(), Migration_16_17(), Migration_22_23()), FrameworkSQLiteOpenHelperFactory())

    @Test fun upgradePreservesExistingMemoriesAndAddsExclusionTable() {
        val name = "memory-migration-${Uuid.random()}.db"
        try {
            helper.createDatabase(name, 41).apply {
                execSQL("INSERT INTO memoryentity(id, assistant_id, content, memory_type, created_at, source_conversation_id, lifecycle_state, lifecycle_updated_at) VALUES (7, 'assistant', 'Keep this event', 'episodic', 100, 'conversation', 'active', 100)")
                close()
            }
            helper.runMigrationsAndValidate(name, 42, true).apply {
                query("SELECT content, source_conversation_id FROM memoryentity WHERE id=7").use {
                    assertTrue(it.moveToFirst()); assertEquals("Keep this event", it.getString(0)); assertEquals("conversation", it.getString(1))
                }
                query("SELECT COUNT(*) FROM conversation_memory_exclusion").use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
                close()
            }
        } finally { InstrumentationRegistry.getInstrumentation().targetContext.deleteDatabase(name) }
    }
}
