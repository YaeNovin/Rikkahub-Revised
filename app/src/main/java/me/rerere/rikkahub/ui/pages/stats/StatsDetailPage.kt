package me.rerere.rikkahub.ui.pages.stats

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AlertCircle
import me.rerere.hugeicons.stroke.Calendar03
import me.rerere.hugeicons.stroke.Cpu
import me.rerere.hugeicons.stroke.Download01
import me.rerere.hugeicons.stroke.Message01
import me.rerere.hugeicons.stroke.Rocket01
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.ServerStack01
import me.rerere.hugeicons.stroke.Time02
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.db.entity.RequestStatEntity
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

private val REQUEST_TIME_FORMATTER = DateTimeFormatter.ofPattern("MM-dd\nHH:mm", Locale.getDefault())
private val COMPLETION_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd\nHH:mm:ss", Locale.getDefault())
private const val TABLE_ROW_COUNT = 7
private val TABLE_HEADER_HEIGHT = 56.dp
private val TABLE_ROW_HEIGHT = 48.dp

@Composable
internal fun StatsDetailPage(
    vm: StatsVM,
    contentPadding: PaddingValues,
) {
    val state by vm.detailState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    var pendingCsv by remember { mutableStateOf("") }

    val csvLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        output.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
                        output.write(pendingCsv.toByteArray(Charsets.UTF_8))
                    } ?: error("Unable to open export destination")
                }
            }.onSuccess {
                toaster.show(
                    context.getString(R.string.stats_detail_export_success),
                    type = ToastType.Success,
                )
            }.onFailure {
                toaster.show(
                    context.getString(R.string.stats_detail_export_failed),
                    type = ToastType.Error,
                )
            }
        }
    }

    val csvHeaders = StatsCsvHeaders(
        timestamp = stringResource(R.string.stats_detail_csv_timestamp),
        provider = stringResource(R.string.stats_detail_provider),
        model = stringResource(R.string.stats_detail_model),
        reasoningDepth = stringResource(R.string.stats_detail_reasoning_depth),
        promptTokens = stringResource(R.string.stats_detail_input_tokens),
        completionTokens = stringResource(R.string.stats_detail_output_tokens),
        cachedTokens = stringResource(R.string.stats_detail_cached_tokens),
        totalTokens = stringResource(R.string.stats_detail_total_tokens),
        firstTokenMs = stringResource(R.string.stats_detail_first_token_time),
        totalDurationMs = stringResource(R.string.stats_detail_total_time),
        statusCode = stringResource(R.string.stats_detail_status),
    )

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding + PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            StatsFilterControls(
                filter = state.summaryFilter,
                providerOptions = state.providerOptions,
                onTimeRangeChange = vm::setSummaryTimeRange,
                onProvidersChange = vm::setSummaryProviders,
            )
        }
        item {
            SummaryCards(
                summary = state.summary,
                loading = state.isInitializing || state.isSummaryLoading,
            )
        }
        state.summaryError?.let {
            item { LoadErrorText() }
        }
        item {
            HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
        }
        item {
            Text(
                text = stringResource(R.string.stats_detail_requests_title),
                style = MaterialTheme.typography.titleMedium,
            )
        }
        item {
            StatsFilterControls(
                filter = state.tableFilter,
                providerOptions = state.providerOptions,
                onTimeRangeChange = vm::setTableTimeRange,
                onProvidersChange = vm::setTableProviders,
            )
        }
        item {
            FilledTonalButton(
                onClick = {
                    pendingCsv = buildRequestStatsCsv(state.requests, csvHeaders)
                    val timestamp = java.time.LocalDateTime.now()
                        .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                    csvLauncher.launch("rikkahub-request-statistics-$timestamp.csv")
                },
                enabled = state.requests.isNotEmpty() && !state.isTableLoading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(HugeIcons.Download01, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(
                    text = stringResource(R.string.stats_detail_export_csv),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
        item {
            when {
                state.isInitializing || state.isTableLoading -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }

                state.tableError != null -> LoadErrorText()
                state.requests.isEmpty() -> Text(
                    text = stringResource(R.string.stats_detail_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 40.dp),
                    textAlign = TextAlign.Center,
                )

                else -> RequestStatsTable(state.requests)
            }
        }
    }
}

@Composable
private fun SummaryCards(
    summary: RequestStatsSummary,
    loading: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DetailMetricCard(
                modifier = Modifier.weight(1f),
                icon = HugeIcons.Cpu,
                label = stringResource(R.string.stats_detail_used_tokens),
                value = formatTokens(summary.totalTokens),
                loading = loading,
            )
            DetailMetricCard(
                modifier = Modifier.weight(1f),
                icon = HugeIcons.Rocket01,
                label = stringResource(R.string.stats_page_request_count),
                value = formatCount(summary.requestCount.toLong()),
                loading = loading,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DetailMetricCard(
                modifier = Modifier.weight(1f),
                icon = HugeIcons.Message01,
                label = stringResource(R.string.stats_detail_message_count),
                value = formatCount(summary.messageCount.toLong()),
                loading = loading,
            )
            DetailMetricCard(
                modifier = Modifier.weight(1f),
                icon = HugeIcons.Time02,
                label = stringResource(R.string.stats_detail_average_response),
                value = summary.averageResponseNanos.formatNanosDuration(),
                loading = loading,
            )
        }
    }
}

