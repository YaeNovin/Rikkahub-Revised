package me.rerere.rikkahub.ui.pages.log

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.common.android.*

internal fun LogEntry.systemLogTitle(): String = when (this) {
    is LogEntry.ErrorLog -> name
    else -> tag
}

internal fun LogEntry.systemLogSummary(): String = when (this) {
    is LogEntry.ErrorLog -> summary
    is LogEntry.TextLog -> message.lineSequence().firstOrNull().orEmpty().take(512)
    else -> ""
}

internal fun LogEntry.systemLogBody(): String = when (this) {
    is LogEntry.ErrorLog -> details
    is LogEntry.TextLog -> message
    else -> ""
}

internal fun DiagnosticClient?.clientLabel(): String = this?.let { client ->
    listOfNotNull(client.name.takeIf(String::isNotBlank), client.versionName?.takeIf(String::isNotBlank))
        .joinToString(" · ").ifBlank { client.packageName }
} ?: "未记录（旧日志）"

internal fun DiagnosticClient?.clientTypeLabel(): String = when (val type = this?.buildType?.lowercase()) {
    "release" -> "正式版"
    "qa" -> "QA 测试版"
    "debug" -> "Debug 测试版"
    null, "" -> "未记录"
    else -> "自定义版本（$type）"
}

internal fun LogEntry.systemErrorType(): String {
    diagnostic?.errorType?.takeIf(String::isNotBlank)?.let { return it }
    if (diagnostic?.source == DiagnosticSource.PROCESS_EXIT) return when (diagnostic?.attributes?.get("reason_code")) {
        "3" -> "系统内存回收"
        "4" -> "Java 崩溃"
        "5" -> "原生崩溃"
        "6" -> "应用无响应（ANR）"
        "9" -> "系统资源限制"
        else -> "系统进程退出"
    }
    val text = systemLogSummary() + "\n" + systemLogBody()
    EXCEPTION_TYPE.findAll(text).lastOrNull()?.groupValues?.get(1)?.let { return it }
    return when {
        diagnostic?.source == DiagnosticSource.LOG_STORAGE -> "日志采集告警"
        diagnostic?.severity == DiagnosticSeverity.WARNING -> "应用告警"
        !isFailure() -> "信息事件"
        diagnostic?.source == DiagnosticSource.RENDERER -> "渲染错误"
        diagnostic?.source == DiagnosticSource.JAVA_CRASH -> "Java 崩溃"
        else -> "应用错误"
    }
}

private val EXCEPTION_TYPE = Regex("(?m)^(?:Caused by:\\s*)?([\\w.$]*(?:Exception|Error))(?::|\\s|$)")

internal fun LogEntry.systemSourceLabel(): String = when (diagnostic?.source) {
    DiagnosticSource.APPLICATION -> "应用事件"
    DiagnosticSource.JAVA_CRASH -> "Java 崩溃捕获"
    DiagnosticSource.PROCESS_EXIT -> "系统退出信息"
    DiagnosticSource.RENDERER -> "渲染器"
    DiagnosticSource.LOG_STORAGE -> "日志存储"
    DiagnosticSource.HTTP -> "HTTP 请求"
    DiagnosticSource.PROVIDER -> "供应商请求"
    null -> "旧版记录"
}

internal fun LogEntry.systemDataValues(): List<Pair<String, String>> = diagnostic?.attributes.orEmpty().map { (key, value) ->
    when (key) {
        "pss_kb" -> "最后采样 PSS" to "$value KB"
        "rss_kb" -> "最后采样 RSS" to "$value KB"
        "reason_code" -> "系统退出原因码" to value
        "status" -> "系统状态码" to value
        "importance" -> "进程重要级别" to value
        "renderer" -> "渲染器" to value
        "stage" -> "发生阶段" to value
        "dropped" -> "丢弃条目数" to value
        "persistence_failures" -> "持久化失败次数" to value
        "related_event_id" -> "关联事件 ID" to value
        "trace_available" -> "系统追踪附件" to when (value) { "true" -> "可用"; "false" -> "未提供"; else -> value }
        "trace_path" -> "追踪附件路径" to value.ifBlank { "未提供" }
        "trace_format" -> "追踪附件格式" to when (value) { "native_system_trace" -> "原生系统追踪（二进制）"; "text" -> "文本"; else -> value }
        else -> key to value
    }
}

@Composable
internal fun SystemDiagnosticRow(log: LogEntry, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(log.systemLogTitle(), style = MaterialTheme.typography.titleSmall)
        Text(log.diagnosticTime(), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        Text("客户端：${log.diagnostic?.client.clientLabel()}", style = MaterialTheme.typography.bodySmall,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text("${log.diagnostic?.client.clientTypeLabel()} · ${log.systemErrorType()}",
            style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text("${log.statusLabel()} · ${log.systemSourceLabel()}",
            color = if (log.isFailure()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        Text(log.systemLogSummary(), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun SystemLogDetail(log: LogEntry) {
    val values = remember(log) { log.systemDataValues() }
    SelectionContainer {
        LazyColumn(Modifier.fillMaxWidth().testTag("system-log-detail"), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text("软件与系统日志详情", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            item { SystemDetailField("时间", log.diagnosticTime()) }
            item { SystemDetailField("客户端", log.diagnostic?.client.clientLabel()) }
            log.diagnostic?.client?.packageName?.takeIf(String::isNotBlank)?.let { name ->
                item { SystemDetailField("客户端标识", name) }
            }
            item { SystemDetailField("客户端类型", log.diagnostic?.client.clientTypeLabel()) }
            log.diagnostic?.client?.versionCode?.let { code -> item { SystemDetailField("版本号", code.toString()) } }
            item { SystemDetailField("错误类型", log.systemErrorType()) }
            item { SystemDetailField("来源", "${log.systemSourceLabel()} · ${log.tag}") }
            item { SystemDetailField("状态", log.statusLabel()) }
            item { SystemDetailField("级别", when (log.diagnostic?.severity) {
                DiagnosticSeverity.INFO -> "信息"; DiagnosticSeverity.WARNING -> "警告"
                DiagnosticSeverity.ERROR -> "错误"; DiagnosticSeverity.FATAL -> "致命错误"; null -> "未记录"
            }) }
            item { SystemDetailField("摘要", log.systemLogSummary()) }
            (log as? LogEntry.ErrorLog)?.reason?.takeIf(String::isNotBlank)?.let { reason ->
                item { SystemDetailField("错误原因", reason) }
            }
            item { HorizontalDivider(); Text("数据值", Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold) }
            if (values.isEmpty()) item { Text("无附加数据", style = MaterialTheme.typography.bodySmall) }
            items(values) { (label, value) -> SystemDetailField(label, value) }
            item { HorizontalDivider(); SystemDetailField("记录 ID", log.id.toString()) }
            log.diagnostic?.sessionId?.let { id -> item { SystemDetailField("采集会话 ID", id) } }
            log.diagnostic?.requestId?.let { id -> item { SystemDetailField("请求 ID", id) } }
            if (log.diagnostic?.truncated == true) item { Text("详细日志已截断", style = MaterialTheme.typography.bodySmall) }
            item {
                val body = log.systemLogBody()
                if (body.isNotBlank()) Box(Modifier.testTag("system-log-body")) {
                    LogBodyContent("详细日志", body, collapsible = true)
                }
                else Text("详细日志未记录或不可用", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun SystemDetailField(label: String, value: String) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
