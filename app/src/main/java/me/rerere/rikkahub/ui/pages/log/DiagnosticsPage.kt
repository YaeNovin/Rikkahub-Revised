package me.rerere.rikkahub.ui.pages.log

import android.app.DatePickerDialog
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.common.android.LogEntry
import me.rerere.common.android.Logging
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.LargeFlexibleTopAppBar
import me.rerere.rikkahub.ui.components.ui.RikkaConfirmDialog
import me.rerere.rikkahub.ui.components.ui.AppearanceModalBottomSheet
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel
import java.time.LocalDate
import java.time.ZoneId

private val filterSaver = listSaver<DiagnosticFilter, String>(
    save = { listOf(it.period.name, it.startDay?.toString().orEmpty(), it.endDay?.toString().orEmpty(),
        it.model.orEmpty(), it.status.name, it.type.orEmpty()) },
    restore = { DiagnosticFilter(DiagnosticPeriod.valueOf(it[0]), it[1].toLongOrNull(), it[2].toLongOrNull(),
        it[3].ifEmpty { null }, DiagnosticStatus.valueOf(it[4]), it[5].ifEmpty { null }) },
)

@Composable
fun LogPage(vm: LogVM = koinViewModel()) {
    val logs by vm.logs.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var section by rememberSaveable { mutableStateOf(LogSection.REQUESTS) }
    var provider by rememberSaveable { mutableStateOf<String?>(null) }
    var requestFilter by rememberSaveable(stateSaver = filterSaver) { mutableStateOf(DiagnosticFilter()) }
    var softwareFilter by rememberSaveable(stateSaver = filterSaver) { mutableStateOf(DiagnosticFilter()) }
    val index by produceState(DiagnosticIndex(), logs, provider, requestFilter, softwareFilter) {
        value = withContext(Dispatchers.Default) { buildDiagnosticIndex(logs, provider, requestFilter, softwareFilter) }
    }
    var selectedId by remember { mutableStateOf<kotlin.uuid.Uuid?>(null) }
    var requestLogging by remember { mutableStateOf(Logging.isRequestLoggingEnabled()) }
    var confirmClear by remember { mutableStateOf(false) }
    var exportSnapshot by remember { mutableStateOf<List<LogEntry>?>(null) }
    var exportKind by remember { mutableStateOf(DiagnosticExportKind.CSV) }
    var exportMode by remember { mutableStateOf(LogExportMode.REDACTED) }
    var exportMenu by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val csvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) {
        vm.export(context.contentResolver, it)
    }
    val logLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) {
        vm.export(context.contentResolver, it)
    }
    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(context.getString(it)); vm.message.value = null }
    }
    BackHandler(section == LogSection.REQUESTS && provider != null && selectedId == null) { provider = null }
    val providerTitle = index.providers.firstOrNull { it.key == provider }?.name ?: "供应商请求"
    fun singleExport(log: LogEntry) { exportKind = DiagnosticExportKind.DETAIL; exportSnapshot = listOf(log) }
    Scaffold(
        containerColor = CustomColors.scaffoldContainerColor,
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(if (section == LogSection.REQUESTS && provider != null) providerTitle else "错误与诊断日志") },
                navigationIcon = {
                    if (section == LogSection.REQUESTS && provider != null) TextButton(onClick = { provider = null }) { Text("返回") }
                    else BackButton()
                },
                actions = {
                    Box {
                        TextButton(onClick = { exportMenu = true }, enabled = logs.isNotEmpty() && !busy) { Text("导出") }
                        DropdownMenu(expanded = exportMenu, onDismissRequest = { exportMenu = false }) {
                            DropdownMenuItem(text = { Text("导出当前筛选 · CSV") }, onClick = {
                                exportMenu = false; exportKind = DiagnosticExportKind.CSV
                                exportSnapshot = if (section == LogSection.REQUESTS) index.requests else index.software
                            })
                            DropdownMenuItem(text = { Text("导出所有日志 · CSV") }, onClick = {
                                exportMenu = false; exportKind = DiagnosticExportKind.CSV; exportSnapshot = logs.toList()
                            })
                        }
                    }
                    TextButton(onClick = { confirmClear = true }, enabled = !busy && logs.isNotEmpty()) { Text("清空") }
                }, colors = CustomColors.topBarColors,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar(containerColor = CustomColors.cardColorsOnSurfaceContainer.containerColor) {
                LogSection.entries.forEach { option ->
                    NavigationBarItem(selected = section == option, onClick = { section = option },
                        icon = { Text(if (option == LogSection.REQUESTS) "API" else "SYS") },
                        label = { Text(if (option == LogSection.REQUESTS) "请求日志" else "软件与系统") })
                }
            }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item("tools") {
                TextButton(onClick = vm::openAnalysisHistory) { Text("诊断分析历史") }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            if (section == LogSection.REQUESTS && provider == null) {
                item("capture") { RequestLoggingSwitchCard(requestLogging) { requestLogging = it; Logging.setRequestLoggingEnabled(it) } }
                item("help") {
                    Text("按供应商查看已调用模型。正文记录可关闭；基础请求信息仍保留。", style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.log_page_retention), style = MaterialTheme.typography.bodySmall)
                }
                if (index.providers.isEmpty()) item { Text("暂无请求日志") }
                items(index.providers, key = { it.key }) { group ->
                    Column(Modifier.fillMaxWidth().clickable {
                        provider = group.key; requestFilter = DiagnosticFilter()
                    }.padding(vertical = 12.dp)) {
                        Text(group.name, style = MaterialTheme.typography.titleMedium)
                        if (group.endpoint.isNotBlank()) Text(group.endpoint, style = MaterialTheme.typography.bodySmall)
                        Text("${group.logs.size} 次请求 · ${group.logs.mapNotNull { it.modelId() }.distinct().size} 个模型 · ${group.logs.count { it.isFailure() }} 个错误")
                    }
                    HorizontalDivider()
                }
            } else {
                val requestSection = section == LogSection.REQUESTS
                val shown = if (requestSection) index.requests else index.software
                item("filters") {
                    DiagnosticFilters(
                        filter = if (requestSection) requestFilter else softwareFilter,
                        onChange = { if (requestSection) requestFilter = it else softwareFilter = it },
                        models = if (requestSection) index.models else null, types = index.types,
                        modelLabel = { id ->
                            val name = settings.providers.asSequence().flatMap { it.models.asSequence() }
                                .firstOrNull { it.modelId == id }?.displayName.orEmpty()
                            if (name.isBlank() || name == id) id else "$name · $id"
                        },
                    )
                    Text("${shown.size} 条日志", style = MaterialTheme.typography.labelMedium)
                    if(!requestSection) Text("仅包含本应用事件与系统提供的本应用退出信息，不含完整设备 logcat。", style = MaterialTheme.typography.bodySmall)
                    Text(if (requestSection) "时间以 9 位小数展示；实际精度取决于设备，旧记录仅毫秒精度。首字未知时为 —；耗时标注测量阶段。"
                        else "点击条目查看分项详情；详细日志可展开。时间精度取决于设备，旧记录仅毫秒精度；未采集的客户端信息显示为未记录。",
                        style = MaterialTheme.typography.bodySmall)
                    if (shown.isEmpty()) Text("没有符合筛选条件的日志")
                }
                items(shown, key = { it.id }) { log ->
                    if (requestSection) RequestDiagnosticRow(log) { selectedId = log.id }
                    else SystemDiagnosticRow(log) { selectedId = log.id }
                    HorizontalDivider()
                }
            }
        }
    }
    logs.firstOrNull { it.id == selectedId }?.let { summary ->
        val log by produceState(summary, summary) {
            value = withContext(Dispatchers.IO) { Logging.getLog(summary.id) ?: summary }
        }
        val sheet = rememberBottomSheetState(initialValue = SheetValue.Expanded, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded))
        AppearanceModalBottomSheet(onDismissRequest = { selectedId = null }, sheetState = sheet) {
            Row {
                TextButton(onClick = { singleExport(log) }, enabled = !busy) { Text("导出详情 · .log") }
                if (log.isFailure()) TextButton(onClick = { vm.prepareAnalysis(log) }) { Text("分析错误") }
            }
            when (val full = log) {
                is LogEntry.RequestLog -> RequestLogDetail(full)
                is LogEntry.ProviderRequestLog -> ProviderRequestLogDetail(full)
                else -> key(log.id) { SystemLogDetail(full) }
            }
        }
    }
    LogAnalysisDialog(vm)
    RikkaConfirmDialog(show = confirmClear, title = "清空日志", text = { Text("清空本机已记录的请求、软件和系统日志？此操作不可撤销。") },
        confirmText = "清空", dismissText = "取消", onDismiss = { confirmClear = false }, onConfirm = { confirmClear = false; vm.clear() })
    RikkaConfirmDialog(show = exportSnapshot != null, title = "导出日志", text = {
        Column {
            Text("导出 ${exportSnapshot?.size ?: 0} 条；${if (exportKind == DiagnosticExportKind.CSV) "CSV 表格" else "详细技术日志 .log"}")
            LogDropdown("内容", if (exportMode == LogExportMode.REDACTED) "脱敏" else "完整（仍屏蔽密钥）",
                listOf(LogExportMode.REDACTED, LogExportMode.FULL), { if (it == LogExportMode.REDACTED) "脱敏" else "完整（仍屏蔽密钥）" }) { exportMode = it }
            Text("日志可能含提示词、回复及技术错误中的内容。分享前请检查；脱敏不保证移除所有个人信息。")
        }
    }, confirmText = "导出", dismissText = "取消", onDismiss = { exportSnapshot = null }, onConfirm = {
        val snapshot = exportSnapshot.orEmpty(); exportSnapshot = null
        if (snapshot.isNotEmpty()) {
            vm.prepareExport(snapshot, exportMode, exportKind)
            try {
                val filename = "rikkahub-${System.currentTimeMillis()}"
                if (exportKind == DiagnosticExportKind.CSV) csvLauncher.launch("$filename.csv") else logLauncher.launch("$filename.log")
            } catch (_: Exception) { vm.cancelExport(); vm.message.value = R.string.log_page_export_failed }
        }
    })
}

