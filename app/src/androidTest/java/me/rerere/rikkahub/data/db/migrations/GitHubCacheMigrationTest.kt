package me.rerere.rikkahub.data.db.migrations

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.db.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class GitHubCacheMigrationTest {
    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java,
        listOf(Migration_8_9(), Migration_16_17(), Migration_22_23()), FrameworkSQLiteOpenHelperFactory(),
    )

    @Test fun migrationAddsCacheWithoutLosingExistingFolders() {
        val name = "github-cache-migration"
        helper.createDatabase(name, 40).apply {
            execSQL("INSERT INTO conversation_folder (id, assistant_id, name, sort_index, create_at) VALUES ('existing', 'assistant', 'Keep me', 2, 100)")
            close()
        }
        helper.runMigrationsAndValidate(name, 41, true).apply {
            query("SELECT name FROM conversation_folder WHERE id = 'existing'").use {
                assertTrue(it.moveToFirst()); assertEquals("Keep me", it.getString(0))
            }
            execSQL("INSERT INTO github_repository_cache (fullName,payload,etag,fetchedAt,refreshAfter,lastAccessAt,error) VALUES ('owner/repo',NULL,NULL,0,12000,10000,'unavailable')")
            execSQL("INSERT INTO github_api_cache_state (id,blockedUntil,windowStartedAt,requests) VALUES ('public',12000,10000,1)")
            query("SELECT blockedUntil FROM github_api_cache_state WHERE id='public'").use {
                assertTrue(it.moveToFirst()); assertEquals(12000L, it.getLong(0))
            }
            close()
        }
    }
}
