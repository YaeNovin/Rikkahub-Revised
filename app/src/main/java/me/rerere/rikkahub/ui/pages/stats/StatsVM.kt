package me.rerere.rikkahub.ui.pages.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.db.dao.ConversationDAO
import me.rerere.rikkahub.data.db.dao.MessageNodeDAO
import me.rerere.rikkahub.data.db.dao.RequestStatDAO
import me.rerere.rikkahub.data.db.dao.getMessageCountPerDay
import me.rerere.rikkahub.data.db.dao.getTokenStats
import me.rerere.rikkahub.data.db.dao.getUntrackedHistoricalRequests
import me.rerere.rikkahub.data.db.entity.RequestStatEntity
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import kotlin.uuid.Uuid

data class AppStats(
    val isLoading: Boolean = true,
    val totalConversations: Int = 0,
    val totalMessages: Int = 0,
    val totalPromptTokens: Long = 0L,
    val totalCompletionTokens: Long = 0L,
    val totalCachedTokens: Long = 0L,
    val conversationsPerDay: Map<LocalDate, Int> = emptyMap(),
    val launchCount: Int = 0,
    val requestCount: Int = 0,
    val usedModels: Int = 0,
    val configuredProviders: Int = 0,
    val enabledProviders: Int = 0,
    val enabledApiKeys: Int = 0,
    val apiKeyProviders: Int = 0,
    val configuredModels: Int = 0,
) {
    val totalTokens: Long
        get() = totalPromptTokens + totalCompletionTokens
}

internal data class DashboardConfigurationStats(
    val configuredProviders: Int,
    val enabledProviders: Int,
    val enabledApiKeys: Int,
    val apiKeyProviders: Int,
    val configuredModels: Int,
)

enum class StatsRangePreset {
    DEFAULT_SEVEN_DAYS,
    LAST_24_HOURS,
    LAST_3_DAYS,
    LAST_14_DAYS,
    LAST_30_DAYS,
    CUSTOM,
}

data class StatsTimeRange(
    val preset: StatsRangePreset = StatsRangePreset.DEFAULT_SEVEN_DAYS,
    val customStart: LocalDate? = null,
    val customEnd: LocalDate? = null,
)

data class StatsFilter(
    val timeRange: StatsTimeRange = StatsTimeRange(),
    val providers: Set<String> = emptySet(),
)

data class RequestStatsSummary(
    val totalTokens: Long = 0,
    val requestCount: Int = 0,
    val messageCount: Int = 0,
    val averageResponseNanos: Long? = null,
)

data class StatsDetailState(
    val isInitializing: Boolean = true,
    val providerOptions: List<String> = emptyList(),
    val summaryFilter: StatsFilter = StatsFilter(),
    val tableFilter: StatsFilter = StatsFilter(),
    val summary: RequestStatsSummary = RequestStatsSummary(),
    val requests: List<RequestStatEntity> = emptyList(),
    val isSummaryLoading: Boolean = false,
    val isTableLoading: Boolean = false,
    val summaryError: String? = null,
    val tableError: String? = null,
)

internal data class StatsTimeBounds(
    val startMillis: Long,
    val endMillis: Long,
)

