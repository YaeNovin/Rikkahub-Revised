package me.rerere.rikkahub.data.datastore

import me.rerere.rikkahub.data.ai.DEFAULT_GENERATION_RETRY_COUNT
import me.rerere.rikkahub.data.ai.DEFAULT_GENERATION_RETRY_DURATION_SECONDS
import me.rerere.rikkahub.data.ai.DEFAULT_GENERATION_RETRY_INTERVAL_SECONDS
import me.rerere.rikkahub.data.ai.MAX_GENERATION_RETRY_COUNT
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsRetryDefaultTest {
    @Test
    fun `generation retry is enabled by default`() {
        val settings = Settings()

        assertTrue(settings.enableGenerationRetry)
        assertEquals(DEFAULT_GENERATION_RETRY_COUNT, settings.generationRetryMaxRetries)
        assertEquals(
            DEFAULT_GENERATION_RETRY_INTERVAL_SECONDS,
            settings.generationRetryInitialIntervalSeconds,
        )
        assertEquals(
            DEFAULT_GENERATION_RETRY_DURATION_SECONDS,
            settings.generationRetryMaxDurationSeconds,
        )
        assertEquals(7, MAX_GENERATION_RETRY_COUNT)
    }
}
