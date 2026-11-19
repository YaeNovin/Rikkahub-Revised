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
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.datastore.RichContentStyle
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.ui.components.richtext.HighlightCodeBlock
import me.rerere.rikkahub.ui.components.richtext.LocalTextSurfaceBackgrounds
import me.rerere.rikkahub.ui.context.*
import me.rerere.rikkahub.ui.theme.LocalDarkMode

/** Fixed samples only; never reads/writes the user's settings or conversations.
 * Exercise the real HighlightCodeBlock with opposing app/background brightness. */
class HighlightPalettePreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dark = intent.getBooleanExtra("dark", false)
        val darkBackground = intent.getBooleanExtra("darkBackground", true)
        val wrapped = intent.getBooleanExtra("wrapped", true)
        val longSource = intent.getBooleanExtra("longSource", false)
        val python = intent.getBooleanExtra("python", false)
        val language = if (longSource) "json" else if (python) "python" else "kotlin"
        val code = when {
            longSource -> "{\n  \"title\": \"Highlighter long source\",\n  \"enabled\": true,\n  \"items\": [\n" +
                List(180) { "    {\"name\":\"star\",\"count\":42,\"active\":true}" }.joinToString(",\n") + "\n  ]\n}"
            python -> "# 注释应可读，关键字、字符串和数字保留原色\nclass Star:\n    def draw(self, name):\n        color = \"violet\"\n        count = 42\n        return count > 0\n\n# one more comment"
            else -> "/* 多行注释\n * val fake = true\n */\ndata class Star(val count: Int)\nfun draw(name: String): Boolean {\n    val color = \"violet\"\n    val count = 42\n    return count > 0\n}\n// 原始 Highlight 配色，注释独立提亮"
        }
        val original = Settings(displaySetting = DisplaySetting(showLineNumbers = true,
            codeBlockAutoWrap = wrapped, codeBlockAutoCollapse = false))
        val settings = original.copy(advancedAppearanceSetting = original.advancedAppearanceSetting.copy(
            richContentStyle = RichContentStyle.OUTLINED))
        val background = if (darkBackground) Color(0xFF101218) else Color(0xFFF9F9FF)
        val foreground = if (darkBackground) Color.White else Color.Black
        setContent {
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                val navigator = remember { Navigator(mutableListOf()) }
                val toaster = com.dokar.sonner.rememberToasterState()
                CompositionLocalProvider(
                    LocalSettings provides settings, LocalDarkMode provides dark,
                    LocalToaster provides toaster, LocalNavController provides navigator,
                    LocalTextSurfaceBackgrounds provides listOf(background),
                    LocalGlobalBackgroundActive provides true, LocalContentColor provides foreground,
                ) {
                    Column(Modifier.fillMaxSize().background(background).safeDrawingPadding()
                        .verticalScroll(rememberScrollState()).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("Highlight · ${if (dark) "深色主题" else "浅色主题"} / ${if (darkBackground) "深色背景" else "浅色背景"}")
                        Text("$language · ${code.length} 字符 · ${if (wrapped) "行号 + 换行" else "不换行"}")
                        HighlightCodeBlock(code, language, renderVisuals = false)
                    }
                }
            }
        }
    }
}
