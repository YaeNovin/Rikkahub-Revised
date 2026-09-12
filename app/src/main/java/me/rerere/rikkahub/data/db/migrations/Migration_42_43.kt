package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.rerere.rikkahub.data.model.memoryContentHash
import java.util.UUID

val Migration_42_43 = object : Migration(42, 43) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE MemoryEntity ADD COLUMN uid TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE MemoryEntity ADD COLUMN revision INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE MemoryEntity ADD COLUMN content_hash TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE MemoryEntity ADD COLUMN scope_type TEXT NOT NULL DEFAULT 'assistant'")
        db.execSQL("ALTER TABLE MemoryEntity ADD COLUMN scope_id TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE MemoryEntity ADD COLUMN superseded_by_uid TEXT")
        db.execSQL("ALTER TABLE MemoryEntity ADD COLUMN embedding_key TEXT")
        db.execSQL("ALTER TABLE MemoryEntity ADD COLUMN created_by_run_id TEXT")
        db.query("SELECT id, content FROM MemoryEntity").use { cursor ->
            while (cursor.moveToNext()) db.execSQL("UPDATE MemoryEntity SET uid = ?, content_hash = ? WHERE id = ?",
                arrayOf<Any>(UUID.randomUUID().toString(), memoryContentHash(cursor.getString(1)), cursor.getInt(0)))
        }
        db.execSQL("UPDATE MemoryEntity SET scope_type = CASE WHEN memory_type = 'episodic' THEN CASE WHEN source_conversation_id IS NULL OR source_conversation_id = '' THEN 'unassigned' ELSE 'conversation' END WHEN assistant_id = '__global__' THEN 'global' ELSE 'assistant' END, scope_id = CASE WHEN memory_type = 'episodic' THEN COALESCE(source_conversation_id, '') ELSE assistant_id END")
        db.execSQL("UPDATE MemoryEntity SET superseded_by_uid = (SELECT replacement.uid FROM MemoryEntity AS replacement WHERE replacement.id = MemoryEntity.superseded_by_memory_id)")
        db.execSQL("CREATE UNIQUE INDEX index_MemoryEntity_uid ON MemoryEntity(uid)")
        db.execSQL("CREATE INDEX index_MemoryEntity_scope_type_scope_id ON MemoryEntity(scope_type, scope_id)")
        db.execSQL("ALTER TABLE conversation_memory_chunk ADD COLUMN embedding_key TEXT")
        db.execSQL("CREATE TABLE IF NOT EXISTS memory_source (memory_uid TEXT NOT NULL, conversation_id TEXT NOT NULL, message_id TEXT NOT NULL, PRIMARY KEY(memory_uid, conversation_id, message_id), FOREIGN KEY(memory_uid) REFERENCES MemoryEntity(uid) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX index_memory_source_conversation_id ON memory_source(conversation_id)")
        db.execSQL("INSERT INTO memory_source SELECT uid, source_conversation_id, '' FROM MemoryEntity WHERE source_conversation_id IS NOT NULL AND source_conversation_id != ''")
        db.execSQL("CREATE TABLE IF NOT EXISTS memory_deletion (uid TEXT NOT NULL PRIMARY KEY, scope_type TEXT NOT NULL, scope_id TEXT NOT NULL, revision INTEGER NOT NULL, deleted_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS memory_run (id TEXT NOT NULL PRIMARY KEY, conversation_id TEXT, model_id TEXT NOT NULL, operation TEXT NOT NULL, status TEXT NOT NULL, input_hash TEXT NOT NULL, started_at INTEGER NOT NULL, finished_at INTEGER, duration_ms INTEGER, request_id TEXT)")
        db.execSQL("CREATE INDEX index_memory_run_conversation_id ON memory_run(conversation_id)")
    }
}
