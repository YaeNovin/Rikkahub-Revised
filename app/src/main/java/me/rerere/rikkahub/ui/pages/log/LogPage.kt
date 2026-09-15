package me.rerere.rikkahub.ui.pages.log

import android.content.ClipData
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.NavigationBarItem
import me.rerere.hugeicons.stroke.ComputerTerminal01
import me.rerere.hugeicons.stroke.Code
import me.rerere.hugeicons.stroke.AiBrain01
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.stroke.Download01
import me.rerere.hugeicons.stroke.Search01
import me.rerere.rikkahub.ui.components.ui.RikkaConfirmDialog
import me.rerere.rikkahub.ui.components.ui.Tooltip
import org.koin.androidx.compose.koinViewModel
import androidx.compose.ui.platform.ClipEntry
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.Delete01
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import me.rerere.rikkahub.ui.components.ui.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import me.rerere.rikkahub.ui.components.ui.AppearanceModalBottomSheet as ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Switch
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.rerere.ai.provider.ProviderRequestChannel
import me.rerere.ai.provider.ProviderRequestOperation
import me.rerere.common.android.LogEntry
import me.rerere.common.android.Logging
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.JsonTree
import me.rerere.rikkahub.service.SoftwareFailureKind
import me.rerere.rikkahub.service.classifySoftwareFailure
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.ui.theme.JetbrainsMono
import me.rerere.rikkahub.utils.JsonInstantPretty
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LogPage(vm: LogVM = koinViewModel()) {
    val logs by vm.logs.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var requestQuery by rememberSaveable { mutableStateOf("") }
    var softwareQuery by rememberSaveable { mutableStateOf("") }
    var requestFilter by rememberSaveable { mutableStateOf(LogFilter.ALL) }
    var softwareFilter by rememberSaveable { mutableStateOf(LogFilter.ALL) }
    val pager = rememberPagerState { 2 }
    val scope = rememberCoroutineScope()
    val requestLogs = remember(logs, requestQuery, requestFilter) {
        filterLogs(logs.filter { it.section() == LogSection.REQUESTS }, requestFilter, requestQuery)
    }
    val softwareLogs = remember(logs, softwareQuery, softwareFilter) {
        filterLogs(logs.filter { it.section() == LogSection.SOFTWARE }, softwareFilter, softwareQuery)
    }
    var confirmClear by remember { mutableStateOf(false) }
    var exportLogs by remember { mutableStateOf<List<LogEntry>?>(null) }
    var exportMode by remember { mutableStateOf(LogExportMode.REDACTED) }
    val filteredLogs = if (pager.currentPage == 0) requestLogs else softwareLogs
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> vm.export(context.contentResolver, uri) }
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(context.getString(it))
            vm.message.value = null
        }
    }
    var requestLoggingEnabled by remember { mutableStateOf(Logging.isRequestLoggingEnabled()) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.log_page_title)) },
                navigationIcon = { BackButton() },
                actions = {
                    TextButton(onClick = vm::openAnalysisHistory) { Text("分析历史") }
                    Tooltip(tooltip = { Text(stringResource(R.string.log_page_export)) }) {
                        IconButton(
                            onClick = { exportLogs = filteredLogs.toList() },
                            enabled = filteredLogs.isNotEmpty() && !busy,
                        ) {
                            Icon(HugeIcons.Download01, stringResource(R.string.log_page_export))
                        }
                    }
                    IconButton(
                        onClick = { confirmClear = true },
                        enabled = logs.isNotEmpty() && !busy,
                    ) {
                        Icon(
                            imageVector = HugeIcons.Delete01,
                            contentDescription = stringResource(R.string.log_page_clear),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        bottomBar = {
            BottomAppBar(containerColor = CustomColors.cardColorsOnSurfaceContainer.containerColor) {
                LogSection.entries.forEachIndexed { index, section ->
                    NavigationBarItem(
                        selected = pager.currentPage == index,
                        onClick = { scope.launch { pager.animateScrollToPage(index) } },
                        icon = { Icon(if (section == LogSection.REQUESTS) HugeIcons.Code else HugeIcons.ComputerTerminal01, null) },
                        label = { Text(stringResource(
                            if (section == LogSection.REQUESTS) R.string.log_page_requests_tab
                            else R.string.log_page_software_tab
                        )) },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.scaffoldContainerColor,
    ) { contentPadding ->
        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize().padding(contentPadding)) { page ->
            val section = LogSection.entries[page]
            UnifiedLogList(
                logs = if (page == 0) requestLogs else softwareLogs,
                totalCount = logs.count { it.section() == section },
                query = if (page == 0) requestQuery else softwareQuery,
                onQueryChange = { if (page == 0) requestQuery = it else softwareQuery = it },
                filter = if (page == 0) requestFilter else softwareFilter,
                onFilterChange = { if (page == 0) requestFilter = it else softwareFilter = it },
                section = section,
                exporting = busy,
                onExport = { exportLogs = listOf(it) },
                onAnalyze = vm::prepareAnalysis,
                requestLoggingEnabled = requestLoggingEnabled,
                onRequestLoggingChange = {
                    requestLoggingEnabled = it
                    Logging.setRequestLoggingEnabled(it)
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
    LogAnalysisDialog(vm)
    RikkaConfirmDialog(
        show = confirmClear,
        title = stringResource(R.string.log_page_clear),
        text = { Text(stringResource(R.string.log_page_clear_confirmation)) },
        confirmText = stringResource(R.string.confirm),
        dismissText = stringResource(R.string.cancel),
        onDismiss = { confirmClear = false },
        onConfirm = {
            confirmClear = false
            vm.clear()
        },
    )
    RikkaConfirmDialog(
        show = exportLogs != null,
        title = stringResource(R.string.log_page_export),
        text = {
            Column {
                Text(stringResource(R.string.log_page_export_confirmation, exportLogs?.size ?: 0))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.RadioButton(selected = exportMode == LogExportMode.REDACTED, onClick = { exportMode = LogExportMode.REDACTED })
                    Text("脱敏日志")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.RadioButton(selected = exportMode == LogExportMode.FULL, onClick = { exportMode = LogExportMode.FULL })
                    Text("完整日志（仍屏蔽密钥）")
                }
                if (exportMode == LogExportMode.FULL) Text("包含提示词、工具参数及模型回复，请确认分享范围。")
            }
        },
        confirmText = stringResource(R.string.confirm),
        dismissText = stringResource(R.string.cancel),
        onDismiss = { exportLogs = null },
        onConfirm = {
            val snapshot = exportLogs.orEmpty()
            exportLogs = null
            vm.prepareExport(snapshot, exportMode)
            try {
                exportLauncher.launch("rikkahub-logs-${System.currentTimeMillis()}.json")
            } catch (_: Exception) {
                vm.cancelExport()
                vm.message.value = R.string.log_page_export_failed
            }
        },
    )
}

@Composable
private fun UnifiedLogList(
    logs: List<LogEntry>,
    totalCount: Int,
    query: String,
    onQueryChange: (String) -> Unit,
    filter: LogFilter,
    onFilterChange: (LogFilter) -> Unit,
    section: LogSection,
    exporting: Boolean,
    onExport: (LogEntry) -> Unit,
    onAnalyze: (LogEntry) -> Unit,
    requestLoggingEnabled: Boolean,
    onRequestLoggingChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedLog by remember { mutableStateOf<LogEntry?>(null) }
    val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded))
    val scope = rememberCoroutineScope()
    val sortedLogs = remember(logs) { logs.sortedByDescending { it.timestamp } }
    val groups = remember(sortedLogs) { groupRequestLogs(sortedLogs) }
    var expandedGroups by remember { mutableStateOf(emptySet<String>()) }

    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(16.dp)
    ) {
        if (section == LogSection.REQUESTS) {
            item {
                RequestLoggingSwitchCard(
                    enabled = requestLoggingEnabled,
                    onEnabledChange = onRequestLoggingChange
                )
            }
        }
        item(key = "filters") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    placeholder = { Text(stringResource(R.string.log_page_search)) },
                    leadingIcon = { Icon(HugeIcons.Search01, null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    (if (section == LogSection.REQUESTS) listOf(LogFilter.ALL, LogFilter.ERRORS)
                    else listOf(LogFilter.ALL, LogFilter.ERRORS, LogFilter.TEXT)).forEach { option ->
                        FilterChip(
                            selected = filter == option,
                            onClick = { onFilterChange(option) },
                            label = { Text(stringResource(when (option) {
                                LogFilter.ALL -> R.string.log_page_filter_all
                                LogFilter.ERRORS -> R.string.log_page_filter_errors
                                LogFilter.REQUESTS -> R.string.log_page_filter_requests
                                LogFilter.TEXT -> R.string.log_page_filter_text
                            })) },
                        )
                    }
                }
                Text(
                    stringResource(R.string.log_page_count, logs.size, totalCount),
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(
                    stringResource(R.string.log_page_retention),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (exporting) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                if (logs.isEmpty()) Text(stringResource(R.string.log_page_empty))
            }
        }

        groups.forEach { group ->
            val expanded = group.key in expandedGroups
            if (group.entries.size > 1) {
                item(key = group.key, contentType = "request-group") {
                    RequestGroupHeader(group, expanded, onClick = {
                        expandedGroups = if (expanded) expandedGroups - group.key else expandedGroups + group.key
                    })
                }
            }
            if (group.entries.size == 1 || expanded) {
                items(group.entries, key = { it.id }, contentType = { it.javaClass.simpleName }) { log ->
                    val openLog: () -> Unit = {
                        selectedLog = log
                        scope.launch { sheetState.show() }
                    }
                    when (log) {
                        is LogEntry.RequestLog -> RequestLogCard(log, openLog)
                        is LogEntry.ProviderRequestLog -> ProviderRequestLogCard(log, openLog)
                        is LogEntry.ErrorLog -> ErrorLogCard(log, openLog)
                        is LogEntry.TextLog -> TextLogCard(log, openLog)
                    }
                }
            }
        }
    }

    selectedLog?.let { log ->
        ModalBottomSheet(
            onDismissRequest = { selectedLog = null },
            sheetState = sheetState
        ) {
            TextButton(onClick = { onExport(log) }, enabled = !exporting) {
                Icon(HugeIcons.Download01, null)
                Text(stringResource(R.string.log_page_export_single))
            }
            if (log.isFailure()) {
                TextButton(onClick = { onAnalyze(log) }) {
                    Icon(HugeIcons.AiBrain01, null)
                    Text(stringResource(R.string.log_page_ai_analysis))
                }
            }
            when (log) {
                is LogEntry.RequestLog -> RequestLogDetail(log)
                is LogEntry.ProviderRequestLog -> ProviderRequestLogDetail(log)
                is LogEntry.ErrorLog -> ErrorLogDetail(log)
                is LogEntry.TextLog -> SelectionContainer {
                    LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        item { DetailSection(stringResource(R.string.log_page_name), log.tag) }
                        item { DetailSection(stringResource(R.string.log_page_time),
                            SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date(log.timestamp))) }
                        item { DetailSection(stringResource(R.string.log_page_technical_log), log.message) }
                    }
                }
            }
        }
    }
}

@Composable
private fun RequestGroupHeader(group: RequestLogGroup, expanded: Boolean, onClick: () -> Unit) {
    val latest = group.entries.first() as LogEntry.ProviderRequestLog
    val failures = group.entries.count { it.isFailure() }
    val timeFormat = remember { SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()) }
    val methods = group.entries.filterIsInstance<LogEntry.ProviderRequestLog>()
        .groupingBy { it.method.orEmpty().uppercase(Locale.ROOT) }.eachCount()
        .entries.joinToString(" / ") { "${it.key}: ${it.value}" }
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(latest.provider, style = MaterialTheme.typography.titleSmall)
            Text(latest.model, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                stringResource(R.string.log_page_group_count, group.entries.size, failures),
                style = MaterialTheme.typography.labelMedium,
                color = if (failures > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
            Text("$methods · ${timeFormat.format(Date(latest.timestamp))}", style = MaterialTheme.typography.labelSmall)
            group.entries.firstOrNull { it.isFailure() }?.let { failed ->
                val reason = (failed as LogEntry.ProviderRequestLog).error
                    ?: failed.responseCode?.let { "HTTP $it" }.orEmpty()
                Text(reason, style = MaterialTheme.typography.bodySmall, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.error)
            }
            Text(
                stringResource(if (expanded) R.string.log_page_group_collapse else R.string.log_page_group_expand),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun ProviderRequestLogCard(log: LogEntry.ProviderRequestLog, onClick: () -> Unit) {
    val dateFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = localizedProviderOperation(log.operation),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = dateFormat.format(Date(log.timestamp)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = log.model,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = JetbrainsMono,
                maxLines = 2,
            )
            Text(
                text = log.provider,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = localizedProviderChannel(log.channel),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                log.responseCode?.let { code ->
                    Text(
                        text = stringResource(R.string.log_page_status_value, code),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (code in 200..299) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                }
                log.durationMs?.let { duration ->
                    Text(
                        text = stringResource(R.string.log_page_duration_millis, duration),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            log.error?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                )
            }
        }
    }
}

@Composable
private fun ProviderRequestLogDetail(log: LogEntry.ProviderRequestLog) {
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()) }
    SelectionContainer {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    text = stringResource(R.string.log_page_provider_request_details),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            item { DetailSection(stringResource(R.string.log_page_time), dateFormat.format(Date(log.timestamp))) }
            item { DetailSection(stringResource(R.string.log_page_provider), log.provider) }
            item { DetailSection(stringResource(R.string.log_page_model), log.model) }
            item {
                DetailSection(
                    stringResource(R.string.log_page_channel),
                    localizedProviderChannel(log.channel),
                )
            }
            item {
                DetailSection(
                    stringResource(R.string.log_page_operation),
                    localizedProviderOperation(log.operation),
                )
            }
            log.responseCode?.let { code ->
                item { DetailSection(stringResource(R.string.log_page_status_code), code.toString()) }
            }
            log.durationMs?.let { duration ->
                item {
                    DetailSection(
                        stringResource(R.string.log_page_duration),
                        stringResource(R.string.log_page_duration_millis, duration),
                    )
                }
            }
            log.error?.let { error ->
                item {
                    DetailSection(stringResource(R.string.log_page_diagnostic_error_label), error)
                }
            }
            if (log.parameters.isNotEmpty()) {
                item {
                    HorizontalDivider()
                    Text(
                        text = stringResource(R.string.log_page_parameters_sent),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Text(
                        text = stringResource(R.string.log_page_parameters_sent_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                log.parameters.forEach { (key, value) ->
                    item { ProviderParameterItem(key, value) }
                }
            }
            if (log.url != null || log.method != null) {
                item { ProviderHttpRequestDetails(log) }
            }
        }
    }
}

@Composable
private fun ProviderHttpRequestDetails(log: LogEntry.ProviderRequestLog) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val requestBodyLabel = stringResource(R.string.log_page_request_body)

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        HorizontalDivider()
        Text(
            text = stringResource(R.string.log_page_http_details),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 8.dp),
        )
        log.url?.let { DetailSection(stringResource(R.string.log_page_url), it) }
        log.method?.let { DetailSection(stringResource(R.string.log_page_method), it) }
        if (log.requestHeaders.isNotEmpty()) {
            Text(
                text = stringResource(R.string.log_page_request_headers),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            log.requestHeaders.forEach { (key, value) -> HeaderItem(key, value) }
        }
        log.requestBody?.let { body ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = requestBodyLabel,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp),
                )
                IconButton(
                    onClick = {
                        scope.launch {
                            clipboard.setClipEntry(
                                ClipEntry(ClipData.newPlainText(requestBodyLabel, body))
                            )
                        }
                    }
                ) {
                    Icon(
                        imageVector = HugeIcons.Copy01,
                        contentDescription = stringResource(R.string.copy),
                    )
                }
            }
            val jsonElement = remember(body) {
                runCatching { JsonInstantPretty.parseToJsonElement(body) }.getOrNull()
            }
            if (jsonElement != null) {
                JsonTree(
                    json = jsonElement,
                    modifier = Modifier.padding(top = 4.dp),
                    initialExpandLevel = 2,
                )
            } else {
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = JetbrainsMono,
                )
            }
        }
        log.responseBody?.let { body ->
            FormattedResponseBody(body)
        }
        if (log.responseHeaders.isNotEmpty()) {
            Text(
                text = stringResource(R.string.log_page_response_headers),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            log.responseHeaders.forEach { (key, value) -> HeaderItem(key, value) }
        }
    }
}

@Composable
private fun FormattedResponseBody(body: String) {
    LogBodyContent(label = "响应正文", body = body)
}

@Composable
private fun localizedProviderChannel(value: String): String = when (value) {
    ProviderRequestChannel.ANTHROPIC_API.name ->
        stringResource(R.string.log_page_channel_anthropic_api)
    ProviderRequestChannel.OPENAI_API.name ->
        stringResource(R.string.log_page_channel_openai_api)
    ProviderRequestChannel.XAI_API.name ->
        stringResource(R.string.log_page_channel_xai_api)
    ProviderRequestChannel.GOOGLE_AI_STUDIO.name ->
        stringResource(R.string.log_page_channel_google_ai_studio)
    ProviderRequestChannel.VERTEX_AI.name ->
        stringResource(R.string.log_page_channel_vertex_ai)
    ProviderRequestChannel.COMPATIBLE_ENDPOINT.name ->
        stringResource(R.string.log_page_channel_compatible)
    else -> value
}

@Composable
private fun localizedProviderOperation(value: String): String = when (value) {
    ProviderRequestOperation.EMBEDDING.name -> "向量生成"
    ProviderRequestOperation.TEXT_GENERATION.name ->
        stringResource(R.string.log_page_operation_text)
    ProviderRequestOperation.STREAM_TEXT.name ->
        stringResource(R.string.log_page_operation_stream_text)
    ProviderRequestOperation.IMAGE_GENERATION.name ->
        stringResource(R.string.log_page_operation_image)
    ProviderRequestOperation.IMAGE_EDIT.name ->
        stringResource(R.string.log_page_operation_image_edit)
    ProviderRequestOperation.VIDEO_GENERATION_CREATE.name ->
        stringResource(R.string.log_page_operation_video_create)
    ProviderRequestOperation.VIDEO_GENERATION_STATUS.name ->
        stringResource(R.string.log_page_operation_video_status)
    ProviderRequestOperation.VIDEO_GENERATION_CANCEL.name ->
        stringResource(R.string.log_page_operation_video_cancel)
    ProviderRequestOperation.VIDEO_GENERATION_DOWNLOAD.name ->
        stringResource(R.string.log_page_operation_video_download)
    else -> value
}

@Composable
private fun ProviderParameterItem(key: String, value: String) {
    val labelResource = when (key) {
        "api" -> R.string.log_page_parameter_api
        "messages.count" -> R.string.log_page_parameter_messages_count
        "system.blocks" -> R.string.log_page_parameter_system_blocks
        "content.textBlocks" -> R.string.log_page_parameter_text_blocks
        "content.imageBlocks" -> R.string.log_page_parameter_image_blocks
        "responseModalities" -> R.string.log_page_parameter_response_modalities
        "imageConfig.aspectRatio" -> R.string.log_page_parameter_image_aspect_ratio
        "imageConfig.imageSize" -> R.string.log_page_parameter_image_size
        "thinkingConfig.thinkingLevel" -> R.string.log_page_parameter_thinking_level
        "thinkingConfig.thinkingBudget" -> R.string.log_page_parameter_thinking_budget
        "thinkingConfig.includeThoughts" -> R.string.log_page_parameter_include_thoughts
        "tools.googleSearch.webSearch" -> R.string.log_page_parameter_web_search
        "tools.googleSearch.imageSearch" -> R.string.log_page_parameter_image_search
        "prompt.characters" -> R.string.log_page_parameter_prompt_characters
        "referenceImages.encodedBytes" -> R.string.log_page_parameter_reference_image_bytes
        "referenceImages" -> R.string.log_page_parameter_reference_images
        "safety.harassment" -> R.string.log_page_parameter_safety_harassment
        "safety.hate_speech" -> R.string.log_page_parameter_safety_hate_speech
        "safety.sexually_explicit" -> R.string.log_page_parameter_safety_sexual
        "safety.dangerous_content" -> R.string.log_page_parameter_safety_dangerous
        "temperature" -> R.string.log_page_parameter_temperature
        "topP", "top_p" -> R.string.log_page_parameter_top_p
        "top_k" -> R.string.log_page_parameter_top_k
        "maxOutputTokens", "max_tokens" -> R.string.log_page_parameter_max_output_tokens
        "mediaResolution" -> R.string.log_page_parameter_media_resolution
        "seed" -> R.string.log_page_parameter_seed
        "stopSequences", "stop_sequences.count" -> R.string.log_page_parameter_stop_sequences
        "responseMimeType" -> R.string.log_page_parameter_response_mime_type
        "responseJsonSchema" -> R.string.log_page_parameter_response_schema
        "presencePenalty" -> R.string.log_page_parameter_presence_penalty
        "frequencyPenalty" -> R.string.log_page_parameter_frequency_penalty
        "service_tier" -> R.string.log_page_parameter_service_tier
        "inference_geo" -> R.string.log_page_parameter_inference_geo
        "stream" -> R.string.log_page_parameter_stream
        "thinking.type" -> R.string.log_page_parameter_thinking_type
        "thinking.display" -> R.string.log_page_parameter_thinking_display
        "thinking.budget_tokens" -> R.string.log_page_parameter_thinking_budget
        "output_config.effort" -> R.string.log_page_parameter_reasoning_effort
        "output_config.format.type" -> R.string.log_page_parameter_response_format
        "tool_choice.type" -> R.string.log_page_parameter_tool_choice
        "tool_choice.disable_parallel_tool_use" -> R.string.log_page_parameter_disable_parallel_tools
        "metadata.user_id" -> R.string.log_page_parameter_metadata_user
        "tools.count" -> R.string.log_page_parameter_tools_count
        "tools.function.count" -> R.string.log_page_parameter_function_tools_count
        "tools.server.count" -> R.string.log_page_parameter_server_tools_count
        "cache_control.type" -> R.string.log_page_parameter_cache_type
        "cache_control.ttl" -> R.string.log_page_parameter_cache_ttl
        "customBody" -> R.string.log_page_parameter_custom_body
        else -> null
    }
    val label = labelResource?.let {
        stringResource(
            R.string.log_page_parameter_label_with_api_field,
            stringResource(it),
            key,
        )
    } ?: key
    val instruction = key.lowercase().replace("_", "") in setOf("body.systeminstruction", "body.systemlnstruction")
    if (instruction || me.rerere.rikkahub.ui.components.ui.isLongDisplayText(value)) {
        LogBodyContent(label, value, systemInstruction = instruction)
    } else HeaderItem(label, localizedProviderParameterValue(value))
}

@Composable
private fun localizedProviderParameterValue(value: String): String = when {
    value == "true" -> stringResource(R.string.log_page_parameter_value_enabled, value)
    value == "false" -> stringResource(R.string.log_page_parameter_value_disabled, value)
    value == "configured" -> stringResource(R.string.log_page_parameter_value_configured, value)
    value == "none" -> stringResource(R.string.log_page_parameter_value_not_configured, value)
    value == "omitted (API default)" ->
        stringResource(R.string.log_page_parameter_value_api_default)
    value == "messages" -> stringResource(R.string.log_page_parameter_value_anthropic_messages, value)
    value == "TEXT" -> stringResource(R.string.log_page_parameter_value_text, value)
    value == "IMAGE" -> stringResource(R.string.log_page_parameter_value_image, value)
    value == "TEXT, IMAGE" -> stringResource(R.string.log_page_parameter_value_text_image, value)
    value == "BLOCK_NONE" -> stringResource(R.string.log_page_parameter_value_block_none, value)
    value == "BLOCK_ONLY_HIGH" -> stringResource(R.string.log_page_parameter_value_block_high, value)
    value == "BLOCK_MEDIUM_AND_ABOVE" ->
        stringResource(R.string.log_page_parameter_value_block_medium, value)
    value == "BLOCK_LOW_AND_ABOVE" ->
        stringResource(R.string.log_page_parameter_value_block_low, value)
    value == "OFF" -> stringResource(R.string.log_page_parameter_value_off, value)
    value.endsWith(" configured") && value.removeSuffix(" configured").toIntOrNull() != null ->
        stringResource(
            R.string.log_page_parameter_value_count_configured,
            value.removeSuffix(" configured").toInt(),
        )
    value.endsWith(" (per media part)") ->
        stringResource(
            R.string.log_page_parameter_value_per_media_part,
            value.removeSuffix(" (per media part)"),
        )
    else -> value
}

@Composable
private fun ErrorLogCard(log: LogEntry.ErrorLog, onClick: () -> Unit) {
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = log.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = dateFormat.format(Date(log.timestamp)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = log.summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            log.reason?.let { reason ->
                Text(
                    text = stringResource(R.string.log_page_reason_value, reason),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ErrorLogDetail(log: LogEntry.ErrorLog) {
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()) }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    SelectionContainer {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    text = stringResource(R.string.log_page_error_details),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            item { DetailSection(stringResource(R.string.log_page_name), log.name) }
            item {
                DetailSection(
                    stringResource(R.string.log_page_time),
                    dateFormat.format(Date(log.timestamp)),
                )
            }
            item {
                DetailSection(
                    stringResource(R.string.log_page_diagnostic_result),
                    log.summary,
                )
            }
            log.reason?.let { reason ->
                item { DetailSection(stringResource(R.string.log_page_reason), reason) }
            }
            item {
                DetailSection(
                    stringResource(R.string.log_page_troubleshooting),
                    stringResource(log.troubleshootingMessageResource()),
                )
            }
            item {
                HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(R.string.log_page_technical_log),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    IconButton(
                        onClick = {
                            scope.launch {
                                clipboard.setClipEntry(
                                    ClipEntry(
                                        ClipData.newPlainText(log.name, log.details)
                                    )
                                )
                            }
                        }
                    ) {
                        Icon(
                            imageVector = HugeIcons.Copy01,
                            contentDescription = stringResource(R.string.log_page_copy_complete_log),
                        )
                    }
                }
                Text(
                    text = log.details,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = JetbrainsMono,
                )
            }
        }
    }
}

private fun LogEntry.ErrorLog.troubleshootingMessageResource(): Int {
    val diagnosticText = listOfNotNull(reason, summary)
        .joinToString(separator = "\n")
        .lowercase()
    if (REQUEST_ERROR_STATUS_PATTERN.containsMatchIn(diagnosticText)) {
        return R.string.log_page_troubleshooting_request
    }
    return when (classifySoftwareFailure(diagnosticText)) {
        SoftwareFailureKind.EMPTY_RESPONSE -> R.string.log_page_troubleshooting_empty_response
        SoftwareFailureKind.CONTEXT_LIMIT -> R.string.log_page_troubleshooting_context
        SoftwareFailureKind.RATE_LIMIT -> R.string.log_page_troubleshooting_rate_limit
        SoftwareFailureKind.SERVER_TEMPORARY -> R.string.log_page_troubleshooting_server
        SoftwareFailureKind.NETWORK -> R.string.log_page_troubleshooting_network
        SoftwareFailureKind.TIMEOUT -> R.string.log_page_troubleshooting_timeout
        SoftwareFailureKind.AUTHENTICATION -> R.string.log_page_troubleshooting_authentication
        SoftwareFailureKind.CONFIGURATION -> R.string.log_page_troubleshooting_configuration
        SoftwareFailureKind.IMAGE -> R.string.log_page_troubleshooting_image
        SoftwareFailureKind.PERMISSION -> R.string.log_page_troubleshooting_permission
        SoftwareFailureKind.INVALID_DATA,
        SoftwareFailureKind.UNSUPPORTED -> R.string.log_page_troubleshooting_request

        SoftwareFailureKind.MIDI,
        SoftwareFailureKind.DOCUMENT,
        SoftwareFailureKind.FILE_NOT_FOUND,
        SoftwareFailureKind.FILE_READ,
        SoftwareFailureKind.FILE_WRITE,
        SoftwareFailureKind.IMPORT,
        SoftwareFailureKind.EXPORT,
        SoftwareFailureKind.RESTORE,
        SoftwareFailureKind.DELETE -> R.string.log_page_troubleshooting_local_data

        SoftwareFailureKind.TOOL,
        SoftwareFailureKind.GENERIC -> R.string.log_page_troubleshooting_generic
    }
}

private val REQUEST_ERROR_STATUS_PATTERN = Regex(
    """(?:http|status(?:\s+code)?|状态码)\s*[:：]?\s*(?:400|404|405|409|410|413|415|422)\b"""
)

@Composable
private fun RequestLoggingSwitchCard(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.log_page_record_requests),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = stringResource(R.string.log_page_record_requests_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = onEnabledChange
            )
        }
    }
}