@Composable
private fun RequestDiagnosticRow(log: LogEntry, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(log.modelId() ?: "—", style = MaterialTheme.typography.titleSmall)
        Text(log.diagnosticTime(), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        Text(log.diagnosticType(), style = MaterialTheme.typography.bodySmall)
        Text("${log.statusLabel()} · HTTP ${log.requestCode() ?: "—"}", color = if (log.isFailure()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        val first = log.firstTokenMillis()?.let { String.format(java.util.Locale.ROOT, "%.3f", it) } ?: "—"
        val phase = when (log.timingPhase()) {
            "headers" -> "响应头"; "closed" -> "关闭时"; "body_complete" -> "响应体"; "model_complete" -> "完整生成"
            "failed" -> "失败时"; else -> "旧记录"
        }
        Text("首字 $first ms · 耗时 ${log.elapsedMs() ?: "—"} ms（$phase）", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun DiagnosticFilters(filter: DiagnosticFilter, onChange: (DiagnosticFilter) -> Unit,
    models: List<String>?, types: List<String>, modelLabel: (String) -> String) {
    val context = LocalContext.current
    var startPicker by remember { mutableStateOf<DatePickerDialog?>(null) }
    var endPicker by remember { mutableStateOf<DatePickerDialog?>(null) }
    DisposableEffect(Unit) { onDispose { startPicker?.dismiss(); endPicker?.dismiss() } }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        LogDropdown("日期", if (filter.period == DiagnosticPeriod.CUSTOM) "${filter.startDay?.let(LocalDate::ofEpochDay)} — ${filter.endDay?.let(LocalDate::ofEpochDay)}" else filter.period.label,
            DiagnosticPeriod.entries, { it.label }) { period ->
            if (period != DiagnosticPeriod.CUSTOM) onChange(filter.copy(period = period))
            else {
                val day = filter.startDay?.let(LocalDate::ofEpochDay) ?: LocalDate.now()
                startPicker = DatePickerDialog(context, { _, year, month, date ->
                    val from = LocalDate.of(year, month + 1, date)
                    val end = filter.endDay?.let(LocalDate::ofEpochDay)?.takeIf { !it.isBefore(from) } ?: from
                    endPicker = DatePickerDialog(context, { _, y, m, d ->
                        val to = LocalDate.of(y, m + 1, d)
                        if (!to.isBefore(from)) onChange(filter.copy(period = period, startDay = from.toEpochDay(), endDay = to.toEpochDay()))
                    }, end.year, end.monthValue - 1, end.dayOfMonth).apply {
                        setTitle("结束日期（包含当天）")
                        datePicker.minDate = from.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                        show()
                    }
                }, day.year, day.monthValue - 1, day.dayOfMonth).apply { setTitle("开始日期"); show() }
            }
        }
        if (models != null) LogDropdown("模型", filter.model?.let(modelLabel) ?: "全部模型", listOf<String?>(null) + models,
            { it?.let(modelLabel) ?: "全部模型" }) { onChange(filter.copy(model = it)) }
        else LogDropdown("类型", filter.type ?: "全部类型", listOf<String?>(null) + types,
            { it ?: "全部类型" }) { onChange(filter.copy(type = it)) }
        LogDropdown("状态", filter.status.label, DiagnosticStatus.entries, { it.label }) { onChange(filter.copy(status = it)) }
    }
}

@Composable
private fun <T> LogDropdown(label: String, selected: String, options: List<T>, text: (T) -> String, onSelect: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text("$label：$selected ▾") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.heightIn(max = 360.dp)) {
            options.forEach { option -> DropdownMenuItem(text = { Text(text(option)) }, onClick = { expanded = false; onSelect(option) }) }
        }
    }
}