@Composable
private fun DetailMetricCard(
    modifier: Modifier,
    icon: ImageVector,
    label: String,
    value: String,
    loading: Boolean,
) {
    Card(
        modifier = modifier.heightIn(min = 104.dp),
        shape = RoundedCornerShape(8.dp),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(19.dp),
                )
                if (loading) CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
            }
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun StatsFilterControls(
    filter: StatsFilter,
    providerOptions: List<String>,
    onTimeRangeChange: (StatsTimeRange) -> Unit,
    onProvidersChange: (Set<String>) -> Unit,
) {
    var showTimeMenu by remember { mutableStateOf(false) }
    var showCustomRange by remember { mutableStateOf(false) }
    var showProviderDialog by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(modifier = Modifier.weight(1f)) {
            OutlinedButton(
                onClick = { showTimeMenu = true },
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 10.dp),
            ) {
                Icon(HugeIcons.Calendar03, contentDescription = null, modifier = Modifier.size(16.dp))
                Text(
                    text = filter.timeRange.label(),
                    modifier = Modifier.padding(start = 6.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            DropdownMenu(
                expanded = showTimeMenu,
                onDismissRequest = { showTimeMenu = false },
            ) {
                TimeRangeMenuItem(StatsRangePreset.DEFAULT_SEVEN_DAYS) {
                    showTimeMenu = false
                    onTimeRangeChange(StatsTimeRange())
                }
                listOf(
                    StatsRangePreset.LAST_24_HOURS,
                    StatsRangePreset.LAST_3_DAYS,
                    StatsRangePreset.LAST_14_DAYS,
                    StatsRangePreset.LAST_30_DAYS,
                ).forEach { preset ->
                    TimeRangeMenuItem(preset) {
                        showTimeMenu = false
                        onTimeRangeChange(StatsTimeRange(preset))
                    }
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.stats_detail_custom_range)) },
                    onClick = {
                        showTimeMenu = false
                        showCustomRange = true
                    },
                )
            }
        }
        OutlinedButton(
            onClick = { showProviderDialog = true },
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 10.dp),
        ) {
            Icon(HugeIcons.ServerStack01, contentDescription = null, modifier = Modifier.size(16.dp))
            Text(
                text = filter.providers.providerLabel(),
                modifier = Modifier.padding(start = 6.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }

    if (showCustomRange) {
        CustomDateRangeDialog(
            initialRange = filter.timeRange,
            onDismiss = { showCustomRange = false },
            onConfirm = {
                showCustomRange = false
                onTimeRangeChange(it)
            },
        )
    }
    if (showProviderDialog) {
        ProviderFilterDialog(
            options = providerOptions,
            selected = filter.providers,
            onDismiss = { showProviderDialog = false },
            onConfirm = {
                showProviderDialog = false
                onProvidersChange(it)
            },
        )
    }
}

@Composable
private fun TimeRangeMenuItem(
    preset: StatsRangePreset,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(StatsTimeRange(preset).label()) },
        onClick = onClick,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomDateRangeDialog(
    initialRange: StatsTimeRange,
    onDismiss: () -> Unit,
    onConfirm: (StatsTimeRange) -> Unit,
) {
    val state = rememberDateRangePickerState(
        initialSelectedStartDateMillis = initialRange.customStart?.toUtcMillis(),
        initialSelectedEndDateMillis = initialRange.customEnd?.toUtcMillis(),
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val start = state.selectedStartDateMillis?.toUtcDate() ?: return@TextButton
                    val end = state.selectedEndDateMillis?.toUtcDate() ?: start
                    onConfirm(
                        StatsTimeRange(
                            preset = StatsRangePreset.CUSTOM,
                            customStart = start,
                            customEnd = end,
                        )
                    )
                },
                enabled = state.selectedStartDateMillis != null,
            ) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    ) {
        DateRangePicker(
            state = state,
            modifier = Modifier.heightIn(max = 520.dp),
            showModeToggle = false,
        )
    }
}

