package me.rerere.rikkahub

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.ui.pages.setting.CustomSearchOptionsEditor
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import me.rerere.search.*

/** Real editor with fixed local configuration, no network/test button and no user settings. */
class CustomSearchPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val preset = intent.getBooleanExtra("x", false)
        val dark = intent.getBooleanExtra("dark", false)
        setContent {
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                CompositionLocalProvider(LocalDarkMode provides dark) {
                    var options by remember { mutableStateOf(if (preset) SearchServiceOptions.CustomOptions(
                        baseUrl = "https://search.example", kind = CustomSearchKind.X, endpoint = "/v1/x_search",
                        maxResults = OptionalSearchValue(true, "5"), allowedXHandles = OptionalSearchValue(true, "@alpha, beta"),
                        fromDate = OptionalSearchValue(true, "2026-10-01"),
                        extraParameters = listOf(CustomSearchParameter(enabled = true, name = "language", value = "zh"))
                    ) else SearchServiceOptions.CustomOptions()) }
                    Surface(Modifier.fillMaxSize()) {
                        Column(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("自定义搜索服务 · 固定配置验证", style = MaterialTheme.typography.titleMedium)
                            CustomSearchOptionsEditor(options) { options = it }
                        }
                    }
                }
            }
        }
    }
}
