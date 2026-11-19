package me.rerere.rikkahub

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.ui.components.richtext.*
import me.rerere.rikkahub.ui.context.*
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import java.io.File
import java.util.UUID

/** Synthetic source data only. Runs production Markdown components, with no
 * provider calls, stored conversations, settings writes or external browsing. */
class CitationRegressionActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val folder = File(filesDir, "citation-regression").apply { mkdirs() }
        val token = UUID.randomUUID().toString().take(8)
        val source = """
            ## SCREENSHOT_CASE · $token

            - 场景：桌面 Agent 完成多步任务[citation:9d7feb,54d9dd]。
            - 价值：在独立环境中完成工作，保留“会话隔离”与 **强调文字**[citation:fae5f3,9d7feb]。

            **HTML_ROUTE**<br>段落也应有引用[citation:fae5f3,54d9dd]。

            [**加粗链接** 与 *斜体* 及 `inline`](https://example.org/document)

            | 项目 | 结果 |
            |---|---|
            | 表格引用 | 可验证[citation:table01] |
            | 格式 | **粗体** / ~~删除线~~ |

            [**参考链接**][docs]

            [docs]: https://example.org/reference

            转义：\*literal\* &amp; &lt;safe&gt;，颜色 #FF8800。

            `a  b &amp; c`

            ```text
            LITERAL_CHECK [citation:fae5f3,9d7feb] "code" #FF8800
            ```

        """.trimIndent() + "\n\n" + List(6) {
            "随机上下文 $it · ${UUID.randomUUID().toString().take(8)} · 正常阅读上下文。"
        }.joinToString("\n\n")
        val nativeSource = source.replace("**HTML_ROUTE**<br>", "**NATIVE_ROUTE** ")
            .replace("[**参考链接**][docs]\n\n[docs]: https://example.org/reference", "[**普通链接**](https://example.org/reference)")
        File(folder, "source.md").writeText(source)
        val urls = mapOf("fae5f3" to "https://example.org/source-a", "9d7feb" to "https://example.org/source-b",
            "54d9dd" to "https://example.org/source-c", "table01" to "https://example.org/source-table")
        setContent {
            var dark by remember { mutableStateOf(true) }
            var html by remember { mutableStateOf(false) }
            var streaming by remember { mutableStateOf(false) }
            var content by remember { mutableStateOf(nativeSource) }
            var clicked by remember { mutableStateOf("") }
            var held by remember { mutableStateOf("") }
            val scope = rememberCoroutineScope()
            val navigator = remember { Navigator(mutableStateListOf()) }
            val toaster = com.dokar.sonner.rememberToasterState()
            LaunchedEffect(token, dark, html, streaming, content, clicked, held) {
                File(folder, "state.json").writeText(buildJsonObject {
                    put("token", token); put("dark", dark); put("html", html); put("streaming", streaming)
                    put("chars", content.length); put("complete_chars", if (html) source.length else nativeSource.length)
                    put("clicked_id", clicked); put("clicked_url", urls[clicked].orEmpty()); put("held_id", held)
                }.toString())
            }
            MaterialTheme(colorScheme = if (dark) darkColorScheme(primary = Color(0xFFFFB3D0)) else lightColorScheme()) {
                val backdrop = if (dark) listOf(Color(0xFF211822), Color(0xFF35282A)) else listOf(Color(0xFFF9F2F5), Color(0xFFEEE4EA))
                CompositionLocalProvider(LocalSettings provides Settings(), LocalDarkMode provides dark,
                    LocalContentColor provides MaterialTheme.colorScheme.onSurface,
                    LocalNavController provides navigator, LocalToaster provides toaster,
                    LocalRichTextStreaming provides streaming,
                    LocalTextSurfaceBackgrounds provides backdrop,
                    LocalCitationLinkAction provides { held = it }) {
                    Column(Modifier.fillMaxSize().background(Brush.verticalGradient(backdrop)).safeDrawingPadding().padding(12.dp)) {
                        Text("Citation regression · $token", style = MaterialTheme.typography.titleMedium)
                        Row {
                            TextButton(onClick = { dark = !dark }, modifier = Modifier.semantics { contentDescription = "citation_theme" }) { Text(if (dark) "Dark" else "Light") }
                            TextButton(enabled = !streaming, onClick = { html = !html; content = if (html) source else nativeSource }, modifier = Modifier.semantics { contentDescription = "citation_renderer" }) { Text(if (html) "HTML" else "Native") }
                            TextButton(enabled = !streaming, modifier = Modifier.semantics { contentDescription = "citation_stream" }, onClick = {
                                scope.launch {
                                    val full = if (html) source else nativeSource
                                    streaming = true; content = ""
                                    for (end in 32..full.length + 31 step 32) { content = full.take(end); delay(35) }
                                    content = full; streaming = false
                                }
                            }) { Text("Stream") }
                        }
                        Text("CLICK:$clicked HOLD:$held", Modifier.semantics { contentDescription = "citation_status" }, style = MaterialTheme.typography.labelSmall)
                        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                            val style = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface)
                            if (html) MarkdownNew(content, style = style, onClickCitation = { clicked = it })
                            else MarkdownBlock(content, style = style, onClickCitation = { clicked = it })
                        }
                    }
                }
            }
        }
    }
}