@Composable
private fun ProviderFilterDialog(
    options: List<String>,
    selected: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var draft by remember(selected) { mutableStateOf(selected) }
    val visibleOptions = remember(options, query) {
        options.filter { it.contains(query.trim(), ignoreCase = true) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.stats_detail_provider_filter)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.stats_detail_search_provider)) },
                    leadingIcon = { Icon(HugeIcons.Search01, contentDescription = null) },
                )
                LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                    item {
                        ProviderOptionRow(
                            label = stringResource(R.string.stats_detail_all_providers),
                            checked = draft.isEmpty(),
                            onClick = { draft = emptySet() },
                        )
                    }
                    items(visibleOptions, key = { it }) { provider ->
                        ProviderOptionRow(
                            label = provider,
                            checked = provider in draft,
                            onClick = {
                                draft = if (provider in draft) draft - provider else draft + provider
                            },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(draft) }) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}

@Composable
private fun ProviderOptionRow(
    label: String,
    checked: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = { onClick() })
        Text(
            text = label,
            modifier = Modifier.padding(start = 6.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun RequestStatsTable(requests: List<RequestStatEntity>) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(TABLE_HEADER_HEIGHT + TABLE_ROW_HEIGHT * (TABLE_ROW_COUNT - 1)),
    ) {
        Column(modifier = Modifier.width(94.dp)) {
            TableCell(
                text = stringResource(R.string.stats_detail_request_column),
                width = 94.dp,
                height = TABLE_HEADER_HEIGHT,
                header = true,
            )
            listOf(
                R.string.stats_detail_provider,
                R.string.stats_detail_model,
                R.string.stats_detail_reasoning_depth,
                R.string.stats_detail_total_tokens,
                R.string.stats_detail_status,
                R.string.stats_detail_time,
            ).forEach { label ->
                TableCell(
                    text = stringResource(label),
                    width = 94.dp,
                    height = TABLE_ROW_HEIGHT,
                    header = true,
                )
            }
        }
        LazyRow(modifier = Modifier.weight(1f)) {
            itemsIndexed(requests, key = { _, request -> request.id }) { index, request ->
                RequestTableColumn(
                    index = index,
                    request = request,
                )
            }
        }
    }
}

@Composable
private fun RequestTableColumn(
    index: Int,
    request: RequestStatEntity,
) {
    val dateTime = remember(request.timestamp) {
        Instant.ofEpochMilli(request.timestamp)
            .atZone(ZoneId.systemDefault())
            .format(REQUEST_TIME_FORMATTER)
    }
    val completedTime = remember(request.completedAt, request.timestamp) {
        Instant.ofEpochMilli(request.completedAt ?: request.timestamp)
            .atZone(ZoneId.systemDefault())
            .format(COMPLETION_TIME_FORMATTER)
    }
    Column(modifier = Modifier.width(132.dp)) {
        TableCell(
            text = "#${index + 1}\n$dateTime",
            width = 132.dp,
            height = TABLE_HEADER_HEIGHT,
            header = true,
        )
        TableCell(request.provider, 132.dp, TABLE_ROW_HEIGHT)
        TableCell(request.model, 132.dp, TABLE_ROW_HEIGHT)
        TableCell(request.reasoningDepth.ifBlank { "—" }, 132.dp, TABLE_ROW_HEIGHT)
        TokenTableCell(request)
        TableCell(request.statusCode?.toString() ?: "—", 132.dp, TABLE_ROW_HEIGHT)
        TableCell(completedTime, 132.dp, TABLE_ROW_HEIGHT)
    }
}

