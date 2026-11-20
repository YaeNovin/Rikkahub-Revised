package me.rerere.rikkahub

import android.os.Bundle
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.unit.dp
import com.dokar.sonner.rememberToasterState
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.components.richtext.MarkdownRenderMode
import me.rerere.rikkahub.ui.components.richtext.buildMarkdownPreviewHtml
import me.rerere.rikkahub.ui.components.webview.WEB_VIEW_BASE_URL
import me.rerere.rikkahub.ui.components.webview.WebView
import me.rerere.rikkahub.ui.components.webview.ChatWebPreviewHost
import me.rerere.rikkahub.ui.components.webview.LocalChatWebPreview
import me.rerere.rikkahub.ui.components.webview.rememberWebViewState
import me.rerere.rikkahub.ui.context.*
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import org.json.JSONArray
import kotlinx.coroutines.delay

/** Isolated Debug fixture. Samples remain in memory; no conversations or settings are written. */
class BaselineAlignmentActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val samples = runCatching {
            val encoded = requireNotNull(intent.getStringExtra("samples_base64"))
            val array = JSONArray(String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8))
            List(array.length()) { array.getString(it) }
        }.getOrDefault(emptyList())
        setContent {
            var sample by remember { mutableIntStateOf(0) }
            var mode by remember { mutableStateOf(MarkdownRenderMode.USER_MESSAGE) }
            var dark by remember { mutableStateOf(false) }
            var preview by remember { mutableStateOf(false) }
            var clicked by remember { mutableStateOf("") }
            var fullscreenContent by remember { mutableStateOf<String?>(null) }
            val handler = remember { object : UriHandler { override fun openUri(uri: String) { clicked = uri } } }
            val settings = remember {
                Settings(displaySetting = DisplaySetting(
                    showLineNumbers = false, codeBlockAutoCollapse = false, codeBlockAutoWrap = true,
                ))
            }
            val navigator = remember { Navigator(mutableListOf()) }
            val toaster = rememberToasterState()
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                CompositionLocalProvider(
                    LocalSettings provides settings,
                    LocalNavController provides navigator,
                    LocalToaster provides toaster,
                    LocalDarkMode provides dark,
                    LocalUriHandler provides handler,
                    LocalChatWebPreview provides { fullscreenContent = it },
                ) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
                            Text("P0 基线渲染验证", style = MaterialTheme.typography.titleLarge)
                            Text("样本 ${sample + 1}/${samples.size} · ${mode.name} · ${if (dark) "深色" else "浅色"}")
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Button(onClick = {
                                    mode = if (mode == MarkdownRenderMode.USER_MESSAGE) MarkdownRenderMode.STANDARD
                                        else MarkdownRenderMode.USER_MESSAGE
                                }, modifier = Modifier.weight(1f)) { Text("角色切换") }
                                Button(onClick = { dark = !dark }, modifier = Modifier.weight(1f)) { Text("主题切换") }
                                Button(onClick = { if (samples.isNotEmpty()) sample = (sample + 1) % samples.size },
                                    modifier = Modifier.weight(1f)) { Text("下一样本") }
                            }
                            Row {
                                TextButton(onClick = { preview = !preview }) { Text(if (preview) "Return to Compose" else "Open offline preview") }
                                if (clicked.isNotEmpty()) Text("OPENED $clicked")
                            }
                            HorizontalDivider(Modifier.padding(vertical = 12.dp))
                            if (preview) {
                                val colors = MaterialTheme.colorScheme
                                val html = remember(sample, colors) {
                                    buildMarkdownPreviewHtml(this@BaselineAlignmentActivity, samples[sample], colors)
                                }
                                val state = rememberWebViewState(data = html, baseUrl = WEB_VIEW_BASE_URL,
                                    mimeType = "text/html", encoding = "UTF-8")
                                var view by remember { mutableStateOf<android.webkit.WebView?>(null) }
                                var result by remember(html) { mutableStateOf("") }
                                LaunchedEffect(view, html) {
                                    repeat(25) {
                                        if (result.isNotBlank()) return@LaunchedEffect
                                        view?.evaluateJavascript("""(() => {
                                            if (window.__rikkaRenderStatus !== 'ready') return '';
                                            const count = s => document.querySelectorAll(s).length;
                                            const href = document.querySelector('a[href^="https://www."]')?.getAttribute('href');
                                            return ['WEB',count('input:disabled'),count('input:checked'),
                                              count('sup'),count('sub'),count('s'),count('.markdown-alert'),
                                              href || '',document.body.innerText.includes('[^note]: example'),
                                              count('a[href="example"]')].join(' ');
                                        })()""") { value -> if (value.startsWith("\"WEB ")) result = value.trim('"') }
                                        delay(400)
                                    }
                                }
                                if (result.isNotBlank()) Text(result)
                                WebView(state, Modifier.weight(1f).fillMaxWidth(), onCreated = { view = it })
                            } else Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                                samples.getOrNull(sample)?.let {
                                    MarkdownBlock(it, style = MaterialTheme.typography.bodyLarge, renderMode = mode)
                                } ?: Text("缺少验证样本")
                            }
                        }
                    }
                    ChatWebPreviewHost(fullscreenContent) { fullscreenContent = null }
                }
            }
        }
    }
}
