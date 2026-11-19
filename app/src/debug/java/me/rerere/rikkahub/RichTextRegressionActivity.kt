package me.rerere.rikkahub

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.datastore.*
import me.rerere.rikkahub.ui.components.richtext.*
import me.rerere.rikkahub.ui.components.ui.AppearanceBackgroundSpec
import me.rerere.rikkahub.ui.components.ui.LocalAppearanceBackground
import me.rerere.rikkahub.ui.context.*
import me.rerere.rikkahub.ui.theme.*

/** Debug-only fixed data. No provider requests, conversation access or preference writes. */
class RichTextRegressionActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val amoled = intent.getBooleanExtra("amoled", false)
        val initialMode = TextColorMode.entries.firstOrNull { it.name == intent.getStringExtra("mode") }
            ?: TextColorMode.AUTO_CLEAR
        val ansi = "\u001b[32mSUCCESS\u001b[0m 深色终端 中文 🙂\n" +
            "\u001b[1;31mERROR\u001b[0m \u001b[38;5;208m256 COLOR\u001b[0m \u001b[38;2;121;192;255mRGB\u001b[0m\n" +
            (1..70).joinToString("\n") { "line $it | " + "terminal column ".repeat(10) }
        val messages = (1..20).map { index ->
            "### 消息 $index · 边界回归\n\n" +
                "来回滑动这组固定消息，检查正文、表格与代码在消息边界处的呈现。".repeat(6) +
                "\n\n```kotlin\n// message $index\ndata class Star(val count: Int)\nfun draw() = listOf(1, 2, 3).map { it * 42 }\n```\n\n" +
                "| 列 | 状态 |\n| --- | --- |\n" + (1..8).joinToString("\n") { "| $it | **可读** / `value_$it` |" }
        }
        val settings = Settings(displaySetting = DisplaySetting(showLineNumbers = true,
            codeBlockAutoCollapse = false, codeBlockAutoWrap = false))
        setContent {
            var mode by remember { mutableStateOf(initialMode) }
            val background = if (amoled) Color.Black else Color(0xFF121316)
            val scheme = darkColorScheme(background = background, surface = background)
            val seed = textPaletteSeed(mode, 0xFF7755CC.toInt(), 0xFF00AA88.toInt())
            val foreground = backgroundTextForeground(mode, listOf(background), seed, scheme.onSurface, true)
            MaterialTheme(colorScheme = scheme) {
                val navigator = remember { Navigator(mutableListOf()) }
                val toaster = com.dokar.sonner.rememberToasterState()
                CompositionLocalProvider(LocalSettings provides settings, LocalDarkMode provides true,
                    LocalContentColor provides scheme.onSurface,
                    LocalTextColorMode provides mode, LocalWallpaperTextSeed provides WallpaperTextSeed(0xFF00AA88.toInt(), "fixture"),
                    LocalBaseThemeColorScheme provides scheme, LocalNavController provides navigator, LocalToaster provides toaster,
                    LocalGlobalBackgroundActive provides true,
                    LocalTextSurfaceBackgrounds provides listOf(background),
                    LocalAppearanceBackground provides AppearanceBackgroundSpec(null, 1f, 0f, useGradientBackground = true,
                        foreground = foreground, readability = BackgroundReadability(foreground, listOf(background), seedArgb = 0xFF7755CC.toInt()))) {
                    BackgroundReadabilityTheme(true, foreground) {
                        LaunchedEffect(Unit) { preloadMarkdownContents(messages, maxTotalChars = 256 * 1024) }
                        Column(Modifier.fillMaxSize().background(background).safeDrawingPadding().padding(12.dp)) {
                            Text("${if (amoled) "AMOLED" else "深色"} · 配色与消息边界", style = MaterialTheme.typography.titleMedium)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextColorMode.entries.forEach { value ->
                                    FilterChip(selected = mode == value, onClick = { mode = value },
                                        label = { Text(value.name) })
                                }
                            }
                            Text("中文正文 / English 123 · 颜色应随模式切换", Modifier.semantics {
                                contentDescription = "palette:${mode.name}:${foreground.toArgb().toUInt().toString(16)}"
                            })
                            LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                item(key = "ansi") { HighlightCodeBlock(ansi, "ansi") }
                                itemsIndexed(messages, key = { index, _ -> index }) { _, content ->
                                    MarkdownBlock(content, style = MaterialTheme.typography.bodyLarge.copy(color = foreground))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
