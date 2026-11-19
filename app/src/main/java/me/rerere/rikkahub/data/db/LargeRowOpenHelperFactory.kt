package me.rerere.rikkahub.data.db

import android.database.Cursor
import android.os.CancellationSignal
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.SupportSQLiteQuery
import io.requery.android.database.AbstractWindowedCursor
import io.requery.android.database.CursorWindow

internal const val CHAT_CURSOR_WINDOW_BYTES = 32 * 1024 * 1024

/** Configure the cursor Room actually reads, through Requery's public window API. */
internal class LargeRowOpenHelperFactory(private val factory: SupportSQLiteOpenHelper.Factory) : SupportSQLiteOpenHelper.Factory {
    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
        val cache = DatabaseWrapperCache()
        val callback = configuration.callback
        val wrappedCallback = object : SupportSQLiteOpenHelper.Callback(callback.version) {
            override fun onConfigure(db: SupportSQLiteDatabase) = callback.onConfigure(cache.wrap(db))
            override fun onCreate(db: SupportSQLiteDatabase) = callback.onCreate(cache.wrap(db))
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                callback.onUpgrade(cache.wrap(db), oldVersion, newVersion)
            override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                callback.onDowngrade(cache.wrap(db), oldVersion, newVersion)
            override fun onOpen(db: SupportSQLiteDatabase) = callback.onOpen(cache.wrap(db))
            override fun onCorruption(db: SupportSQLiteDatabase) = callback.onCorruption(cache.wrap(db))
        }
        val delegate = factory.create(SupportSQLiteOpenHelper.Configuration.builder(configuration.context)
            .name(configuration.name)
            .callback(wrappedCallback)
            .noBackupDirectory(configuration.useNoBackupDirectory)
            .allowDataLossOnRecovery(configuration.allowDataLossOnRecovery)
            .build())
        return object : SupportSQLiteOpenHelper by delegate {
            override val writableDatabase: SupportSQLiteDatabase get() = cache.wrap(delegate.writableDatabase)
            override val readableDatabase: SupportSQLiteDatabase get() = cache.wrap(delegate.readableDatabase)
        }
    }
}

private class DatabaseWrapperCache {
    private var source: SupportSQLiteDatabase? = null
    private var wrapped: SupportSQLiteDatabase? = null

    @Synchronized
    fun wrap(database: SupportSQLiteDatabase): SupportSQLiteDatabase {
        if (source !== database) {
            source = database
            wrapped = LargeRowDatabase(database)
        }
        return requireNotNull(wrapped)
    }
}

private class LargeRowDatabase(private val delegate: SupportSQLiteDatabase) : SupportSQLiteDatabase by delegate {
    override fun query(query: String): Cursor = delegate.query(query).withChatWindow()
    override fun query(query: String, bindArgs: Array<out Any?>): Cursor = delegate.query(query, bindArgs).withChatWindow()
    override fun query(query: SupportSQLiteQuery): Cursor = delegate.query(query).withChatWindow()
    override fun query(query: SupportSQLiteQuery, cancellationSignal: CancellationSignal?): Cursor =
        delegate.query(query, cancellationSignal).withChatWindow()
}

private fun Cursor.withChatWindow(): Cursor {
    if (this !is AbstractWindowedCursor) return this
    try {
        // Each cursor owns and closes its bounded window. No global hidden API or query retry.
        window = CursorWindow("rikkahub", CHAT_CURSOR_WINDOW_BYTES)
        return this
    } catch (error: Throwable) {
        close()
        throw error
    }
}
