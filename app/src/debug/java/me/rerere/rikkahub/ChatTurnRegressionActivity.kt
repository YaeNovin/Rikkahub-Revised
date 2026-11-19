package me.rerere.rikkahub

import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import me.rerere.ai.provider.*
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.ui.components.message.GoogleSearchSuggestions
import me.rerere.rikkahub.ui.pages.assistant.detail.GeminiSearchSettings
import me.rerere.rikkahub.ui.pages.setting.VolcengineSearchOptionsEditor
import me.rerere.search.SearchServiceOptions
import org.koin.android.ext.android.get
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.uuid.Uuid

/** Isolated debug-only fixture using the production service, Room persistence and HTTP provider. */
class ChatTurnRegressionActivity : ComponentActivity() {
    private val service: ChatService by lazy { get() }
    private val store: SettingsStore by lazy { get() }
    private val repository: ConversationRepository by lazy { get() }
    private val conversationId = Uuid.random()
    private lateinit var server: ServerSocket
    private val connections = AtomicInteger()
    private val disconnects = AtomicInteger()
    private var ready by mutableStateOf(false)
    private var stream by mutableStateOf(false)
    private var sent by mutableIntStateOf(0)
    private var completed by mutableIntStateOf(0)
    private var status by mutableStateOf("初始化本地测试")
    private var requestStage by mutableStateOf("尚未请求")
    private val reports = mutableListOf<JsonObject>()
    private var sentAt = 0L
    private var visibleLatency = -1L
    private val runId by lazy { intent.getStringExtra("run").orEmpty().filter { it.isLetterOrDigit() }.take(64).ifBlank { "local" } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        check(BuildConfig.APPLICATION_ID.endsWith(".revised.appearanceqa"))
        server = ServerSocket(0, 20, InetAddress.getByName("127.0.0.1"))
        serve()
        lifecycleScope.launch {
            store.settingsFlow.first { !it.init }
            val model = Model(modelId = "qa-local-model", displayName = "本地延迟接口")
            val provider = ProviderSetting.OpenAI(baseUrl = "http://127.0.0.1:${server.localPort}/v1",
                apiKey = "local-test-fixture", models = listOf(model))
            val assistant = Assistant(chatModelId = model.id, name = "本地消息取消回归", streamOutput = stream,
                enableRollingContextCompression = false)
            store.update { it.copy(providers = listOf(provider), assistants = listOf(assistant), assistantId = assistant.id,
                chatModelId = model.id, enableTitleGeneration = false, enableSuggestion = false, enableGenerationRetry = false,
                mcpServers = emptyList()) }
            store.settingsFlow.first { !it.init && it.chatModelId == model.id && it.assistantId == assistant.id }
            service.initializeConversation(conversationId)
            service.addConversationReference(conversationId)
            ready = true
            status = "READY 本地接口，无付费请求"
        }
        lifecycleScope.launch {
            service.getDisplayedConversationFlow(conversationId).collect { state ->
                if (sentAt > 0 && state.currentMessages.any { it.toText() == "QA message $sent" } && visibleLatency < 0) {
                    visibleLatency = SystemClock.elapsedRealtime() - sentAt
                }
            }
        }
        setContent {
            var tab by remember { mutableIntStateOf(0) }
            var search by remember { mutableStateOf(GeminiSearchOptions()) }
            var volc by remember { mutableStateOf(SearchServiceOptions.VolcengineOptions()) }
            var dark by remember { mutableStateOf(true) }
            val conversation by service.getDisplayedConversationFlow(conversationId)
                .collectAsState(service.getConversationFlow(conversationId).value)
            val job by service.getGenerationJobStateFlow(conversationId).collectAsState(null)
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.safeDrawingPadding().padding(12.dp)) {
                        Text(status)
                        Row {
                            TextButton(onClick = { tab = 0 }) { Text("发送停止") }
                            TextButton(onClick = { tab = 1 }) { Text("Gemini搜索") }
                            TextButton(onClick = { tab = 2 }) { Text("豆包搜索") }
                        }
                        when (tab) {
                            0 -> {
                                Text("已发送 $sent · 已核验 $completed · 连接 ${connections.get()}")
                                Text("回答状态：${if (job == null) "已停止" else "生成中"}")
                                Text("接口状态：$requestStage")
                                Row {
                                    Text("流式接口")
                                    Switch(stream, { stream = it }, enabled = job == null)
                                }
                                Button(onClick = { send(false) }, enabled = ready && job == null) { Text("发送测试消息") }
                                Button(onClick = { stop() }, enabled = ready && job != null) { Text("停止一次") }
                                Button(onClick = { send(true) }, enabled = ready && job == null) { Text("立即发送并停止") }
                                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                                    conversation.currentMessages.forEach { Text("${it.role}: ${it.toText()}", Modifier.padding(6.dp)) }
                                }
                            }
                            1 -> Column(Modifier.verticalScroll(rememberScrollState())) {
                                TextButton(onClick = { dark = !dark }) { Text(if (dark) "切换浅色" else "切换深色") }
                                GoogleSearchSuggestions(UIMessageAnnotation.GoogleSearchSuggestions(
                                    html = "<div style='padding:12px;background:#edf1f8;color:#222;border-radius:12px'>Google 搜索建议 · <a href='https://www.google.com/search?q=Gemini'>Gemini 官方文档</a></div>"))
                                GeminiSearchSettings(search, true, true) { search = it }
                            }
                            2 -> Column(Modifier.verticalScroll(rememberScrollState())) {
                                VolcengineSearchOptionsEditor(volc) { volc = it }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun send(immediate: Boolean) {
        lifecycleScope.launch {
            store.update { settings -> settings.copy(assistants = settings.assistants.map { it.copy(streamOutput = stream) }) }
            sent++
            requestStage = "等待本地请求"
            visibleLatency = -1
            sentAt = SystemClock.elapsedRealtime()
            service.sendMessage(conversationId, listOf(UIMessagePart.Text("QA message $sent")))
            if (immediate) stop()
        }
    }

    private fun stop() {
        val number = sent
        val clickAt = SystemClock.elapsedRealtime()
        val job = service.requestStopGeneration(conversationId)
        val acknowledged = SystemClock.elapsedRealtime() - clickAt
        status = "停止已受理 $number"
        lifecycleScope.launch {
            try {
                val expected = "QA message $number"
                withTimeout(10000) {
                    job?.join()
                    service.getConversationFlow(conversationId).first { c -> c.currentMessages.any { it.toText() == expected } }
                    while (withContext(Dispatchers.IO) { repository.getConversationById(conversationId) }
                            ?.currentMessages?.count { it.toText() == expected } != 1) delay(20)
                }
                completed++
                reports += buildJsonObject {
                    put("message", number); put("persisted", true); put("stop_ack_ms", acknowledged)
                    put("stop_complete_ms", SystemClock.elapsedRealtime() - clickAt)
                    put("visible_ms", visibleLatency); put("stream", stream)
                    put("connections", connections.get()); put("disconnected", disconnects.get())
                }
                status = "PASS $completed 用户消息已显示并保存"
            } catch (error: Exception) {
                status = "FAIL ${error.javaClass.simpleName}"
                reports += buildJsonObject { put("message", number); put("persisted", false); put("error", error.toString()) }
            }
            withContext(Dispatchers.IO) {
                java.io.File(filesDir, "chat-turn-$runId.json").writeText(buildJsonObject {
                    put("conversationId", conversationId.toString())
                    put("reports", JsonArray(reports.toList()))
                }.toString())
            }
        }
    }

    private fun serve() = thread(isDaemon = true, name = "qa-turn-server") {
        while (!server.isClosed) {
            val socket = runCatching { server.accept() }.getOrNull() ?: break
            thread(isDaemon = true, name = "qa-slow-response") {
                try {
                    socket.use {
                        val input = socket.getInputStream()
                        val header = StringBuilder()
                        while (!header.endsWith("\r\n\r\n")) {
                            val byte = input.read(); if (byte < 0) return@thread
                            header.append(byte.toChar())
                        }
                        val size = Regex("(?i)Content-Length: (\\d+)").find(header)?.groupValues?.get(1)?.toInt() ?: 0
                        val bytes = ByteArray(size)
                        var offset = 0
                        while (offset < size) { val count = input.read(bytes, offset, size - offset); if (count < 0) return@thread; offset += count }
                        val body = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
                        val streaming = (body["stream"] as? JsonPrimitive)?.booleanOrNull == true
                        connections.incrementAndGet()
                        val output = socket.getOutputStream()
                        if (streaming) {
                            output.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n".toByteArray())
                            runOnUiThread { requestStage = "流式响应已开始" }
                            repeat(200) { index ->
                                val data = "data: {\"id\":\"qa\",\"choices\":[{\"delta\":{\"content\":\" local chunk $index\"}}]}\n\n"
                                output.write(data.toByteArray()); output.flush(); Thread.sleep(100)
                            }
                        } else {
                            output.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 999999\r\n\r\n{".toByteArray())
                            output.flush()
                            runOnUiThread { requestStage = "正文读取中" }
                            input.read()
                        }
                    }
                } catch (_: Exception) { } finally { disconnects.incrementAndGet() }
            }
        }
    }

    override fun onDestroy() {
        service.requestStopGeneration(conversationId)
        if (ready) service.removeConversationReference(conversationId)
        server.close()
        super.onDestroy()
    }
}