class StatsVM(
    private val conversationDAO: ConversationDAO,
    private val messageNodeDAO: MessageNodeDAO,
    private val settingsStore: SettingsStore,
    private val requestStatDAO: RequestStatDAO,
) : ViewModel() {

    private val _stats = MutableStateFlow(AppStats())
    val stats = _stats.asStateFlow()

    private val _detailState = MutableStateFlow(StatsDetailState())
    val detailState = _detailState.asStateFlow()

    private var summaryJob: Job? = null
    private var tableJob: Job? = null

    init {
        viewModelScope.launch {
            delay(50)
            val settings = settingsStore.settingsFlow.value
            runCatching { backfillHistoricalRequests(settings) }
            loadStats(settings)
            val recordedProviders = withContext(Dispatchers.IO) {
                requestStatDAO.getRecordedProviders()
            }
            val providerOptions = (
                settings.providers.filter(ProviderSetting::isConfigured)
                    .map(ProviderSetting::displayName) + recordedProviders
                ).filter(String::isNotBlank)
                .distinctBy { it.lowercase() }
                .sortedBy { it.lowercase() }
            _detailState.update {
                it.copy(
                    isInitializing = false,
                    providerOptions = providerOptions,
                )
            }
            refreshSummary()
            refreshTable()
        }
    }

    fun setSummaryTimeRange(timeRange: StatsTimeRange) {
        _detailState.update { it.copy(summaryFilter = it.summaryFilter.copy(timeRange = timeRange)) }
        refreshSummary()
    }

    fun setSummaryProviders(providers: Set<String>) {
        _detailState.update { it.copy(summaryFilter = it.summaryFilter.copy(providers = providers)) }
        refreshSummary()
    }

    fun setTableTimeRange(timeRange: StatsTimeRange) {
        _detailState.update { it.copy(tableFilter = it.tableFilter.copy(timeRange = timeRange)) }
        refreshTable()
    }

    fun setTableProviders(providers: Set<String>) {
        _detailState.update { it.copy(tableFilter = it.tableFilter.copy(providers = providers)) }
        refreshTable()
    }

    fun refreshDetails() {
        refreshSummary()
        refreshTable()
    }

    private suspend fun loadStats(settings: Settings) {
        val today = LocalDate.now()
        val startDate = today
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))
            .minusWeeks(52)
            .toString()

        val conversationsPerDay = withContext(Dispatchers.IO) {
            messageNodeDAO
                .getMessageCountPerDay(startDate)
                .mapNotNull { entry ->
                    runCatching { LocalDate.parse(entry.day) to entry.count }.getOrNull()
                }
                .toMap()
        }
        val totalConversations = conversationDAO.countAll()
        val tokenStats = messageNodeDAO.getTokenStats()
        val configurationStats = settings.providers.dashboardConfigurationStats()
        val persistedRequestCount = requestStatDAO.countAll()

        _stats.value = AppStats(
            isLoading = false,
            totalConversations = totalConversations,
            totalMessages = tokenStats.totalMessages,
            totalPromptTokens = tokenStats.promptTokens,
            totalCompletionTokens = tokenStats.completionTokens,
            totalCachedTokens = tokenStats.cachedTokens,
            conversationsPerDay = conversationsPerDay,
            launchCount = settings.launchCount,
            requestCount = maxOf(persistedRequestCount, tokenStats.requestCount),
            usedModels = tokenStats.usedModels,
            configuredProviders = configurationStats.configuredProviders,
            enabledProviders = configurationStats.enabledProviders,
            enabledApiKeys = configurationStats.enabledApiKeys,
            apiKeyProviders = configurationStats.apiKeyProviders,
            configuredModels = configurationStats.configuredModels,
        )
    }

    private suspend fun backfillHistoricalRequests(settings: Settings) = withContext(Dispatchers.IO) {
        // Historical messages prove completion, but they do not preserve the original HTTP code.
        requestStatDAO.clearInferredHistoricalStatusCodes()
        val zoneId = ZoneId.systemDefault()
        val entries = requestStatDAO.getUntrackedHistoricalRequests().mapNotNull { historical ->
            val modelUuid = runCatching { Uuid.parse(historical.modelId) }.getOrNull()
                ?: return@mapNotNull null
            val model = settings.findModelById(modelUuid) ?: return@mapNotNull null
            val provider = model.findProvider(settings.providers) ?: return@mapNotNull null
            val createdAt = historical.createdAt.toEpochMillis(zoneId) ?: return@mapNotNull null
            val finishedAt = historical.finishedAt?.toEpochMillis(zoneId)
            val durationMs = finishedAt?.minus(createdAt)?.coerceAtLeast(0L)
            RequestStatEntity(
                id = "message:${historical.messageId}",
                messageId = historical.messageId,
                timestamp = createdAt,
                provider = provider.displayName(),
                model = model.modelId.ifBlank { model.displayName },
                operation = "HISTORICAL_TEXT",
                promptTokens = historical.promptTokens,
                completionTokens = historical.completionTokens,
                cachedTokens = historical.cachedTokens,
                messageCount = 1,
                statusCode = null,
                durationMs = durationMs,
                completedAt = finishedAt,
            )
        }
        if (entries.isNotEmpty()) requestStatDAO.insertAll(entries)
    }

    private fun refreshSummary() {
        summaryJob?.cancel()
        val filter = _detailState.value.summaryFilter
        summaryJob = viewModelScope.launch {
            _detailState.update { it.copy(isSummaryLoading = true, summaryError = null) }
            runCatching { queryRequests(filter) }
                .onSuccess { requests ->
                    if (_detailState.value.summaryFilter == filter) {
                        _detailState.update {
                            it.copy(
                                summary = requests.toSummary(),
                                isSummaryLoading = false,
                            )
                        }
                    }
                }
                .onFailure { error ->
                    if (_detailState.value.summaryFilter == filter) {
                        _detailState.update {
                            it.copy(
                                isSummaryLoading = false,
                                summaryError = error.message ?: error::class.java.simpleName,
                            )
                        }
                    }
                }
        }
    }

    private fun refreshTable() {
        tableJob?.cancel()
        val filter = _detailState.value.tableFilter
        tableJob = viewModelScope.launch {
            _detailState.update { it.copy(isTableLoading = true, tableError = null) }
            runCatching { queryRequests(filter) }
                .onSuccess { requests ->
                    if (_detailState.value.tableFilter == filter) {
                        _detailState.update {
                            it.copy(
                                requests = requests,
                                isTableLoading = false,
                            )
                        }
                    }
                }
                .onFailure { error ->
                    if (_detailState.value.tableFilter == filter) {
                        _detailState.update {
                            it.copy(
                                isTableLoading = false,
                                tableError = error.message ?: error::class.java.simpleName,
                            )
                        }
                    }
                }
        }
    }

    private suspend fun queryRequests(filter: StatsFilter): List<RequestStatEntity> {
        val bounds = filter.timeRange.toBounds()
        val providers = filter.providers.toList().ifEmpty { listOf("") }
        return withContext(Dispatchers.IO) {
            requestStatDAO.getRequests(
                startMillis = bounds.startMillis,
                endMillis = bounds.endMillis,
                allProviders = filter.providers.isEmpty(),
                providers = providers,
            )
        }
    }
}

