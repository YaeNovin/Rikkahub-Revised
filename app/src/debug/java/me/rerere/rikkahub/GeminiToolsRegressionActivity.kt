package me.rerere.rikkahub

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.rerere.ai.provider.*
import me.rerere.rikkahub.data.datastore.*
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.ui.context.*
import me.rerere.rikkahub.ui.pages.assistant.detail.*
import me.rerere.rikkahub.ui.pages.setting.ModelSettingsForm
import me.rerere.rikkahub.ui.pages.setting.VolcengineSearchOptionsEditor
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import me.rerere.search.SearchServiceOptions
import org.koin.android.ext.android.get

/** Production settings/navigation components with local, non-networked configurations. */
class GeminiToolsRegressionActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        check(BuildConfig.APPLICATION_ID.endsWith(".revised.appearanceqa"))
        val store = get<SettingsStore>()
        val models = listOf("gemini-4-argon", "gemini-4-carbon", "gpt-6.1-sol", "gemma-4-31b-it").map { id ->
            Model(modelId = id, displayName = id, abilities = listOf(ModelAbility.TOOL),
                tools = if (id.startsWith("gemini")) setOf(BuiltInTools.Search, BuiltInTools.UrlContext) else emptySet())
        }
        val assistant = Assistant(name = "Gemini 搜索配置回归", chatModelId = models[0].id)
        var ready by mutableStateOf(false)
        lifecycleScope.launch {
            store.settingsFlow.first { !it.init }
            store.update { it.copy(assistants = listOf(assistant), assistantId = assistant.id, chatModelId = models[0].id,
                providers = listOf(ProviderSetting.Google(name = "Local Google fixture", models = models)),
                enableTitleGeneration = false, enableSuggestion = false, enableGenerationRetry = false) }
            ready = true
        }
        setContent {
            val settings by store.settingsFlow.collectAsState()
            var tab by remember { mutableStateOf("助手") }
            var dark by remember { mutableStateOf(true) }
            var volc by remember { mutableStateOf(SearchServiceOptions.VolcengineOptions()) }
            val scope = rememberCoroutineScope()
            val backStack = rememberNavBackStack(Screen.AssistantDetail(assistant.id.toString()))
            val nav = remember { Navigator(backStack) }
            val currentAssistant = settings.assistants.find { it.id == assistant.id } ?: assistant
            val currentModel = settings.findModelById(currentAssistant.chatModelId) ?: models[0]
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                val toaster = com.dokar.sonner.rememberToasterState()
                SharedTransitionLayout {
                    CompositionLocalProvider(LocalSettings provides settings, LocalDarkMode provides dark,
                        LocalNavController provides nav, LocalSharedTransitionScope provides this, LocalToaster provides toaster) {
                        Surface(Modifier.fillMaxSize()) {
                            Column(Modifier.safeDrawingPadding()) {
                                Text(if (ready) "READY ${currentModel.modelId}" else "初始化")
                                Row(Modifier.horizontalScroll(rememberScrollState())) {
                                    listOf("Argon", "Carbon", "GPT", "Gemma").forEachIndexed { index, label ->
                                        TextButton(enabled = ready, onClick = { scope.launch { store.updateAssistantModel(assistant.id, models[index].id) } }) { Text(label) }
                                    }
                                }
                                Row {
                                    listOf("助手", "模型", "豆包Key").forEach { label -> TextButton(onClick = { tab = label }) { Text(label) } }
                                    TextButton(onClick = { dark = !dark }) { Text("明暗") }
                                }
                                Text("本助手搜索=${settings.isBuiltInSearchEnabled(currentModel)}")
                                Box(Modifier.weight(1f)) {
                                    when (tab) {
                                        "助手" -> NavDisplay(backStack = backStack,
                                            entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator(), rememberViewModelStoreNavEntryDecorator()),
                                            onBack = { nav.popBackStack() }, entryProvider = entryProvider {
                                                entry<Screen.AssistantDetail> { AssistantDetailPage(it.id) }
                                                entry<Screen.AssistantGeminiTools> { AssistantGeminiToolsPage(it.id) }
                                                entry<Screen.AssistantGemini> { AssistantGeminiPage(it.id) }
                                            })
                                        "模型" -> ModelSettingsForm(currentModel, {}, true, settings.providers.first())
                                        "豆包Key" -> Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp)) {
                                            VolcengineSearchOptionsEditor(volc) { volc = it }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
