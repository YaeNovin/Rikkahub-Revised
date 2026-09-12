package me.rerere.rikkahub.ui.pages.videogen

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.ai.provider.*
import me.rerere.ai.provider.providers.openai.miniMaxVideoSetting
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.db.entity.VideoGenerationTaskEntity
import me.rerere.rikkahub.data.model.VideoGenerationRequestState
import me.rerere.rikkahub.data.model.VideoGenerationTaskState
import me.rerere.rikkahub.data.repository.VideoGenerationTaskRepository
import me.rerere.rikkahub.data.video.VideoGenerationCoordinator
import me.rerere.rikkahub.ui.components.ui.AppearanceAlertDialog
import me.rerere.rikkahub.ui.components.ui.TopAppBar
import me.rerere.rikkahub.utils.JsonInstant
import org.koin.compose.koinInject
import java.io.File
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.core.MessageRole
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.uuid.Uuid

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun VideoGenPage() {
    val store: SettingsStore = koinInject()
    val manager: ProviderManager = koinInject()
    val repository: VideoGenerationTaskRepository = koinInject()
    val coordinator: VideoGenerationCoordinator = koinInject()
    val mediaService: me.rerere.rikkahub.data.ai.MediaGenerationService = koinInject()
    val files: FilesManager = koinInject()
    val conversations: ConversationRepository = koinInject()
    val settings by store.settingsFlowRaw.collectAsStateWithLifecycle(initialValue = null)
    val tasks by repository.observeAll().collectAsStateWithLifecycle(initialValue = emptyList())
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var selectedId by rememberSaveable { mutableStateOf("") }
    var requestJson by rememberSaveable { mutableStateOf(JsonInstant.encodeToString(VideoGenerationRequestState())) }
    val request = remember(requestJson) { JsonInstant.decodeFromString<VideoGenerationRequestState>(requestJson) }
    fun update(value: VideoGenerationRequestState) { requestJson = JsonInstant.encodeToString(value) }
    var busy by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    var referenceRole by remember { mutableStateOf(VideoReferenceImageRole.FIRST_FRAME) }
    var exportPath by rememberSaveable { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<String?>(null) }
    var roleNotes by rememberSaveable { mutableStateOf("") }
    var worldSummary by rememberSaveable { mutableStateOf("") }
    var storyboard by rememberSaveable { mutableStateOf("") }
    var selectedTasks by remember { mutableStateOf(setOf<String>()) }
    var confirmDelete by remember { mutableStateOf(false) }
    val choices = settings?.providers.orEmpty().filter { it.enabled }.flatMap { provider ->
        provider.models.filter { it.type == ModelType.VIDEO && manager.videoGenerationConstraints(provider, it).supportsGeneration }
            .map { provider to it }
    }
    val selected = choices.firstOrNull { it.second.id.toString() == selectedId }
    val constraints = selected?.let { manager.videoGenerationConstraints(it.first, it.second) }
    LaunchedEffect(selectedId) {
        if (constraints != null) {
            update(request.copy(
                inputMode = request.inputMode.takeIf { it in constraints.supportedInputModes } ?: constraints.supportedInputModes.first(),
                aspectRatio = request.aspectRatio?.takeIf { it in constraints.supportedAspectRatios },
                resolution = request.resolution?.takeIf { it in constraints.supportedResolutions },
                durationSeconds = request.durationSeconds?.takeIf { it in constraints.supportedDurationsSeconds || constraints.customDurationRangeSeconds?.contains(it) == true },
            ))
        }
    }
    fun runAction(action: suspend () -> Unit) {
        scope.launch {
            try { action() } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { snackbar.showSnackbar(e.message ?: "操作失败") }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runAction {
            val managed = files.saveManagedFromUri(FileFolders.UPLOAD, uri)
            update(request.copy(referenceImages = request.referenceImages.filterNot { it.role == referenceRole } +
                VideoReferenceImage(Uri.fromFile(files.getFile(managed)).toString(), referenceRole)))
        }
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { uri ->
        val path = exportPath
        if (uri != null && path != null) runAction {
            withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                    File(path).inputStream().use { it.copyTo(output) }
                } ?: error("无法打开导出位置")
            }
            snackbar.showSnackbar("视频已导出")
        }
        exportPath = null
    }
    Scaffold(
        topBar = { TopAppBar(title = { Text("视频生成") }, navigationIcon = { BackButton() }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).imePadding(),
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                var modelsOpen by remember { mutableStateOf(false) }
                Box {
                    OutlinedButton(onClick = { modelsOpen = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(selected?.let { "${it.first.name} · ${it.second.displayName.ifBlank { it.second.modelId }}" } ?: "选择视频模型")
                    }
                    DropdownMenu(expanded = modelsOpen, onDismissRequest = { modelsOpen = false }) {
                        choices.forEach { (provider, model) ->
                            DropdownMenuItem(text = { Text("${provider.name} · ${model.displayName.ifBlank { model.modelId }}") },
                                onClick = { selectedId = model.id.toString(); modelsOpen = false; update(request.copy(referenceImages = emptyList())) })
                        }
                    }
                }
                if (choices.isEmpty()) Text("请在供应商设置中配置已支持的视频模型及凭据。", style = MaterialTheme.typography.bodySmall)
                if (selected?.second?.modelId in setOf("as-sd2.0-fast", "video-ds-2.0", "video-ds-2.0-fast"))
                    Text("当前通道固定 15 秒、720P，仅支持文生视频及横竖比例。", style = MaterialTheme.typography.bodySmall)
                if (selected?.second?.modelId?.startsWith("kling-v") == true)
                    Text("可灵当前使用专业模式（pro）；凭据格式为 AccessKey:SecretKey。", style = MaterialTheme.typography.bodySmall)
            }
            item { OutlinedTextField(value = request.prompt, onValueChange = { update(request.copy(prompt = it)) },
                label = { Text("视频描述") }, modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 8) }
            if (constraints != null) item {
                val pager = rememberPagerState { 2 }
                SecondaryTabRow(selectedTabIndex = pager.currentPage) {
                    listOf("基础选项", "高级选项").forEachIndexed { index, title ->
                        Tab(selected = pager.currentPage == index, onClick = { scope.launch { pager.animateScrollToPage(index) } }, text = { Text(title) })
                    }
                }
                HorizontalPager(state = pager, modifier = Modifier.fillMaxWidth().height(310.dp)) { page ->
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (page == 0) {
                            VideoChoice("生成方式", request.inputMode, constraints.supportedInputModes.toList(),
                                { when (it) { VideoGenerationInputMode.TEXT_TO_VIDEO -> "文生视频"; VideoGenerationInputMode.IMAGE_TO_VIDEO -> "图生视频"; VideoGenerationInputMode.KEYFRAMES_TO_VIDEO -> "首尾帧"; else -> "视频参考" } }) {
                                update(request.copy(inputMode = it, referenceImages = emptyList()))
                            }
                            if (constraints.supportedAspectRatios.isNotEmpty()) VideoChoice("比例", request.aspectRatio, listOf(null) + constraints.supportedAspectRatios, { it ?: "模型默认" }) { update(request.copy(aspectRatio = it)) }
                            val miniMax = selected?.let { it.first.miniMaxVideoSetting(it.second) } != null
                            if (constraints.supportedResolutions.isNotEmpty()) VideoChoice("分辨率", request.resolution, listOf(null) + constraints.supportedResolutions, { it ?: "模型默认" }) { update(request.copy(resolution = it,
                                durationSeconds = if (miniMax && it == "1080P" && request.durationSeconds == 10) 6 else request.durationSeconds)) }
                            val durations = if (miniMax && request.resolution == "1080P") listOf(6) else constraints.customDurationRangeSeconds?.toList() ?: constraints.supportedDurationsSeconds.toList()
                            VideoChoice("时长", request.durationSeconds, listOf(null) + durations, { it?.let { "$it 秒" } ?: "模型默认" }) { update(request.copy(durationSeconds = it)) }
                            if (request.inputMode != VideoGenerationInputMode.TEXT_TO_VIDEO) {
                                listOf(VideoReferenceImageRole.FIRST_FRAME, VideoReferenceImageRole.LAST_FRAME)
                                    .filter { it == VideoReferenceImageRole.FIRST_FRAME || request.inputMode == VideoGenerationInputMode.KEYFRAMES_TO_VIDEO }
                                    .forEach { role ->
                                        Row {
                                            TextButton(onClick = { referenceRole = role; picker.launch(arrayOf("image/*")) }) {
                                                Text((if (role == VideoReferenceImageRole.FIRST_FRAME) "首帧" else "尾帧") +
                                                    if (request.referenceImages.any { it.role == role }) "：已选择" else "：选择图片")
                                            }
                                            if (request.referenceImages.any { it.role == role }) TextButton(onClick = { update(request.copy(referenceImages = request.referenceImages.filterNot { it.role == role })) }) { Text("移除") }
                                        }
                                    }
                            }
                        } else {
                            if (constraints.supportsPromptEnhancement) VideoBoolean("自动优化提示词", request.promptEnhancement) { update(request.copy(promptEnhancement = it)) }
                            if (constraints.supportsFastPretreatment && request.promptEnhancement != false) {
                                VideoBoolean("快速预处理", request.fastPretreatment) { update(request.copy(fastPretreatment = it)) }
                                Text("启用提示词优化时缩短预处理时间。1080P 仅支持 6 秒。", style = MaterialTheme.typography.bodySmall)
                            }
                            if (constraints.supportsNegativePrompt) OutlinedTextField(request.negativePrompt,
                                { update(request.copy(negativePrompt = it)) }, label = { Text("负面描述") }, modifier = Modifier.fillMaxWidth())
                            if (constraints.supportsSeed) OutlinedTextField(value = request.seed?.toString().orEmpty(),
                                onValueChange = { if (it.isEmpty() || it.toLongOrNull() != null) update(request.copy(seed = it.toLongOrNull())) },
                                label = { Text("随机种子（留空随机）") }, modifier = Modifier.fillMaxWidth())
                            if (constraints.supportsAudio) VideoBoolean("生成声音", request.generateAudio) { update(request.copy(generateAudio = it)) }
                            if (constraints.supportsWatermark) VideoBoolean("水印", request.watermark) { update(request.copy(watermark = it)) }
                            if (constraints.supportsCameraFixed) VideoBoolean("固定镜头", request.cameraFixed) { update(request.copy(cameraFixed = it)) }
                            if (constraints.supportsReturnLastFrame) VideoBoolean("返回尾帧", request.returnLastFrame) { update(request.copy(returnLastFrame = it)) }
                            OutlinedTextField(roleNotes, { roleNotes = it }, label = { Text("角色描述（可选）") }, modifier = Modifier.fillMaxWidth())
                            var assistantPicker by remember { mutableStateOf(false) }
                            Box {
                                TextButton(onClick = { assistantPicker = true }) { Text("从助手导入角色描述") }
                                DropdownMenu(expanded = assistantPicker, onDismissRequest = { assistantPicker = false }) {
                                    settings?.assistants.orEmpty().forEach { assistant ->
                                        DropdownMenuItem(text = { Text(assistant.name.ifBlank { "未命名助手" }) }, onClick = {
                                            roleNotes = listOf(assistant.name, assistant.systemPrompt).filter { it.isNotBlank() }.joinToString("\n").take(4000)
                                            assistantPicker = false
                                        })
                                    }
                                }
                            }
                            OutlinedTextField(worldSummary, { worldSummary = it }, label = { Text("世界书摘要（可选）") }, modifier = Modifier.fillMaxWidth())
                            var worldPicker by remember { mutableStateOf(false) }
                            Box {
                                TextButton(onClick = { worldPicker = true }) { Text("从世界书导入简介") }
                                DropdownMenu(expanded = worldPicker, onDismissRequest = { worldPicker = false }) {
                                    settings?.lorebooks.orEmpty().forEach { book ->
                                        DropdownMenuItem(text = { Text(book.name) }, onClick = {
                                            worldSummary = listOf(book.name, book.description).filter { it.isNotBlank() }.joinToString("\n").take(4000)
                                            worldPicker = false
                                        })
                                    }
                                }
                            }
                            OutlinedTextField(storyboard, { storyboard = it }, label = { Text("镜头脚本（可选）") }, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
            item { Button(onClick = { confirm = true }, enabled = selected != null && request.prompt.isNotBlank() && !busy,
                modifier = Modifier.fillMaxWidth()) { Text(if (busy) "提交中" else "生成视频") } }
            item {
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text("生成历史", style = MaterialTheme.typography.titleMedium)
                    if (selectedTasks.isNotEmpty()) TextButton(onClick = { confirmDelete = true }) { Text("删除 ${selectedTasks.size} 条记录") }
                }
            }
            items(tasks, key = { it.id }) { task ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(task.modelApiId, style = MaterialTheme.typography.labelMedium)
                        if (task.status == VideoGenerationTaskState.SUCCEEDED) {
                            val outputs by repository.observeOutputs(task.id).collectAsStateWithLifecycle(initialValue = emptyList())
                            outputs.firstOrNull()?.localRelativePath?.let { path ->
                                me.rerere.rikkahub.ui.components.message.InlineVideo(
                                    Uri.fromFile(File(context.filesDir, path)).toString())
                            }
                        }
                        var diagnosticsOpen by remember { mutableStateOf(false) }
                        TextButton(onClick = { diagnosticsOpen = !diagnosticsOpen }) { Text("任务详情") }
                        if (diagnosticsOpen) {
                            Text("供应商：${task.providerName}\n任务：${task.remoteTaskId ?: task.id}\n重试次数：${task.attemptCount}\n耗时：${((task.completedAt ?: task.updatedAt) - task.createdAt).coerceAtLeast(0) / 1000} 秒",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        if (task.status.isTerminal) Checkbox(checked = task.id in selectedTasks,
                            onCheckedChange = { checked -> selectedTasks = if (checked) selectedTasks + task.id else selectedTasks - task.id })
                        Text(task.prompt, maxLines = 3)
                        Text(videoStatusLabel(task.status), color = MaterialTheme.colorScheme.primary)
                        if (!task.status.isTerminal) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        task.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                        FlowRow {
                            val taskProvider = settings?.providers?.firstOrNull { it.id.toString() == task.providerId }
                            val taskModel = taskProvider?.models?.firstOrNull { it.id.toString() == task.modelId }
                            val canCancelRemote = taskProvider != null && taskModel != null && manager.videoGenerationConstraints(taskProvider, taskModel).supportsCancellation
                            if (!task.status.isTerminal) TextButton(enabled = task.status != VideoGenerationTaskState.CANCELING &&
                                (canCancelRemote || task.status in setOf(VideoGenerationTaskState.CREATED, VideoGenerationTaskState.DOWNLOADING)),
                                onClick = { runAction { coordinator.cancelTask(task.id) } }) { Text("取消") }
                            if (task.status == VideoGenerationTaskState.FAILED && task.remoteTaskId != null)
                                TextButton(onClick = { runAction { coordinator.retryTask(task.id) } }) { Text("重试获取结果") }
                            TextButton(onClick = { requestJson = task.requestJson; selectedId = task.modelId; roleNotes = ""; worldSummary = ""; storyboard = "" }) { Text("复用参数") }
                            if (task.status.isTerminal) TextButton(onClick = { runAction { repository.deleteTask(task.id) } }) { Text("删除记录") }
                        }
                        if (task.status == VideoGenerationTaskState.SUCCEEDED) FlowRow {
                            TextButton(onClick = { runAction {
                                val output = repository.getOutputs(task.id).firstOrNull() ?: error("没有视频结果")
                                val managed = output.localRelativePath?.let { files.getByRelativePath(it) } ?: error("视频文件不存在")
                                preview = files.getFile(managed).absolutePath
                            } }) { Text("播放 / 全屏") }
                            TextButton(onClick = { runAction {
                                val output = repository.getOutputs(task.id).firstOrNull() ?: error("没有视频结果")
                                val managed = output.localRelativePath?.let { files.getByRelativePath(it) } ?: error("视频文件不存在")
                                exportPath = files.getFile(managed).absolutePath
                                export.launch(managed.displayName)
                            } }) { Text("导出") }
                            TextButton(onClick = { runAction {
                                val currentSettings = settings ?: error("设置尚未加载")
                                val output = repository.getOutputs(task.id).firstOrNull() ?: error("没有视频结果")
                                val managed = output.localRelativePath?.let { files.getByRelativePath(it) } ?: error("视频文件不存在")
                                val source = files.getFile(managed)
                                val copied = files.saveManagedFromUri(FileFolders.GENERATED_VIDEOS, Uri.fromFile(source), managed.displayName, managed.mimeType)
                                val message = UIMessage(role = MessageRole.ASSISTANT,
                                    modelId = runCatching { Uuid.parse(task.modelId) }.getOrNull(),
                                    parts = listOf(UIMessagePart.Video(Uri.fromFile(files.getFile(copied)).toString(),
                                        metadata = buildJsonObject { put("generated_video", true); put("model", task.modelApiId); put("status", "succeeded") })))
                                conversations.insertConversation(Conversation(assistantId = currentSettings.assistantId,
                                    title = task.prompt.take(32), messageNodes = listOf(
                                        UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text(task.prompt))).toMessageNode(), message.toMessageNode())))
                                snackbar.showSnackbar("已保存为当前助手的新对话")
                            } }) { Text("保存到聊天") }
                        }
                    }
                }
            }
        }
    }
    if (confirmDelete) AppearanceAlertDialog(onDismissRequest = { confirmDelete = false },
        title = { Text("删除选中的历史记录？") }, text = { Text("已保存的视频文件仍保留在本地文件管理中。") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; runAction { selectedTasks.forEach { repository.deleteTask(it) }; selectedTasks = emptySet() } }) { Text("删除记录") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } })
    if (confirm && selected != null) AppearanceAlertDialog(onDismissRequest = { confirm = false },
        title = { Text("确认生成视频") },
        text = { Text("${selected.first.name}\n${selected.second.modelId}\n${request.durationSeconds?.let { "$it 秒" } ?: "默认时长"} · ${request.resolution ?: "默认分辨率"}\n生成可能产生费用，时长和分辨率越高，费用及等待时间通常越高。") },
        confirmButton = { TextButton(onClick = {
            confirm = false; busy = true
            runAction {
                try {
                    val prompt = listOf(request.prompt, roleNotes.takeIf { it.isNotBlank() }?.let { "角色：$it" },
                        worldSummary.takeIf { it.isNotBlank() }?.let { "世界设定摘要：$it" }, storyboard.takeIf { it.isNotBlank() }?.let { "镜头脚本：$it" }).filterNotNull().joinToString("\n\n")
                    mediaService.generateVideo(selected.first, selected.second, request.copy(prompt = prompt))
                } finally { busy = false }
            }
        }) { Text("确认生成") } }, dismissButton = { TextButton(onClick = { confirm = false }) { Text("取消") } })
    preview?.let { path ->
        Dialog(onDismissRequest = { preview = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize()) {
                Column {
                    TextButton(onClick = { preview = null }) { Text("关闭预览") }
                    val player = remember(path) { ExoPlayer.Builder(context).build().apply { setMediaItem(MediaItem.fromUri(Uri.fromFile(File(path)))); prepare() } }
                    DisposableEffect(player) { onDispose { player.release() } }
                    AndroidView(factory = { PlayerView(it).apply { this.player = player } },
                        modifier = Modifier.fillMaxWidth().weight(1f), onRelease = { it.player = null })
                }
            }
        }
    }
}