internal fun StatsTimeRange.toBounds(
    nowMillis: Long = System.currentTimeMillis(),
    zoneId: ZoneId = ZoneId.systemDefault(),
): StatsTimeBounds {
    val duration = when (preset) {
        StatsRangePreset.DEFAULT_SEVEN_DAYS -> Duration.ofDays(7)
        StatsRangePreset.LAST_24_HOURS -> Duration.ofHours(24)
        StatsRangePreset.LAST_3_DAYS -> Duration.ofDays(3)
        StatsRangePreset.LAST_14_DAYS -> Duration.ofDays(14)
        StatsRangePreset.LAST_30_DAYS -> Duration.ofDays(30)
        StatsRangePreset.CUSTOM -> null
    }
    if (duration != null) {
        return StatsTimeBounds(
            startMillis = (nowMillis - duration.toMillis()).coerceAtLeast(0L),
            endMillis = nowMillis + 1L,
        )
    }

    val start = customStart ?: LocalDate.now(zoneId)
    val end = (customEnd ?: start).coerceAtLeast(start)
    return StatsTimeBounds(
        startMillis = start.atStartOfDay(zoneId).toInstant().toEpochMilli(),
        endMillis = end.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli(),
    )
}

internal fun List<RequestStatEntity>.toSummary(): RequestStatsSummary {
    val responseTimes = mapNotNull(RequestStatEntity::effectiveTotalDurationNanos)
    return RequestStatsSummary(
        totalTokens = sumOf(RequestStatEntity::totalTokens),
        requestCount = size,
        messageCount = sumOf(RequestStatEntity::messageCount),
        averageResponseNanos = responseTimes.takeIf(List<Long>::isNotEmpty)?.average()?.toLong(),
    )
}

