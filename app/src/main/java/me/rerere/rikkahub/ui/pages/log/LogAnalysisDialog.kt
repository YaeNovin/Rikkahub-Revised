package me.rerere.rikkahub.ui.pages.log

import android.content.ClipData
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.ai.provider.ModelType
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.ai.ModelListSheet
import me.rerere.rikkahub.ui.components.ai.rememberModelListState
import me.rerere.rikkahub.ui.components.ui.AppearanceAlertDialog
import java.util.Locale
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock

@Composable
internal fun LogAnalysisDialog(vm: LogVM) {
    val analysis by vm.analysis.collectAsStateWithLifecycle()
    val historyOpen by vm.showAnalysisHistory.collectAsStateWithLifecycle()
    val history by vm.analysisHistory.collectAsStateWithLifecycle()
    if (historyOpen) AppearanceAlertDialog(
        onDismissRequest = { vm.showAnalysisHistory.value = false }, title = { Text("分析历史") },
        text = { Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
            if (history.isEmpty()) Text("暂无分析记录")
            history.forEach { saved -> TextButton(onClick = { vm.openSavedAnalysis(saved) }) {
                Text("${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(saved.createdAt))}\n${saved.provider} · ${saved.model} · ${saved.status}")
            } }
        } }, confirmButton = { TextButton(onClick = { vm.showAnalysisHistory.value = false }) { Text("关闭") } })
    val state = analysis ?: return
    val settings by vm.settings.collectAsStateWithLifecycle()
    val modelId by vm.analysisModelId.collectAsStateWithLifecycle()
    val selector = rememberModelListState(modelId, settings.providers, ModelType.CHAT)
    var showPayload by remember(state.id) { mutableStateOf(false) }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var pendingExport by remember { mutableStateOf<String?>(null) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        val content = pendingExport
        pendingExport = null
        if (uri != null && content != null) scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { it.write(content) }
                        ?: error("Export unavailable")
                }
                vm.message.value = R.string.log_page_export_success
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { vm.message.value = R.string.log_page_export_failed }
        }
    }

    AppearanceAlertDialog(
        onDismissRequest = vm::closeAnalysis,
        title = { Text(stringResource(R.string.log_page_ai_analysis)) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.log_page_analysis_notice), style = MaterialTheme.typography.bodySmall)
                state.savedRecord?.let { saved -> Text("${saved.provider} · ${saved.model}\n${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(saved.createdAt))}", style = MaterialTheme.typography.bodySmall) }
                TextButton(onClick = selector::open, enabled = !state.running) {
                    Text(selector.currentModel?.displayName ?: stringResource(R.string.model_list_select_model))
                }
                TextButton(onClick = { showPayload = !showPayload }) {
                    Text(stringResource(if (showPayload) R.string.log_page_analysis_hide_payload else R.string.log_page_analysis_show_payload))
                }
                if (showPayload) SelectionContainer {
                    Text(state.payload, style = MaterialTheme.typography.bodySmall)
                }
                if (state.running) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(stringResource(R.string.log_page_analysis_running))
                }
                state.error?.let {
                    Text(stringResource(it), color = MaterialTheme.colorScheme.error)
                }
                if (state.result.isNotEmpty()) {
                    IconButton(onClick = {
                        scope.launch {
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("AI analysis", state.result)))
                        }
                    }) { Icon(HugeIcons.Copy01, stringResource(R.string.copy)) }
                    MarkdownBlock(content = state.result)
                    TextButton(onClick = {
                        pendingExport = "# AI 日志分析\n\n" + (state.savedRecord?.let { "${it.provider} · ${it.model}\n${java.time.Instant.ofEpochMilli(it.createdAt)}\n\n" } ?: "") + state.result + "\n\n## 原始诊断快照\n\n```json\n" + state.payload + "\n```"
                        exporter.launch("log-analysis-${System.currentTimeMillis()}.md")
                    }) { Text("导出分析") }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { vm.analyze(Locale.getDefault().toLanguageTag()) },
                enabled = !state.running && selector.currentModel != null,
            ) { Text(stringResource(R.string.log_page_analysis_start)) }
        },
        dismissButton = {
            TextButton(onClick = { if (state.running) vm.cancelAnalysis() else vm.closeAnalysis() }) {
                Text(stringResource(if (state.running) R.string.cancel else R.string.confirm))
            }
        },
    )
    ModelListSheet(selector, onSelect = { vm.selectAnalysisModel(it.id) })
}
