package me.rerere.rikkahub

import android.os.CancellationSignal
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.platform.app.InstrumentationRegistry
import io.requery.android.database.AbstractWindowedCursor
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory
import me.rerere.rikkahub.data.db.CHAT_CURSOR_WINDOW_BYTES
import me.rerere.rikkahub.data.db.LargeRowOpenHelperFactory
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class LargeRowDatabaseTest {
    @Test fun largeRowsSurviveAllQueryPathsCallbacksAndReopen() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "large-row-regression-${UUID.randomUUID()}.db"
        val payload = "记忆😀".repeat(600_000)
        var callbackDb: SupportSQLiteDatabase? = null
        var opens = 0
        val helper = LargeRowOpenHelperFactory(RequerySQLiteOpenHelperFactory()).create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE message_sample (id INTEGER PRIMARY KEY, body TEXT NOT NULL)")
                        db.execSQL("INSERT INTO message_sample VALUES (?, ?)", arrayOf<Any>(1, payload))
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        callbackDb = db
                        opens++
                        db.query("SELECT body FROM message_sample").use { cursor ->
                            assertTrue(cursor.moveToFirst())
                            assertEquals(payload, cursor.getString(0))
                        }
                    }
                }).build())
        try {
            repeat(2) {
                val database = helper.writableDatabase
                assertSame(callbackDb, database)
                assertSame(database, helper.readableDatabase)
                val sql = "SELECT body FROM message_sample WHERE id = ?"
                listOf(
                    { database.query("SELECT body FROM message_sample") },
                    { database.query(sql, arrayOf(1)) },
                    { database.query(SimpleSQLiteQuery(sql, arrayOf(1))) },
                    { database.query(SimpleSQLiteQuery(sql, arrayOf(1)), CancellationSignal()) },
                ).forEach { query -> query().use {
                    assertTrue(it.moveToFirst())
                    assertEquals(payload, it.getString(0))
                    assertEquals(CHAT_CURSOR_WINDOW_BYTES, (it as AbstractWindowedCursor).window.windowSizeBytes)
                } }
                database.query("SELECT 1").use { cursor -> assertTrue(cursor.moveToFirst()); assertEquals(1, cursor.getInt(0)) }
                helper.close()
            }
            assertEquals(2, opens)
        } finally {
            helper.close()
            context.deleteDatabase(name)
        }
    }
}
