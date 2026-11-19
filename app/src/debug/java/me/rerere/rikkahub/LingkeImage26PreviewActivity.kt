package me.rerere.rikkahub

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.providers.openai.OpenAIProvider
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.ImageGenerationRequestState
import me.rerere.rikkahub.data.model.constrained
import me.rerere.rikkahub.ui.context.*
import me.rerere.rikkahub.ui.pages.imggen.ImageGenerationSettingsBottomSheet
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import okhttp3.OkHttpClient

/** Exercises the production settings sheet with fixed data and no generation calls. */
class LingkeImage26PreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val native = intent.getBooleanExtra("native", false)
        val setting = ProviderSetting.OpenAI(baseUrl = if (native) "https://native.test/v1" else "https://api.lk888.ai/v1")
        val model = Model(modelId = if (native) "gpt-image-2.6-flare" else "tt-image-2.6")
        val constraints = OpenAIProvider(OkHttpClient()).imageGenerationConstraints(setting, model)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val navigator = remember { Navigator(mutableListOf()) }
                val toaster = com.dokar.sonner.rememberToasterState()
                CompositionLocalProvider(LocalSettings provides Settings(), LocalDarkMode provides true,
                    LocalNavController provides navigator, LocalToaster provides toaster) {
                    var state by remember { mutableStateOf(ImageGenerationRequestState(outputFormat = "webp")) }
                    var visible by remember { mutableStateOf(true) }
                    val status = "version=${state.gptImage26Options.version ?: "default"}; compression=${state.outputCompression}"
                    Surface(Modifier.fillMaxSize()) {
                        Column(Modifier.safeDrawingPadding().padding(16.dp)) {
                            Text(status)
                            Button(onClick = { visible = true }) { Text("打开参数") }
                        }
                    }
                    if (visible) ImageGenerationSettingsBottomSheet(
                        state = state,
                        onStateChange = { state = it.constrained(constraints) },
                        constraints = constraints,
                        geminiModelId = null,
                        geminiRequestChannel = null,
                        referenceImageCount = 0,
                        sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden,
                            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)),
                        onDismiss = { visible = false },
                        headerContent = { Text(status) },
                    )
                }
            }
        }
    }
}
