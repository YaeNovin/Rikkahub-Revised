package me.rerere.rikkahub.ui.pages.log

import me.rerere.common.android.LogEntry
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal enum class DiagnosticStatus(val label: String) { ALL("全部"), ERROR("错误"), SUCCESS("成功") }
internal enum class DiagnosticPeriod(val label: String, val days: Long?) {
    ALL("全部日期", null), DAY("最近24小时", 1), WEEK("最近7天", 7), HALF_MONTH("最近15天", 15),
    MONTH("最近30天", 30), CUSTOM("自定义时间范围", null)
}
internal data class DiagnosticFilter(
    val period: DiagnosticPeriod = DiagnosticPeriod.ALL,
    val startDay: Long? = null,
    val endDay: Long? = null,
    val model: String? = null,
    val status: DiagnosticStatus = DiagnosticStatus.ALL,
    val type: String? = null,
)
internal data class DiagnosticProvider(val key: String, val name: String, val endpoint: String, val logs: List<LogEntry>)
internal data class DiagnosticIndex(val providers: List<DiagnosticProvider> = emptyList(), val requests: List<LogEntry> = emptyList(),
    val software: List<LogEntry> = emptyList(), val models: List<String> = emptyList(), val types: List<String> = emptyList())

internal fun LogEntry.endpoint(): String = runCatching {
    val uri = java.net.URI(when (this) {
        is LogEntry.ProviderRequestLog -> endpointOrigin ?: url.orEmpty()
        is LogEntry.RequestLog -> url
        else -> ""
    })
    uri.host?.let { "${uri.scheme}://$it" + if (uri.port >= 0 && !(uri.scheme == "https" && uri.port == 443) && !(uri.scheme == "http" && uri.port == 80)) ":${uri.port}" else "" }.orEmpty()
}.getOrDefault("")

internal fun LogEntry.providerLabel(): String = (this as? LogEntry.ProviderRequestLog)?.provider ?: "其他 HTTP 请求"
internal fun LogEntry.providerKey(): String = "${providerLabel().length}:${providerLabel()}${endpoint()}"
internal fun LogEntry.modelId(): String? = (this as? LogEntry.ProviderRequestLog)?.model
internal fun LogEntry.requestCode(): Int? = when (this) {
    is LogEntry.RequestLog -> responseCode
    is LogEntry.ProviderRequestLog -> responseCode
    else -> null
}
internal fun LogEntry.elapsedMs(): Long? = when (this) {
    is LogEntry.RequestLog -> durationMs
    is LogEntry.ProviderRequestLog -> durationMs
    else -> null
}
internal fun LogEntry.firstTokenMillis(): Double? = when (this) {
    is LogEntry.RequestLog -> firstTokenMs
    is LogEntry.ProviderRequestLog -> firstTokenMs
    else -> null
}
internal fun LogEntry.timingPhase(): String? = when (this) {
    is LogEntry.RequestLog -> durationPhase
    is LogEntry.ProviderRequestLog -> durationPhase
    else -> null
}
internal fun LogEntry.diagnosticType(): String = when (this) {
    is LogEntry.ProviderRequestLog -> "$channel / $operation"
    is LogEntry.RequestLog -> method
    else -> tag
}
internal fun LogEntry.statusLabel(): String = when {
    diagnostic?.outcome == me.rerere.common.android.DiagnosticOutcome.CANCELED -> "已取消"
    isFailure() -> "错误"
    diagnostic?.source == me.rerere.common.android.DiagnosticSource.PROCESS_EXIT -> "系统退出记录"
    diagnostic?.outcome == me.rerere.common.android.DiagnosticOutcome.INTERRUPTED -> "已中断"
    diagnostic?.severity == me.rerere.common.android.DiagnosticSeverity.WARNING -> "警告"
    diagnostic?.outcome == me.rerere.common.android.DiagnosticOutcome.IN_PROGRESS -> "进行中"
    diagnostic?.outcome == me.rerere.common.android.DiagnosticOutcome.UNKNOWN -> "未知"
    section() == LogSection.SOFTWARE -> "成功 / 信息"
    timingPhase() == "headers" -> "进行中"
    timingPhase() == "closed" -> "已关闭（未确认完成）"
    requestCode() in 200..399 -> "成功"
    else -> "未知"
}
internal fun LogEntry.matchesStatus(status: DiagnosticStatus): Boolean = when (status) {
    DiagnosticStatus.ALL -> true
    DiagnosticStatus.ERROR -> isFailure()
    DiagnosticStatus.SUCCESS -> !isFailure() && diagnostic?.severity != me.rerere.common.android.DiagnosticSeverity.WARNING &&
        (diagnostic == null || diagnostic?.outcome == me.rerere.common.android.DiagnosticOutcome.COMPLETED) && (section() == LogSection.SOFTWARE ||
        requestCode() in 200..399 && timingPhase() !in setOf("headers", "closed"))
}

internal fun DiagnosticFilter.matches(log: LogEntry, now: Long, zone: ZoneId = ZoneId.systemDefault()): Boolean {
    if (!log.matchesStatus(status) || model != null && log.modelId() != model || type != null && log.tag != type) return false
    val days = period.days
    if (days != null) return log.timestamp in (now - days * 86_400_000)..now
    if (period != DiagnosticPeriod.CUSTOM) return true
    val from = startDay?.let { LocalDate.ofEpochDay(it).atStartOfDay(zone).toInstant().toEpochMilli() } ?: return false
    val until = endDay?.let { LocalDate.ofEpochDay(it).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() } ?: return false
    return log.timestamp >= from && log.timestamp < until
}

internal fun buildDiagnosticIndex(logs: List<LogEntry>, provider: String?, requestFilter: DiagnosticFilter,
    softwareFilter: DiagnosticFilter, now: Long = System.currentTimeMillis()): DiagnosticIndex {
    val requests = logs.filter { it.section() == LogSection.REQUESTS }.sortedByDescending { it.timestamp }
    val software = logs.filter { it.section() == LogSection.SOFTWARE }.sortedByDescending { it.timestamp }
    val scoped = requests.filter { provider == null || it.providerKey() == provider }
    return DiagnosticIndex(
        providers = requests.groupBy { it.providerKey() }.map { (key, entries) ->
            DiagnosticProvider(key, entries.first().providerLabel(), entries.first().endpoint(), entries)
        }.sortedBy { it.name.lowercase() },
        requests = scoped.filter { requestFilter.matches(it, now) },
        software = software.filter { softwareFilter.matches(it, now) },
        models = scoped.mapNotNull { it.modelId() }.distinct().sorted(),
        types = software.map { it.tag }.distinct().sorted(),
    )
}

private val nanoTimeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSSSSS XXX")
internal fun LogEntry.diagnosticTime(zone: ZoneId = ZoneId.systemDefault()): String {
    val instant = preciseTimestamp?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: Instant.ofEpochMilli(timestamp)
    return nanoTimeFormat.withZone(zone).format(instant)
}
