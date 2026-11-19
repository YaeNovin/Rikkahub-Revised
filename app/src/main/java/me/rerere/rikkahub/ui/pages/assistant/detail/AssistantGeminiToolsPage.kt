package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.providers.google.isGeminiModel
import me.rerere.ai.provider.providers.google.resolvedFor
import me.rerere.ai.provider.providers.google.supportsGoogleImageSearch
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.LargeFlexibleTopAppBar
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

internal fun showGeminiToolsSettings(model: Model?): Boolean = model?.isGeminiModel() == true

@Composable
fun AssistantGeminiToolsPage(id: String) {
    val vm: AssistantDetailVM = koinViewModel(parameters = { parametersOf(id) })
    // Resolve assistant, model and provider from one snapshot during a model switch.
    val settings by vm.settings.collectAsStateWithLifecycle()
    val assistant = settings.assistants.find { it.id.toString() == id }
    val model = settings.findModelById(assistant?.chatModelId ?: settings.chatModelId)
    val visible = assistant != null && showGeminiToolsSettings(model)
    val nav = LocalNavController.current
    LaunchedEffect(settings.init, visible) {
        if (!settings.init && !visible) nav.popBackStack()
    }
    if (!visible || assistant == null || model == null) return
    val provider = model.findProvider(settings.providers) as? ProviderSetting.Google
    val native = provider != null
    Scaffold(
        topBar = { LargeFlexibleTopAppBar(title = { Text("Gemini 内置工具") }, navigationIcon = { BackButton() }, colors = CustomColors.topBarColors) },
        containerColor = CustomColors.scaffoldContainerColor,
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp).imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("仅此助手生效；聊天中的搜索按钮使用同一设置。旧版本模型开关会自动保留为初始值。")
            Card(colors = CustomColors.cardColorsOnSurfaceContainer) {
                GeminiSearchSettings(
                    options = assistant.geminiOptions.search,
                    nativeProtocol = native,
                    supportsImageSearch = model.supportsGoogleImageSearch(),
                    legacySearchEnabled = BuiltInTools.Search in model.tools,
                    legacyUrlContextEnabled = BuiltInTools.UrlContext in model.tools,
                    interactions = provider?.useInteractionsApi == true && !provider.vertexAI,
                ) { value ->
                    vm.update(assistant.copy(geminiOptions = assistant.geminiOptions.copy(search = value.resolvedFor(model))))
                }
            }
        }
    }
}
