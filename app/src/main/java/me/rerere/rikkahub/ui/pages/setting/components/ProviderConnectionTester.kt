package me.rerere.rikkahub.ui.pages.setting.components

import android.content.ClipData
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import me.rerere.rikkahub.ui.components.ui.AppearanceAlertDialog as AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import me.rerere.rikkahub.ui.components.ui.AppearanceModalBottomSheet as ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderDiagnosticCheck
import me.rerere.ai.provider.ProviderDiagnosticStatus
import me.rerere.ai.provider.ProviderDiagnostics
import me.rerere.ai.provider.ProviderDiagnosticsReport
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderProtocolTraceCodec
import me.rerere.ai.provider.ProviderProtocolTraceReplayer
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.withDetectedCapabilities
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Connect
import me.rerere.rikkahub.R
import me.rerere.rikkahub.service.formatUserFacingError
import me.rerere.rikkahub.service.toDiagnosticMessage
import me.rerere.rikkahub.ui.components.ai.ModelSelector
import me.rerere.rikkahub.ui.theme.extendColors
import me.rerere.rikkahub.utils.UiState
import org.koin.compose.koinInject

@Composable
fun ProviderConnectionTester(
    internalProvider: ProviderSetting,
    onApplyDetectedCapabilities: (Model) -> Unit = {},
) {
    var showTestDialog by remember { mutableStateOf(false) }
    val providerManager = koinInject<ProviderManager>()
    val scope = rememberCoroutineScope()

    IconButton(onClick = { showTestDialog = true }) {
        Icon(HugeIcons.Connect, contentDescription = stringResource(R.string.setting_provider_page_diagnostics))
    }

    if (showTestDialog) {
        var model by remember(internalProvider) {
            mutableStateOf(internalProvider.models.firstOrNull { it.type == ModelType.CHAT })
        }
        var state: UiState<ProviderDiagnosticsReport> by remember { mutableStateOf(UiState.Idle) }
        var showTrace by remember { mutableStateOf(false) }
        val report = (state as? UiState.Success)?.data

        AlertDialog(
            onDismissRequest = { showTestDialog = false },
            title = { Text(stringResource(R.string.setting_provider_page_diagnostics)) },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                ) {
                    ModelSelector(
                        modelId = model?.id,
                        providers = listOf(internalProvider),
                        type = ModelType.CHAT,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        model = it
                        state = UiState.Idle
                    }

                    when (state) {
                        is UiState.Idle -> DiagnosticIdleItem()
                        is UiState.Loading -> LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
                        is UiState.Error -> DiagnosticErrorItem((state as UiState.Error).error)
                        is UiState.Success -> report?.let { diagnostic ->
                            DiagnosticsReport(
                                report = diagnostic,
                                onShowTrace = { showTrace = true },
                                onApplyCapabilities = model?.let { selected ->
                                    {
                                        val detected = selected.withDetectedCapabilities(diagnostic.capabilities)
                                        if (detected != selected) {
                                            onApplyDetectedCapabilities(detected)
                                            model = detected
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showTestDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
            confirmButton = {
                TextButton(
                    enabled = model != null && state !is UiState.Loading,
                    onClick = {
                        val selectedModel = model ?: return@TextButton
                        val provider = providerManager.getProviderByType(internalProvider)
                        scope.launch {
                            state = UiState.Loading
                            state = runCatching {
                                ProviderDiagnostics.run(
                                    provider = provider,
                                    setting = internalProvider,
                                    model = selectedModel,
                                )
                            }.fold(
                                onSuccess = { value -> UiState.Success(value) },
                                onFailure = { error -> UiState.Error(error) },
                            )
                        }
                    },
                ) {
                    Text(stringResource(R.string.setting_provider_page_test))
                }
            },
        )

        val trace = report?.protocolTrace
        if (showTrace && trace != null) {
            ProtocolTraceSheet(
                trace = ProviderProtocolTraceCodec.encode(trace),
                passed = ProviderProtocolTraceReplayer.validate(trace).passed,
                onDismiss = { showTrace = false },
            )
        }
    }
}

@Composable
private fun DiagnosticsReport(
    report: ProviderDiagnosticsReport,
    onShowTrace: () -> Unit,
    onApplyCapabilities: (() -> Unit)?,
) {
    DiagnosticResultItem(
        label = stringResource(R.string.setting_provider_page_health),
        result = report.health,
    )
    DiagnosticResultItem(
        label = stringResource(R.string.setting_provider_page_model_discovery),
        status = report.modelDiscovery.status,
        detail = report.modelDiscovery.modelCount?.let {
            stringResource(R.string.setting_provider_page_models_found, it)
        },
        error = report.modelDiscovery.error,
    )
    DiagnosticResultItem(
        label = stringResource(R.string.setting_provider_page_api_latency),
        result = report.health,
        detail = report.health.latencyMillis?.let { "$it ms" },
    )
    DiagnosticResultItem(
        label = stringResource(R.string.setting_provider_page_first_output_latency),
        status = report.streaming.status,
        detail = report.streaming.firstOutputLatencyMillis?.let { "$it ms" },
        error = report.streaming.error,
    )
    DiagnosticResultItem(
        label = stringResource(R.string.setting_provider_page_first_token_latency),
        status = report.streaming.status,
        detail = report.streaming.firstTokenLatencyMillis?.let { "$it ms" }
            ?: stringResource(R.string.setting_provider_page_no_text_token),
        error = report.streaming.error,
    )
    DiagnosticResultItem(
        label = stringResource(R.string.setting_provider_page_tool),
        result = report.toolCalling,
    )

    val textCapability = stringResource(R.string.setting_provider_page_text)
    val streamingCapability = stringResource(R.string.setting_provider_page_streaming)
    val toolCapability = stringResource(R.string.setting_provider_page_tool)
    val reasoningCapability = stringResource(R.string.setting_provider_page_reasoning)
    val unsupportedCapability = stringResource(R.string.setting_provider_page_not_supported)
    val capabilities = buildList {
        if (report.capabilities.text) add(textCapability)
        if (report.capabilities.streaming) add(streamingCapability)
        if (report.capabilities.toolCalling) add(toolCapability)
        if (report.capabilities.reasoning) add(reasoningCapability)
    }
    Text(
        text = "${stringResource(R.string.setting_provider_page_capability_probe)}: " +
            capabilities.ifEmpty { listOf(unsupportedCapability) }
                .joinToString(),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
    ) {
        if (report.protocolTrace != null) {
            TextButton(onClick = onShowTrace) {
                Text(stringResource(R.string.setting_provider_page_protocol_trace))
            }
        }
        if (onApplyCapabilities != null) {
            TextButton(onClick = onApplyCapabilities) {
                Text(stringResource(R.string.setting_provider_page_apply_detected_capabilities))
            }
        }
    }
}

@Composable
private fun DiagnosticIdleItem() {
    Text(
        text = "-",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun DiagnosticResultItem(
    label: String,
    result: ProviderDiagnosticCheck,
    detail: String? = null,
) {
    DiagnosticResultItem(
        label = label,
        status = result.status,
        detail = detail ?: result.latencyMillis?.let { "$it ms" },
        error = result.error,
    )
}

@Composable
private fun DiagnosticResultItem(
    label: String,
    status: ProviderDiagnosticStatus,
    detail: String? = null,
    error: String? = null,
) {
    val context = LocalContext.current
    var showErrorSheet by remember(error) { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(104.dp),
        )
        when (status) {
            ProviderDiagnosticStatus.SUCCESS -> Text(
                text = detail ?: stringResource(R.string.status_ok),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.extendColors.green6,
            )
            ProviderDiagnosticStatus.UNSUPPORTED -> Text(
                text = stringResource(R.string.setting_provider_page_not_supported),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ProviderDiagnosticStatus.FAILURE -> Text(
                text = error?.let { context.formatUserFacingError(it) }
                    ?: stringResource(R.string.error_message_generic),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.extendColors.red6,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable { showErrorSheet = true },
            )
        }
    }

    if (showErrorSheet && !error.isNullOrBlank()) {
        val sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        )
        ModalBottomSheet(
            onDismissRequest = { showErrorSheet = false },
            sheetState = sheetState,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.7f)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.error_diagnostic_details),
                    style = MaterialTheme.typography.titleMedium,
                )
                SelectionContainer {
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                    )
                }
            }
        }
    }
}

@Composable
private fun DiagnosticErrorItem(error: Throwable) {
    val context = LocalContext.current
    var showErrorSheet by remember(error) { mutableStateOf(false) }
    Text(
        text = context.formatUserFacingError(error),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.extendColors.red6,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.clickable { showErrorSheet = true },
    )
    if (showErrorSheet) {
        ModalBottomSheet(
            onDismissRequest = { showErrorSheet = false },
            sheetState = rememberBottomSheetState(
                initialValue = SheetValue.Hidden,
                enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
            ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.7f)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.error_diagnostic_details),
                    style = MaterialTheme.typography.titleMedium,
                )
                SelectionContainer {
                    Text(
                        text = error.toDiagnosticMessage(),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                    )
                }
            }
        }
    }
}

@Composable
private fun ProtocolTraceSheet(
    trace: String,
    passed: Boolean,
    onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.8f)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.setting_provider_page_protocol_trace),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "${stringResource(R.string.setting_provider_page_trace_regression)}: " +
                    if (passed) {
                        stringResource(R.string.status_ok)
                    } else {
                        stringResource(R.string.status_failed)
                    },
                style = MaterialTheme.typography.bodySmall,
                color = if (passed) MaterialTheme.extendColors.green6 else MaterialTheme.extendColors.red6,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    onClick = {
                        scope.launch {
                            clipboard.setClipEntry(
                                ClipEntry(ClipData.newPlainText("provider-protocol-trace", trace))
                            )
                        }
                    },
                ) {
                    Text(stringResource(R.string.setting_provider_page_copy_trace))
                }
            }
            SelectionContainer {
                Text(
                    text = trace,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            }
        }
    }
}
