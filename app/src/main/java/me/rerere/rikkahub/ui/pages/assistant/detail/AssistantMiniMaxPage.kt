package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.ai.provider.*
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.ui.components.ai.ReasoningButton
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.FormItem
import me.rerere.rikkahub.ui.components.ui.LargeFlexibleTopAppBar
import me.rerere.rikkahub.ui.components.ui.Select
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun AssistantMiniMaxPage(id: String) {
    val vm: AssistantDetailVM = koinViewModel(parameters = { parametersOf(id) })
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val model = settings.providers.findModelById(assistant.chatModelId ?: settings.chatModelId)
    val provider = model?.findProvider(settings.providers)
    val nav = LocalNavController.current
    LaunchedEffect(settings.init, model) {
        if (!settings.init && model != null && model.inferParameterFamily() != ModelParameterFamily.MINIMAX) nav.popBackStack()
    }
    if (model?.inferParameterFamily() != ModelParameterFamily.MINIMAX) return
    val nativeControls = model.isMiniMaxM3() && (provider is ProviderSetting.Claude ||
        provider is ProviderSetting.OpenAI && !provider.useResponseApi)
    Scaffold(topBar = { LargeFlexibleTopAppBar(title = { Text("MiniMax 参数") }, navigationIcon = { BackButton() }, colors = CustomColors.topBarColors) },
        containerColor = CustomColors.scaffoldContainerColor) { padding ->
        AssistantMiniMaxContent(assistant, model, provider, nativeControls, padding, vm::update)
    }
}

@Composable
internal fun AssistantMiniMaxContent(assistant: Assistant, model: Model, provider: ProviderSetting?,
    nativeControls: Boolean, padding: PaddingValues, onUpdate: (Assistant) -> Unit) {
    val options = assistant.miniMaxOptions
    val flash = model.modernVendorModel() == ModernVendorModel.MINIMAX_M31
    val support = resolveReasoningLevelSupport(model, provider)
    fun update(value: MiniMaxGenerationOptions) = onUpdate(assistant.copy(miniMaxOptions = value))
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(colors = CustomColors.cardColorsOnSurfaceContainer) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(model.displayName.ifBlank { model.modelId }, style = MaterialTheme.typography.titleMedium)
                Text(if (nativeControls) "M3 系列使用固定采样：temperature=1、top_p=0.95。输出 Token 上限在基础设置中配置，包含思考消耗。"
                    else "当前模型或协议未声明 M3 专有参数支持。基础参数继续有效；不会发送下方专有字段。")
                if (flash) Text("M3.1 Flash 必须开启思考，支持 low / medium / high / xhigh / max，默认 max。")
            }
        }
        if (nativeControls) {
            if (flash) {
                FormItem(label = { Text("思考深度") }) {
                    ReasoningButton(reasoningLevel = support.coerce(assistant.reasoningLevel), availableLevels = support.levels,
                        onUpdateReasoningLevel = { onUpdate(assistant.copy(reasoningLevel = it)) })
                }
            } else {
                FormItem(label = { Text("思考模式") }, description = { Text("默认遵循基础设置；AUTO 时采用协议默认值。OpenAI 默认开启，Anthropic 默认关闭。") }) {
                    Select(MiniMaxThinking.entries, options.thinking, { update(options.copy(thinking = it)) })
                }
                FormItem(label = { Text("服务等级") }, description = { Text("priority 优先处理，官方价格为 standard 的 1.5 倍。") }) {
                    Select(MiniMaxServiceTier.entries, options.serviceTier, { update(options.copy(serviceTier = it)) })
                }
                if (provider is ProviderSetting.OpenAI) {
                    FormItem(label = { Text("思考内容格式") }) {
                        Select(listOf("默认", "分离 reasoning_content", "内联 think 标签"),
                            when (options.reasoningSplit) { true -> "分离 reasoning_content"; false -> "内联 think 标签"; null -> "默认" },
                            { update(options.copy(reasoningSplit = when (it) { "分离 reasoning_content" -> true; "内联 think 标签" -> false; else -> null })) })
                    }
                }
            }
        }
    }
}