@Composable
private fun TableCell(
    text: String,
    width: Dp,
    height: Dp,
    header: Boolean = false,
) {
    Box(
        modifier = Modifier
            .width(width)
            .height(height)
            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal,
            color = if (header) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun TokenTableCell(request: RequestStatEntity) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .width(132.dp)
            .height(TABLE_ROW_HEIGHT)
            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
            .padding(start = 8.dp, end = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text = formatTokens(request.totalTokens),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Box {
            IconButton(
                onClick = { expanded = true },
                modifier = Modifier.size(24.dp),
            ) {
                Icon(
                    imageVector = HugeIcons.AlertCircle,
                    contentDescription = stringResource(R.string.stats_detail_token_breakdown),
                    modifier = Modifier
                        .size(12.dp)
                        .border(0.5.dp, MaterialTheme.colorScheme.outline, CircleShape),
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                TokenBreakdownLine(
                    R.string.stats_detail_input_tokens,
                    formatTokens(request.promptTokens),
                )
                TokenBreakdownLine(
                    R.string.stats_detail_output_tokens,
                    formatTokens(request.completionTokens),
                )
                TokenBreakdownLine(
                    R.string.stats_detail_cached_tokens,
                    formatTokens(request.cachedTokens),
                )
                TokenBreakdownLine(
                    R.string.stats_detail_first_token_time,
                    request.firstTokenNanos?.formatNanosDuration()
                        ?: stringResource(R.string.stats_detail_not_applicable),
                )
                TokenBreakdownLine(
                    R.string.stats_detail_total_time,
                    request.effectiveTotalDurationNanos.formatNanosDuration(),
                )
            }
        }
    }
}

@Composable
private fun TokenBreakdownLine(label: Int, value: String) {
    Text(
        text = "${stringResource(label)}: $value",
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
    )
}

@Composable
private fun LoadErrorText() {
    Text(
        text = stringResource(R.string.stats_detail_load_error),
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun StatsTimeRange.label(): String = when (preset) {
    StatsRangePreset.DEFAULT_SEVEN_DAYS -> stringResource(R.string.stats_detail_default_7_days)
    StatsRangePreset.LAST_24_HOURS -> stringResource(R.string.stats_detail_last_24_hours)
    StatsRangePreset.LAST_3_DAYS -> stringResource(R.string.stats_detail_last_3_days)
    StatsRangePreset.LAST_14_DAYS -> stringResource(R.string.stats_detail_last_14_days)
    StatsRangePreset.LAST_30_DAYS -> stringResource(R.string.stats_detail_last_30_days)
    StatsRangePreset.CUSTOM -> {
        val formatter = DateTimeFormatter.ofPattern("MM/dd")
        val start = customStart?.format(formatter).orEmpty()
        val end = customEnd?.format(formatter).orEmpty()
        if (start.isBlank()) stringResource(R.string.stats_detail_custom_range) else "$start - $end"
    }
}

@Composable
private fun Set<String>.providerLabel(): String = when (size) {
    0 -> stringResource(R.string.stats_detail_all_providers)
    1 -> first()
    else -> stringResource(R.string.stats_detail_selected_providers, size)
}

private fun Long?.formatNanosDuration(): String = when {
    this == null -> "—"
    this < 1_000_000_000L -> String.format(Locale.US, "%.3f ms", this / 1_000_000.0)
    this < 60_000_000_000L -> String.format(Locale.US, "%.3f s", this / 1_000_000_000.0)
    else -> String.format(Locale.US, "%.2f min", this / 60_000_000_000.0)
}

private fun LocalDate.toUtcMillis(): Long = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

private fun Long.toUtcDate(): LocalDate = Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()
