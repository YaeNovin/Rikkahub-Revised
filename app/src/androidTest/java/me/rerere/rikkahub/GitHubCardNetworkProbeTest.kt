package me.rerere.rikkahub

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.github.GitHubCardStatus
import me.rerere.rikkahub.data.github.GitHubRepository
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicit opt-in, one public API lookup with a temporary Room cache, no user credentials. */
class GitHubCardNetworkProbeTest {
    @Test(timeout = 30_000) fun publicApiWorksFromAndroidAndPersistsCache() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("githubNetworkProbe") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val repository = GitHubRepository(database.githubRepositoryCacheDao())
            val observed = async { withTimeout(25_000) { repository.observe("YaeNovin/Rikkahub-Revised").first { it.data != null } } }
            val result = repository.get("YaeNovin/Rikkahub-Revised")
            assertEquals("API result: ${result.status}, ${result.failure}, HTTP ${result.httpStatus}, retryAt=${result.retryAt}",
                GitHubCardStatus.FRESH, result.status)
            assertEquals("YaeNovin/Rikkahub-Revised", result.data?.fullName)
            assertNotNull(result.data?.description)
            assertEquals(result.data, observed.await().data)
            val persisted = GitHubRepository(database.githubRepositoryCacheDao()).get("yaenovin/rikkahub-revised")
            assertEquals(result.data, persisted.data)
            assertEquals(1, database.githubRepositoryCacheDao().apiState()!!.requests)
        } finally { database.close() }
    }
}
