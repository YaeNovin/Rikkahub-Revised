package me.rerere.rikkahub

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.ui.pages.setting.MicaDemoSamples
import me.rerere.rikkahub.ui.pages.setting.MicaPreviewScene
import me.rerere.rikkahub.ui.theme.micaPalette

/** ADB-startable fallback for visual QA on devices whose instrumentation launcher is blocked.
 * It displays fixed samples only and does not read or write application settings. */
class MicaPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dark = intent.getBooleanExtra("dark", false)
        val background = intent.getBooleanExtra("background", false)
        setContent {
            val scheme = if (dark) darkColorScheme() else lightColorScheme()
            MaterialTheme(colorScheme = scheme) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("云母预览 · ${if (dark) "深色" else "浅色"}", style = MaterialTheme.typography.titleLarge)
                        Text(if (background) "示例背景取色" else "无背景 · 主题取色", style = MaterialTheme.typography.bodyMedium)
                        MicaPreviewScene(micaPalette(scheme, dark, if (background) MicaDemoSamples else emptyList()))
                    }
                }
            }
        }
    }
}
