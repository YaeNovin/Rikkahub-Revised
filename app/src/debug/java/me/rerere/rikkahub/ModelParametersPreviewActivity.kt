package me.rerere.rikkahub

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import me.rerere.ai.provider.*
import me.rerere.ai.provider.providers.google.isGeminiHydrogen
import me.rerere.ai.provider.providers.google.gemini4DefaultThinking
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.ui.context.*
import me.rerere.rikkahub.ui.pages.assistant.detail.AssistantGeminiContent
import me.rerere.rikkahub.ui.pages.assistant.detail.AssistantMiniMaxContent
import kotlinx.serialization.json.Json

/** Synthetic parameter form: records only public test options, never user settings. */
class ModelParametersPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val model = Model(modelId = intent.getStringExtra("model") ?: "MiniMax-M3")
        val provider: ProviderSetting = if (model.inferParameterFamily() == ModelParameterFamily.GEMINI)
            ProviderSetting.Google(models = listOf(model)) else ProviderSetting.OpenAI(models = listOf(model))
        setContent {
            var assistant by remember { mutableStateOf(Assistant(chatModelId = model.id)) }
            val settings = Settings(providers = listOf(provider), assistants = listOf(assistant), assistantId = assistant.id)
            MaterialTheme(colorScheme = darkColorScheme()) {
                val nav = remember { Navigator(mutableStateListOf(Screen.Log)) }
                val toaster = com.dokar.sonner.rememberToasterState()
                CompositionLocalProvider(LocalSettings provides settings, LocalNavController provides nav, LocalToaster provides toaster) {
                    val update: (Assistant) -> Unit = { value ->
                        assistant = value
                        filesDir.resolve("parameter-preview.json").writeText(Json.encodeToString(value))
                    }
                    if (model.inferParameterFamily() == ModelParameterFamily.GEMINI) AssistantGeminiContent(
                        PaddingValues(top = 32.dp), assistant, true, true, true, false, false, null, null, update,
                        isArgon = true, hydrogen = model.isGeminiHydrogen(), defaultThinking = model.gemini4DefaultThinking())
                    else AssistantMiniMaxContent(assistant, model, provider, true, PaddingValues(top = 32.dp), update)
                }
            }
        }
    }
}