@Composable
private fun RequestLogCard(log: LogEntry.RequestLog, onClick: () -> Unit) {
    val dateFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = log.method,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = dateFormat.format(Date(log.timestamp)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text(
                text = log.url,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = JetbrainsMono,
                maxLines = 2
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                log.responseCode?.let { code ->
                    Text(
                        text = stringResource(R.string.log_page_status_value, code),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (code in 200..299) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        }
                    )
                }
                log.durationMs?.let { duration ->
                    Text(
                        text = stringResource(R.string.log_page_duration_millis, duration),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            log.error?.let { error ->
                Text(
                    text = stringResource(R.string.log_page_diagnostic_error, error),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun RequestLogDetail(log: LogEntry.RequestLog) {
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()) }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val requestBodyLabel = stringResource(R.string.log_page_request_body)

    SelectionContainer {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    text = stringResource(R.string.log_page_request_details),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            item {
                DetailSection(
                    stringResource(R.string.log_page_time),
                    dateFormat.format(Date(log.timestamp)),
                )
            }

            item {
                DetailSection(stringResource(R.string.log_page_url), log.url)
            }

            item {
                DetailSection(stringResource(R.string.log_page_method), log.method)
            }

            log.responseCode?.let { code ->
                item {
                    DetailSection(stringResource(R.string.log_page_status_code), code.toString())
                }
            }

            log.durationMs?.let { duration ->
                item {
                    DetailSection(
                        stringResource(R.string.log_page_duration),
                        stringResource(R.string.log_page_duration_millis, duration),
                    )
                }
            }

            log.error?.let { error ->
                item {
                    DetailSection(
                        stringResource(R.string.log_page_diagnostic_error_label),
                        error,
                    )
                }
            }

            if (log.requestHeaders.isNotEmpty()) {
                item {
                    HorizontalDivider()
                    Text(
                        text = stringResource(R.string.log_page_request_headers),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                log.requestHeaders.forEach { (key, value) ->
                    item {
                        HeaderItem(key, value)
                    }
                }
            }

            log.requestBody?.let { body ->
                item {
                    HorizontalDivider()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = requestBodyLabel,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                        IconButton(
                            onClick = {
                                scope.launch {
                                    clipboard.setClipEntry(
                                        ClipEntry(ClipData.newPlainText(requestBodyLabel, body))
                                    )
                                }
                            }
                        ) {
                            Icon(
                                imageVector = HugeIcons.Copy01,
                                contentDescription = stringResource(R.string.copy)
                            )
                        }
                    }
                    val jsonElement = remember(body) {
                        runCatching { JsonInstantPretty.parseToJsonElement(body) }.getOrNull()
                    }
                    if (jsonElement != null) {
                        JsonTree(
                            json = jsonElement,
                            modifier = Modifier.padding(top = 4.dp),
                            initialExpandLevel = 2
                        )
                    } else {
                        Text(
                            text = body,
                            fontFamily = JetbrainsMono,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }

            log.responseBody?.let { body ->
                item {
                    FormattedResponseBody(body)
                }
            }
            if (log.responseHeaders.isNotEmpty()) {
                item {
                    HorizontalDivider()
                    Text(
                        text = stringResource(R.string.log_page_response_headers),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                log.responseHeaders.forEach { (key, value) ->
                    item {
                        HeaderItem(key, value)
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailSection(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = JetbrainsMono
        )
    }
}

@Composable
private fun HeaderItem(key: String, value: String) {
    Column(modifier = Modifier.padding(vertical = 2.dp)) {
        Text(
            text = key,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = JetbrainsMono
        )
    }
}

@Composable
private fun TextLogCard(log: LogEntry.TextLog, onClick: () -> Unit) {
    val dateFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        SelectionContainer {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = log.tag,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = dateFormat.format(Date(log.timestamp)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = log.message,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = JetbrainsMono,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
