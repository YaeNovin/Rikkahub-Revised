package me.rerere.rikkahub

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.layout.LazyLayoutCacheWindow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.*
import me.rerere.rikkahub.data.render.RendererCatalog
import me.rerere.rikkahub.ui.context.*
import me.rerere.rikkahub.ui.pages.chat.ChatList
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import java.io.File

/** Fixed synthetic conversation on the production ChatList/ChatMessage pipeline.
 * Debug only. No repository writes, providers, persisted preferences, or test clocks. */
@OptIn(ExperimentalFoundationApi::class)
class RenderStressActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val folder = File(filesDir, "render-stress").apply { mkdirs() }
        val focused = intent.getStringExtra("renderer")?.let { RendererCatalog.find(it) }
        val image = File(folder, "fixture.png")
        Bitmap.createBitmap(960, 540, Bitmap.Config.ARGB_8888).let { bitmap ->
            Canvas(bitmap).apply {
                drawColor(AndroidColor.rgb(16, 26, 48))
                val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                repeat(12) { paint.color = AndroidColor.HSVToColor(floatArrayOf(it * 30f, .7f, .9f)); drawCircle(40f + it * 78f, 270f, 85f, paint) }
            }
            image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        val content = if (focused != null) "# Renderer ${focused.id}\n\n```${focused.id}\n${focused.example}\n```\n" else buildString {
            appendLine("# Synthetic 16K+ rendering stress output")
            // 24 * 800 whitespace words, before code/renderer data. This is a
            // conservative word count, not a claim about a vendor tokenizer.
            repeat(24) { section ->
                appendLine("\n## Section $section\n")
                repeat(16) { paragraph ->
                    appendLine(("render state frame table code cache input output color memory ".repeat(5)) + "[$section/$paragraph]\n")
                }
                appendLine("| Key | Value | State |\n|---|---:|---|\n| frame | $section | **ready** |\n| memory | 128 | `bounded` |")
                appendLine("\n- [x] completed **bold** and *italic*\n- [ ] pending\n\n> Multiline quoted content with `inline code`.\n")
                appendLine("\n```kotlin\n// ordinary code fence $section\ndata class Frame(val id: Int, val label: String)\n" +
                    (1..12).joinToString("\n") { "val frame$it = Frame($it, \"section-$section\") // comment" } + "\n```\n")
                appendLine("\n```json\n{\"section\":$section,\"ok\":true,\"values\":[1,2,3],\"escaped\":\"line\\nquote\\\"\"}\n```\n")
                appendLine("\n$$\n\\sum_{i=1}^{n} i = \\frac{n(n+1)}{2}\n$$\n")
                if (section < RendererCatalog.entries.size) {
                    val renderer = RendererCatalog.entries[section]
                    appendLine("\n### Renderer ${renderer.id}\n```${renderer.id}\n${renderer.example}\n```\n")
                }
                if (section % 4 == 0) appendLine("\n![Local raster sample](file://${image.absolutePath})\n")
            }
            appendLine("\n# END OF COMPLETE OUTPUT\n")
        }
        File(folder, "message.md").writeText(content)
        File(folder, "manifest.json").writeText(buildJsonObject {
            put("chars", content.length); put("utf8_bytes", content.toByteArray().size)
            put("whitespace_words", content.split(Regex("\\s+")).size)
            put("stream_chunk_chars", 1024); put("stream_interval_ms", 50)
            put("renderer_ids", JsonArray(RendererCatalog.entries.map { JsonPrimitive(it.id) }))
        }.toString())
        val assistant = Assistant(name = "Synthetic renderer stress", enableRollingContextCompression = false)
        val settings = Settings(assistants = listOf(assistant), assistantId = assistant.id,
            displaySetting = DisplaySetting(showLineNumbers = true, codeBlockAutoCollapse = false, codeBlockAutoWrap = false))
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val toaster = com.dokar.sonner.rememberToasterState()
                val navigator = remember { Navigator(mutableStateListOf(Screen.Log)) }
                val tts = me.rerere.rikkahub.ui.hooks.rememberCustomTtsState()
                CompositionLocalProvider(LocalSettings provides settings, LocalNavController provides navigator,
                    LocalToaster provides toaster, LocalTTSState provides tts, LocalDarkMode provides true) {
                    val scope = rememberCoroutineScope()
                    val list = rememberLazyListState(cacheWindow = LazyLayoutCacheWindow(aheadFraction = 1.5f, behindFraction = 2.5f))
                    val haze = dev.chrisbanes.haze.rememberHazeState()
                    var running by remember { mutableStateOf(false) }
                    var status by remember { mutableStateOf(if (focused == null) "READY" else "FOCUSED ${focused.id}") }
                    var conversation by remember { mutableStateOf(Conversation(assistantId = assistant.id,
                        messageNodes = listOf(UIMessage.user("Generate a mixed long output locally.").toMessageNode(), UIMessage.assistant(if (focused == null) "" else content).toMessageNode()))) }
                    fun publishState(phase: String, elapsed: Long = 0) {
                        File(folder, "state.json").writeText(buildJsonObject {
                            put("phase", phase); put("elapsed_ms", elapsed); put("messages", conversation.messageNodes.size)
                            put("copies", conversation.messageNodes.count { it.currentMessage.parts.filterIsInstance<UIMessagePart.Text>().any { part -> part.text == content } })
                        }.toString())
                    }
                    Surface(Modifier.fillMaxSize()) {
                        Column(Modifier.safeDrawingPadding()) {
                            Text("$status · ${content.length} chars · ${conversation.messageNodes.size} messages", Modifier.padding(4.dp))
                            Row {
                                TextButton(enabled = !running, modifier = Modifier.semantics { contentDescription = "stress_start" }, onClick = {
                                    scope.launch {
                                        running = true; status = "STREAMING"; publishState("STREAMING")
                                        val start = SystemClock.elapsedRealtime()
                                        for (end in 1024..content.length + 1023 step 1024) {
                                            val text = content.take(end.coerceAtMost(content.length))
                                            conversation = conversation.copy(messageNodes = conversation.messageNodes.dropLast(1) +
                                                conversation.messageNodes.last().copy(messages = listOf(UIMessage.assistant(text).copy(id = conversation.messageNodes.last().currentMessage.id))))
                                            delay(50)
                                        }
                                        running = false; status = "DONE"; publishState("DONE", SystemClock.elapsedRealtime()-start)
                                    }
                                }) { Text("Stream") }
                                TextButton(enabled = !running && status != "READY", modifier = Modifier.semantics { contentDescription = "stress_copy" }, onClick = {
                                    conversation = conversation.copy(messageNodes = conversation.messageNodes + List(4) { UIMessage.assistant(content).toMessageNode() })
                                    status = "COPIED"; publishState("COPIED")
                                }) { Text("Copy ×4") }
                                TextButton(modifier = Modifier.semantics { contentDescription = "stress_top" }, onClick = { scope.launch { list.scrollToItem(0) } }) { Text("Top") }
                                TextButton(modifier = Modifier.semantics { contentDescription = "stress_next" }, onClick = { scope.launch { list.scrollToItem((list.firstVisibleItemIndex+1).coerceAtMost(conversation.messageNodes.lastIndex)) } }) { Text("Next") }
                                TextButton(modifier = Modifier.semantics { contentDescription = "stress_bottom" }, onClick = { scope.launch { list.scrollToItem(conversation.messageNodes.size + 5) } }) { Text("Last") }
                            }
                            Box(Modifier.weight(1f).fillMaxWidth()) {
                                ChatList(PaddingValues(8.dp), conversation, list, running,
                                    previewMode = false, settings = settings, hazeState = haze)
                            }
                        }
                    }
                }
            }
        }
    }
}