internal data class StatsCsvHeaders(
    val timestamp: String = "Timestamp",
    val provider: String = "Provider",
    val model: String = "Model",
    val reasoningDepth: String = "Reasoning depth",
    val promptTokens: String = "Input tokens",
    val completionTokens: String = "Output tokens",
    val cachedTokens: String = "Cached tokens",
    val totalTokens: String = "Total tokens",
    val firstTokenMs: String = "First token (ms)",
    val statusCode: String = "HTTP status",
    val totalDurationMs: String = "Total duration (ms)",
)

internal fun buildRequestStatsCsv(
    requests: List<RequestStatEntity>,
    headers: StatsCsvHeaders = StatsCsvHeaders(),
    zoneId: ZoneId = ZoneId.systemDefault(),
): String {
    val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    return buildString {
        appendLine(
            listOf(
                headers.timestamp,
                headers.provider,
                headers.model,
                headers.reasoningDepth,
                headers.promptTokens,
                headers.completionTokens,
                headers.cachedTokens,
                headers.totalTokens,
                headers.firstTokenMs,
                headers.totalDurationMs,
                headers.statusCode,
            ).joinToString(",", transform = String::csvCell)
        )
        requests.forEach { request ->
            appendLine(
                listOf(
                    java.time.Instant.ofEpochMilli(request.completedAt ?: request.timestamp)
                        .atZone(zoneId)
                        .format(formatter),
                    request.provider,
                    request.model,
                    request.reasoningDepth,
                    request.promptTokens.toString(),
                    request.completionTokens.toString(),
                    request.cachedTokens.toString(),
                    request.totalTokens.toString(),
                    request.firstTokenNanos.csvMilliseconds(),
                    request.effectiveTotalDurationNanos.csvMilliseconds(),
                    request.statusCode?.toString().orEmpty(),
                ).joinToString(",", transform = String::csvCell)
            )
        }
    }
}

private fun Long?.csvMilliseconds(): String = this?.let {
    String.format(java.util.Locale.US, "%.3f", it / 1_000_000.0)
}.orEmpty()

private fun String.csvCell(): String = if (any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
    "\"${replace("\"", "\"\"")}\""
} else {
    this
}

private fun String.toEpochMillis(zoneId: ZoneId): Long? = runCatching {
    LocalDateTime.parse(this).atZone(zoneId).toInstant().toEpochMilli()
}.getOrNull()

internal fun List<ProviderSetting>.dashboardConfigurationStats(): DashboardConfigurationStats {
    val configured = filter(ProviderSetting::isConfigured)
    val enabledWithKeys = filter { provider -> provider.enabled && provider.apiKeys().isNotEmpty() }
    return DashboardConfigurationStats(
        configuredProviders = configured.size,
        enabledProviders = configured.count(ProviderSetting::enabled),
        enabledApiKeys = enabledWithKeys.sumOf { it.apiKeys().size },
        apiKeyProviders = enabledWithKeys.size,
        configuredModels = flatMap(ProviderSetting::models).distinctBy { it.id }.size,
    )
}

private fun ProviderSetting.apiKeys(): List<String> {
    val raw = when (this) {
        is ProviderSetting.OpenAI -> apiKey
        is ProviderSetting.Google -> apiKey
        is ProviderSetting.Claude -> apiKey
    }
    return raw.split(API_KEY_SEPARATOR)
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()
}

private fun ProviderSetting.isConfigured(): Boolean =
    !builtIn || models.isNotEmpty() || apiKeys().isNotEmpty()

private fun ProviderSetting.displayName(): String = name.ifBlank {
    when (this) {
        is ProviderSetting.OpenAI -> "OpenAI"
        is ProviderSetting.Google -> "Google"
        is ProviderSetting.Claude -> "Claude"
    }
}

private val API_KEY_SEPARATOR = Regex("[\\s,]+")
