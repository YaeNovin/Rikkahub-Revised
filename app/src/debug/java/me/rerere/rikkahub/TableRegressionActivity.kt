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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
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

/** Synthetic Markdown only; leaves providers, conversations and preferences alone. */
class TableRegressionActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val folder = File(filesDir, "table-regression").apply { mkdirs() }
        val token = UUID.randomUUID().toString().take(8)
        val alignment = """
            | LEFT | CENTER | RIGHT |
            | :--- | :---: | ---: |
            | L | C | R |
            | **Bold** | *Italic* | `code` |
            | #FF8800 | “强调文字” | 12345 |
        """.trimIndent()
        val wide = """
            | COL_A | COL_B | COL_C | COL_D | COL_E | COL_F |
            | :--- | :---: | ---: | :--- | :---: | ---: |
            | ROW_START | ${"长内容换行验证 context $token ".repeat(8)} | `a  b` | **bold** | a\|b | LAST_SOURCE[citation:table_source] |
            | ROW_END | END_B | END_C | END_D | END_E | END_F |
        """.trimIndent()
        File(folder, "alignment.md").writeText(alignment)
        File(folder, "wide.md").writeText(wide)
        setContent {
            var dark by remember { mutableStateOf(true) }
            var html by remember { mutableStateOf(false) }
            var large by remember { mutableStateOf(false) }
            var wideCase by remember { mutableStateOf(false) }
            var streaming by remember { mutableStateOf(false) }
            var prefix by remember { mutableIntStateOf(Int.MAX_VALUE) }
            var clicked by remember { mutableStateOf("") }
            val full = if (wideCase) wide else alignment
            val content = full.take(prefix)
            val scope = rememberCoroutineScope()
            val navigator = remember { Navigator(mutableStateListOf()) }
            val toaster = com.dokar.sonner.rememberToasterState()
            LaunchedEffect(token, dark, html, large, wideCase, streaming, content, clicked) {
                File(folder, "state.json").writeText(buildJsonObject {
                    put("token", token); put("dark", dark); put("html", html); put("large", large)
                    put("wide", wideCase); put("streaming", streaming); put("chars", content.length)
                    put("complete_chars", full.length); put("clicked", clicked)
                }.toString())
            }
            MaterialTheme(colorScheme = if (dark) darkColorScheme(primary = Color(0xFFFFB3D0)) else lightColorScheme()) {
                val backdrop = if (dark) listOf(Color(0xFF211822), Color(0xFF35282A)) else listOf(Color(0xFFF9F2F5), Color(0xFFEEE4EA))
                val density = LocalDensity.current
                CompositionLocalProvider(LocalSettings provides Settings(), LocalDarkMode provides dark,
                    LocalContentColor provides MaterialTheme.colorScheme.onSurface,
                    LocalNavController provides navigator, LocalToaster provides toaster,
                    LocalRichTextStreaming provides streaming, LocalTextSurfaceBackgrounds provides backdrop,
                    LocalDensity provides Density(density.density, if (large) 1.3f else 1f)) {
                    Column(Modifier.fillMaxSize().background(Brush.verticalGradient(backdrop)).safeDrawingPadding().padding(12.dp)) {
                        Text("Table regression · $token", style = MaterialTheme.typography.titleSmall)
                        Row {
                            TextButton(onClick = { dark = !dark }, modifier = Modifier.semantics { contentDescription = "table_theme" }) { Text(if (dark) "Dark" else "Light") }
                            TextButton(enabled = !streaming, modifier = Modifier.semantics { contentDescription = "table_renderer" }, onClick = { html = !html }) { Text(if (html) "HTML" else "Native") }
                            TextButton(enabled = !streaming, modifier = Modifier.semantics { contentDescription = "table_case" }, onClick = { wideCase = !wideCase; prefix = Int.MAX_VALUE }) { Text(if (wideCase) "Wide" else "Align") }
                        }
                        Row {
                            TextButton(onClick = { large = !large }, modifier = Modifier.semantics { contentDescription = "table_scale" }) { Text(if (large) "130%" else "100%") }
                            TextButton(enabled = !streaming, modifier = Modifier.semantics { contentDescription = "table_stream" }, onClick = {
                                scope.launch {
                                    streaming = true
                                    for (end in 1..full.length + 23 step 24) { prefix = end; delay(45) }
                                    prefix = Int.MAX_VALUE; streaming = false
                                }
                            }) { Text("Stream") }
                        }
                        Text("CLICK:$clicked", style = MaterialTheme.typography.labelSmall)
                        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                            val style = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface)
                            if (html) MarkdownNew(content, style = style, onClickCitation = { clicked = it })
                            else MarkdownBlock(content, style = style, onClickCitation = { clicked = it })
                            repeat(10) { Text("上下文 $it · $token · 快速翻阅不会改变表格内容。", Modifier.padding(vertical = 18.dp)) }
                        }
                    }
                }
            }
        }
    }
}
