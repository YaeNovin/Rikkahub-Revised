package me.rerere.rikkahub

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.rerere.ai.provider.*
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.ui.components.ai.ModelTypeTag
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import me.rerere.rikkahub.ui.pages.setting.PromptSettingsPage
import me.rerere.rikkahub.ui.pages.setting.SettingVM
import me.rerere.rikkahub.utils.applyPlaceholders
import me.rerere.rikkahub.utils.containsPromptVariable
import org.koin.androidx.compose.koinViewModel

/** Fixed local configuration; only included in the isolated debug APK. */
class ModelApiRegressionActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        for (key in listOf("content", "source_text", "target_lang", "a.b[0]")) {
            check("{{ $key }}".containsPromptVariable(key))
            check("{$key}".containsPromptVariable(key))
            check("{{ $key }}".applyPlaceholders(key to "\\$1") == "\\$1")
        }
        Log.i("ModelApiRegression", "Android ICU prompt checks: PASS")
        val decision = Model(modelId = "decision-model-preview").withDetectedCapabilities()
        check(decision.type == ModelType.DECISION)
        val settings = Settings()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                CompositionLocalProvider(LocalSettings provides settings, LocalDarkMode provides true) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.safeDrawingPadding()) {
                        Text("Android ICU prompt checks: PASS", Modifier.padding(12.dp))
                        ModelTypeTag(decision)
                        Box(Modifier.weight(1f)) {
                            PromptSettingsPage(settings, koinViewModel<SettingVM>(), PaddingValues(12.dp))
                        }
                    }
                }
                }
            }
        }
    }
}
