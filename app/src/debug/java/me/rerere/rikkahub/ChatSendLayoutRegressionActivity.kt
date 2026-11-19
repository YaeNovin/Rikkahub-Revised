package me.rerere.rikkahub

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.ui.components.ui.RabbitLoadingIndicator
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.pages.chat.ChatGenerationStatus
import me.rerere.rikkahub.ui.pages.chat.ConversationStatusBadges
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Pin

/** Local send/status/scroll stress test. Never creates conversations or calls providers. */
class ChatSendLayoutRegressionActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val legacy = intent.getBooleanExtra("legacy", false)
        val rabbit = intent.getBooleanExtra("rabbit", false)
        val cycles = intent.getIntExtra("cycles", 1800).coerceIn(1, 6000)
        val runId = intent.getStringExtra("run")?.takeIf { it.matches(Regex("[a-zA-Z0-9-]+")) } ?: "manual"
        val settings = Settings(displaySetting = DisplaySetting(useAppIconStyleLoadingIndicator = rabbit))
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                CompositionLocalProvider(LocalSettings provides settings) {
                    SharedTransitionLayout {
                        val state = rememberLazyListState(initialFirstVisibleItemIndex = 95)
                        val messages = remember { mutableStateListOf<Int>().apply { addAll(0..99) } }
                        var processing by remember { mutableStateOf<String?>(null) }
                        var loading by remember { mutableStateOf(true) }
                        var ticks by remember { mutableIntStateOf(0) }
                        var running by remember { mutableStateOf(false) }
                        var done by remember { mutableStateOf(false) }
                        val scope = rememberCoroutineScope()
                        LaunchedEffect(running) {
                            if (!running) return@LaunchedEffect
                            repeat(cycles) { tick ->
                                withFrameNanos { }
                                processing = when (tick % 4) {
                                    0 -> null
                                    1 -> "正在压缩上下文"
                                    2 -> "正在处理文档"
                                    else -> null
                                }
                                loading = tick % 23 != 0
                                if (tick % 17 == 0) messages.add(messages.last() + 1)
                                if (tick % 31 == 0) state.requestScrollToItem(messages.lastIndex)
                                ticks = tick + 1
                            }
                            processing = null
                            done = true
                            filesDir.resolve("chat-layout-$runId.json").writeText(
                                """{"pass":true,"legacy":$legacy,"rabbit":$rabbit,"cycles":$cycles,"messages":${messages.size}}""",
                            )
                            Log.i("ChatSendLayoutRegression", "PASS legacy=$legacy rabbit=$rabbit cycles=$cycles messages=${messages.size}")
                        }
                        Surface(Modifier.fillMaxSize()) {
                            Column(Modifier.safeDrawingPadding()) {
                                Text("${if (done) "PASS" else if (running) "RUNNING" else "READY"} legacy=$legacy rabbit=$rabbit ticks=$ticks", Modifier.padding(8.dp))
                                Button(onClick = { if (!running) running = true }) { Text("开始压力测试") }
                                Button(onClick = { scope.launch { state.animateScrollToItem(messages.lastIndex) } }) { Text("滚动到底部") }
                                LazyColumn(
                                    state = state,
                                    modifier = Modifier.fillMaxWidth().weight(1f).semantics { contentDescription = "压力测试消息列表" },
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    items(messages, key = { it }, contentType = { "message" }) { index ->
                                        Row(Modifier.fillMaxWidth().padding(12.dp)) {
                                            Text("${if (index % 2 == 0) "用户" else "助手"}：本地消息 $index\n" + "用于复现发送后的列表布局变化。".repeat(4), Modifier.weight(1f))
                                            if (legacy) {
                                                AnimatedVisibility(index % 3 == 0) { Icon(HugeIcons.Pin, "Pinned", Modifier.size(12.dp)) }
                                                AnimatedVisibility(loading && index % 11 == ticks % 11) { Box(Modifier.size(4.dp)) }
                                            } else {
                                                ConversationStatusBadges(pinned = index % 3 == 0,
                                                    loading = loading && index % 11 == ticks % 11)
                                            }
                                        }
                                    }
                                    if (loading) item("LoadingIndicator") {
                                        if (legacy) LegacyStatus(processing) else ChatGenerationStatus(processing)
                                    }
                                    item("bottom") { Spacer(Modifier.fillMaxWidth().height(5.dp)) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Original footer kept exclusively in Debug to compare identical layout workloads. */
@Composable
private fun LegacyStatus(processingStatus: String?) {
    Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AnimatedVisibility(visible = processingStatus == null) { RabbitLoadingIndicator(Modifier.size(28.dp)) }
        AnimatedVisibility(visible = processingStatus != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(processingStatus ?: "", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
