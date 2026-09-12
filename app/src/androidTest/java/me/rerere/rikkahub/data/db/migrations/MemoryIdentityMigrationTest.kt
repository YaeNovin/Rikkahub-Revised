package me.rerere.rikkahub.data.db.migrations

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.model.memoryContentHash
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import kotlin.uuid.Uuid

class MemoryIdentityMigrationTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java,
        listOf(Migration_8_9(), Migration_16_17(), Migration_22_23()), FrameworkSQLiteOpenHelperFactory())

    @Test fun upgradesStableIdentitiesWithoutLosingRowsOrLinks() {
        val name = "identity-upgrade-${Uuid.random()}.db"
        try {
            helper.createDatabase(name, 42).apply {
                execSQL("INSERT INTO memoryentity(id,assistant_id,content,memory_type,source_conversation_id,superseded_by_memory_id) VALUES (10,'a','旧事件','episodic','c',11)")
                execSQL("INSERT INTO memoryentity(id,assistant_id,content,memory_type,source_conversation_id) VALUES (11,'a','新事件','episodic','c')")
                execSQL("INSERT INTO memoryentity(id,assistant_id,content,memory_type) VALUES (12,'__global__','事实','fact'),(13,'a','未知来源','episodic')")
                close()
            }
            helper.runMigrationsAndValidate(name, 43, true, Migration_42_43).apply {
                val ids = mutableSetOf<String>()
                query("SELECT id,uid,content,content_hash,scope_type,scope_id,revision,superseded_by_uid FROM MemoryEntity ORDER BY id").use { cursor ->
                    while (cursor.moveToNext()) {
                        val uid = cursor.getString(1); assertNotNull(Uuid.parse(uid)); assertTrue(ids.add(uid))
                        assertEquals(memoryContentHash(cursor.getString(2)), cursor.getString(3)); assertEquals(1L, cursor.getLong(6))
                        when(cursor.getInt(0)) {
                            10 -> { assertEquals("conversation", cursor.getString(4)); assertEquals("c", cursor.getString(5)); assertFalse(cursor.isNull(7)) }
                            12 -> assertEquals("global", cursor.getString(4))
                            13 -> assertEquals("unassigned", cursor.getString(4))
                        }
                    }
                }
                assertEquals(4, ids.size)
                query("SELECT COUNT(*) FROM memory_source").use { assertTrue(it.moveToFirst()); assertEquals(2, it.getInt(0)) }
                query("SELECT COUNT(*) FROM MemoryEntity a JOIN MemoryEntity b ON a.superseded_by_uid=b.uid WHERE a.id=10 AND b.id=11").use { assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0)) }
                close()
            }
        } finally { InstrumentationRegistry.getInstrumentation().targetContext.deleteDatabase(name) }
    }
}
