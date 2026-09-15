package me.rerere.rikkahub.ui.pages.extensions

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.data.datastore.ExtensionManagementMode
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.data.model.evaluateKeywords
import me.rerere.rikkahub.data.model.isTriggered
import me.rerere.rikkahub.data.model.KeywordExpressionResult
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.LargeFlexibleTopAppBar
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel

/** Item identity is carried by navigation, so search never relies on an ambiguous title. */
@Composable
fun ExtensionItemPage(kind: String, id: String) {
    val vm = koinViewModel<PromptVM>()
    val quickVM = koinViewModel<QuickMessagesVM>()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val entertainment = settings.extensionManagementMode == ExtensionManagementMode.ENTERTAINMENT
    val rawId = id.substringAfter(':')
    val quick = settings.quickMessages.find { it.id.toString() == rawId && kind == "QUICK_MESSAGE" }
    val mode = settings.modeInjections.find { it.id.toString() == rawId && kind == "MODE_INJECTION" }
    val book = settings.lorebooks.find { it.id.toString() == rawId && kind == "LOREBOOK" }
    val title = quick?.title ?: mode?.name ?: book?.name ?: "扩展已不存在"
    val users = settings.assistants.filter { assistant ->
        when {
            quick != null -> quick.id in assistant.quickMessageIds
            mode != null -> mode.id in assistant.modeInjectionIds
            book != null -> book.id in assistant.lorebookIds
            else -> false
        }
    }
    var editing by rememberSaveable(id) { mutableStateOf(false) }
    val nav = me.rerere.rikkahub.ui.context.LocalNavController.current
    var modeDraft by remember(id) { mutableStateOf<PromptInjection.ModeInjection?>(null) }
    var editBase by remember(id) { mutableStateOf<Settings?>(null) }
    Scaffold(
        topBar = { LargeFlexibleTopAppBar(title = { Text(title) }, navigationIcon = { BackButton() }, colors = CustomColors.topBarColors) },
        containerColor = CustomColors.scaffoldContainerColor,
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text(quick?.content ?: mode?.content ?: book?.description.orEmpty())
                TextButton(enabled = quick != null || mode != null || book != null, onClick = {
                    if (book != null) vm.openLorebook(book) else { editBase = settings; modeDraft = mode; editing = true }
                }) { Text("编辑") }
            }
            item { Text("助手默认引用（${users.size}）", style = MaterialTheme.typography.titleMedium) }
            if (users.isEmpty()) item { Text("暂无助手默认引用；此处不统计对话临时选择。") }
            items(users, key = { it.id.toString() }) { assistant ->
                CardGroup { item(headlineContent = { Text(assistant.name.ifBlank { "未命名助手" }) }) }
            }
            if (book != null) {
                item { TextButton(onClick = { nav.navigate(me.rerere.rikkahub.Screen.LorebookHelp) }) { Text("世界书帮助与教程") } }
                item { LorebookBatchPanel(book, settings.lorebooks, vm) }
                item { LorebookSimulator(book, settings.lorebooks, entertainment, settings.lorebookTotalTokenBudget) }
            }
        }
    }
    if (editing) {
        when {
            quick != null -> EditQuickMessageDialog("编辑快捷消息", quick, entertainment, { editing = false }) {
                quickVM.updateQuickMessage(it); editing = false
            }
            modeDraft != null -> ModeInjectionEditSheet(modeDraft!!, entertainment, { editing = false }, {
                editBase?.let { base -> vm.updateSettings(base, base.copy(modeInjections = base.modeInjections.map { if (it.id == modeDraft!!.id) modeDraft!! else it })) }
                editing = false
            }, { modeDraft = it })
        }
    }
    LorebookEditorHost(vm, entertainment)
}
