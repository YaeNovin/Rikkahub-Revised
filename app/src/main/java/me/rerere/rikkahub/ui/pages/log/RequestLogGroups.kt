package me.rerere.rikkahub.ui.pages.log

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.common.android.LogEntry
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.Locale

internal data class RequestLogGroup(val key: String, val entries: List<LogEntry>)

internal fun groupRequestLogs(logs: List<LogEntry>): List<RequestLogGroup> = logs
    .sortedByDescending { it.timestamp }
    .groupBy { log ->
        if (log is LogEntry.ProviderRequestLog && log.provider.isNotBlank() && log.model.isNotBlank() &&
            log.method?.uppercase(Locale.ROOT) in setOf("POST", "GET")
        ) {
            val url = log.url?.toHttpUrlOrNull()
            // Keep distinct endpoints/protocols separate even if provider display names match.
            val endpoint = url?.let { "${it.scheme}://${it.host}:${it.port}" }
            if (log.url != null && endpoint == null) "single:${log.id}"
            else "group:" + Json.encodeToString(listOf(log.provider, log.model, log.channel, endpoint))
        } else "single:${log.id}"
    }
    .map { (key, entries) -> RequestLogGroup(key, entries) }
