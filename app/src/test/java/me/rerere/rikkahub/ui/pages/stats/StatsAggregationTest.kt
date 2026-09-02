package me.rerere.rikkahub.ui.pages.stats

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.db.entity.RequestStatEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class StatsAggregationTest {
    @Test
    fun `configuration summary excludes untouched built in templates`() {
        val providers = listOf(
            ProviderSetting.OpenAI(builtIn = true, enabled = true),
            ProviderSetting.Google(
                builtIn = true,
                enabled = true,
                apiKey = "key-a, key-b\nkey-a",
                models = listOf(Model(modelId = "gemini-3-flash")),
            ),
            ProviderSetting.Claude(
                builtIn = false,
                enabled = false,
                models = listOf(Model(modelId = "claude-sonnet-4-6")),
            ),
        )

        val stats = providers.dashboardConfigurationStats()

        assertEquals(2, stats.configuredProviders)
        assertEquals(1, stats.enabledProviders)
        assertEquals(2, stats.enabledApiKeys)
        assertEquals(1, stats.apiKeyProviders)
        assertEquals(2, stats.configuredModels)
    }

    @Test
    fun `configuration summary counts only keys from enabled providers`() {
        val providers = listOf(
            ProviderSetting.OpenAI(
                builtIn = false,
                enabled = true,
                apiKey = "enabled-key enabled-key, second-key",
            ),
            ProviderSetting.Google(
                builtIn = false,
                enabled = false,
                apiKey = "disabled-key",
            ),
            ProviderSetting.Claude(
                builtIn = false,
                enabled = true,
            ),
        )

        val stats = providers.dashboardConfigurationStats()

        assertEquals(3, stats.configuredProviders)
        assertEquals(2, stats.enabledProviders)
        assertEquals(2, stats.enabledApiKeys)
        assertEquals(1, stats.apiKeyProviders)
    }

    @Test
    fun `dashboard values use compact stable formatting`() {
        assertEquals("999", formatCount(999))
        assertEquals("1.0K", formatCount(1_000))
        assertEquals("1.5M", formatCount(1_500_000))

        assertEquals("999", formatTokens(999))
        assertEquals("1.5K", formatTokens(1_500))
        assertEquals("2.50M", formatTokens(2_500_000))
        assertEquals("3.25B", formatTokens(3_250_000_000))
    }

    @Test
    fun `time ranges resolve presets and inclusive custom dates`() {
        val now = 1_800_000_000_000L
        val recent = StatsTimeRange(StatsRangePreset.LAST_24_HOURS)
            .toBounds(nowMillis = now, zoneId = ZoneOffset.UTC)
        assertEquals(now - 24L * 60L * 60L * 1_000L, recent.startMillis)
        assertEquals(now + 1L, recent.endMillis)

        val custom = StatsTimeRange(
            preset = StatsRangePreset.CUSTOM,
            customStart = LocalDate.of(2026, 8, 1),
            customEnd = LocalDate.of(2026, 8, 3),
        ).toBounds(nowMillis = now, zoneId = ZoneOffset.UTC)
        assertEquals(
            LocalDate.of(2026, 8, 1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            custom.startMillis,
        )
        assertEquals(
            LocalDate.of(2026, 8, 4).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            custom.endMillis,
        )
    }

    @Test
    fun `request summary aggregates tokens messages and response time`() {
        val requests = listOf(
            requestStat(id = "one", prompt = 100, completion = 25, duration = 100, messages = 1),
            requestStat(id = "two", prompt = 200, completion = 75, duration = 300, messages = 1),
            requestStat(id = "three", prompt = 0, completion = 0, duration = null, messages = 0),
        )

        val summary = requests.toSummary()

        assertEquals(400L, summary.totalTokens)
        assertEquals(3, summary.requestCount)
        assertEquals(2, summary.messageCount)
        assertEquals(200_000_000L, summary.averageResponseNanos)
    }

    @Test
    fun `csv export escapes provider and model names`() {
        val csv = buildRequestStatsCsv(
            requests = listOf(
                requestStat(
                    id = "csv",
                    provider = "Provider, Inc.",
                    model = "model\"quoted",
                    prompt = 10,
                    completion = 5,
                    duration = 250,
                    messages = 1,
                )
            ),
            zoneId = ZoneOffset.UTC,
        )

        assertTrue(csv.startsWith("Timestamp,Provider,Model"))
        assertTrue(csv.contains("\"Provider, Inc.\""))
        assertTrue(csv.contains("\"model\"\"quoted\""))
        assertTrue(csv.contains(",10,5,0,15,,250.000,200"))
    }

    @Test
    fun `csv export preserves provider specific HTTP status codes`() {
        val csv = buildRequestStatsCsv(
            requests = listOf(
                requestStat("not-found", prompt = 0, completion = 0, duration = 10, messages = 0, statusCode = 404),
                requestStat("bad-gateway", prompt = 0, completion = 0, duration = 20, messages = 0, statusCode = 502),
                requestStat("stream-error", prompt = 0, completion = 0, duration = 30, messages = 0, statusCode = 503),
            ),
            zoneId = ZoneOffset.UTC,
        )

        assertTrue(csv.lineSequence().any { it.endsWith(",404") })
        assertTrue(csv.lineSequence().any { it.endsWith(",502") })
        assertTrue(csv.lineSequence().any { it.endsWith(",503") })
    }

    private fun requestStat(
        id: String,
        provider: String = "Provider",
        model: String = "model",
        prompt: Long,
        completion: Long,
        duration: Long?,
        messages: Int,
        statusCode: Int? = 200,
    ) = RequestStatEntity(
        id = id,
        timestamp = 0,
        provider = provider,
        model = model,
        operation = "STREAM_TEXT",
        promptTokens = prompt,
        completionTokens = completion,
        messageCount = messages,
        statusCode = statusCode,
        durationMs = duration,
    )
}