@Composable
private fun <T> VideoChoice(label: String, value: T, options: List<T>, name: (T) -> String, onChange: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) { Text("$label：${name(value)}") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { item -> DropdownMenuItem(text = { Text(name(item)) }, onClick = { onChange(item); open = false }) }
        }
    }
}

@Composable
private fun VideoBoolean(label: String, value: Boolean?, onChange: (Boolean?) -> Unit) =
    VideoChoice(label, value, listOf(null, true, false), { when (it) { true -> "开启"; false -> "关闭"; null -> "模型默认" } }, onChange)

private fun videoStatusLabel(state: VideoGenerationTaskState): String = when (state) {
    VideoGenerationTaskState.CREATED -> "等待提交"
    VideoGenerationTaskState.SUBMITTING -> "正在提交"
    VideoGenerationTaskState.QUEUED -> "排队中"
    VideoGenerationTaskState.RUNNING -> "生成中"
    VideoGenerationTaskState.DOWNLOADING -> "下载中"
    VideoGenerationTaskState.SUCCEEDED -> "已完成"
    VideoGenerationTaskState.FAILED -> "失败"
    VideoGenerationTaskState.CANCELING -> "正在取消"
    VideoGenerationTaskState.CANCELED -> "已取消"
    VideoGenerationTaskState.EXPIRED -> "已过期"
}
