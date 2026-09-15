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
import me.rerere.rikkahub.data.model.*
import me.rerere.ai.provider.ModelType
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.AppearanceAlertDialog
import me.rerere.rikkahub.ui.components.ui.LargeFlexibleTopAppBar
import me.rerere.rikkahub.ui.components.ui.Select
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel
import kotlin.uuid.Uuid

@Composable
fun LorebookSourcesPage(vm: PromptVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val sources = settings.lorebookSources
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var assistantId by rememberSaveable { mutableStateOf<String?>(null) }
    val assistant = settings.assistants.firstOrNull { it.id.toString() == assistantId } ?: settings.assistants.firstOrNull { it.id == settings.assistantId }
    val persona = sources.personas.firstOrNull { it.id == sources.activePersonaId }
    var editPersona by remember { mutableStateOf<LorebookPersona?>(null) }
    var deletePersona by remember { mutableStateOf<LorebookPersona?>(null) }
    val books = remember(settings.lorebooks, query) { settings.lorebooks.filter { query.isBlank() || (it.name + it.description).contains(query.trim(), true) } }
    Scaffold(topBar = { LargeFlexibleTopAppBar(title = { Text("世界书来源与绑定") }, navigationIcon = { BackButton() }, colors = CustomColors.topBarColors) }, containerColor = CustomColors.scaffoldContainerColor) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            me.rerere.rikkahub.ui.components.ui.AppearancePrimaryTabRow(tab) { listOf("全局", "Persona", "助手").forEachIndexed { index, label -> Tab(tab == index, { tab = index }, text = { Text(label) }) } }
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    LorebookSectionTitle("显式绑定，按来源合并", "默认没有全局或 Persona 绑定。导入文件上的来源标签不会自动启用；此页修改直接保存，失败会提示。对话中明确停用的世界书优先排除。")
                    Select(LorebookInsertionStrategy.entries, sources.insertionStrategy, onOptionSelected = { strategy -> vm.updateLorebookSources { it.copy(insertionStrategy = strategy) } }, optionToString = { when (it) {
                        LorebookInsertionStrategy.LEGACY -> "保留历史插入顺序"
                        LorebookInsertionStrategy.SORTED_EVENLY -> "角色与全局按顺序混排"
                        LorebookInsertionStrategy.CHARACTER_FIRST -> "角色在前，全局在后"
                        LorebookInsertionStrategy.GLOBAL_FIRST -> "全局在前，角色在后"
                    } })
                    LorebookHint("非历史策略先排列 Chat、再 Persona，最后排列角色与全局；同一本书只注入一次。跨书预算仍按本地书序分配，不改变单书预算。")
                }
                when (tab) {
                    0 -> {
                        item { LorebookHint("全局绑定在允许继承的助手中生效。关闭某个助手的全局继承，不影响其他助手。") }
                        item {
                            LorebookToggle("世界书向量语义匹配", sources.vectorEnabled, "开启后会把近期对话文本和已绑定、标记为向量触发的条目发送到所选向量供应商，可能收费。默认关闭，失败回退关键词。") { enabled -> vm.updateLorebookSources { it.copy(vectorEnabled = enabled) } }
                            if (sources.vectorEnabled) {
                                val models = settings.providers.filter { it.enabled }.flatMap { it.models }.filter { it.type == ModelType.EMBEDDING }
                                Select(listOf<Uuid?>(null) + models.map { it.id }, sources.embeddingModelId, onOptionSelected = { id -> vm.updateLorebookSources { it.copy(embeddingModelId = id) } }, optionToString = { id -> models.firstOrNull { it.id == id }?.modelId ?: "请选择向量模型" })
                                var queryMessages by remember(sources.vectorQueryMessages) { mutableStateOf(sources.vectorQueryMessages.toString()) }
                                var maxEntries by remember(sources.vectorMaxEntries) { mutableStateOf(sources.vectorMaxEntries.toString()) }
                                LorebookNumberField("查询消息数", queryMessages, 1..100, "独立于普通扫描深度；只取近期文本，最多 8000 字符。") { value -> queryMessages = value; value.toIntOrNull()?.takeIf { it in 1..100 }?.let { count -> vm.updateLorebookSources { it.copy(vectorQueryMessages = count) } } }
                                LorebookNumberField("最多向量候选", maxEntries, 1..50, "候选还需通过角色、概率、分组和预算检查。") { value -> maxEntries = value; value.toIntOrNull()?.takeIf { it in 1..50 }?.let { count -> vm.updateLorebookSources { it.copy(vectorMaxEntries = count) } } }
                                Select(listOf(.3f, .5f, .65f, .8f, .9f, sources.vectorMinScore).distinct().sorted(), sources.vectorMinScore,
                                    onOptionSelected = { score -> vm.updateLorebookSources { it.copy(vectorMinScore = score) } }, optionToString = { "最低相似度 $it" })
                                LorebookHint("条目内容和模型变更会使旧索引失效。每次最多新建 32 项、最多等待 20 秒；未完成部分在后续请求继续补齐。索引缓存独立于事实与情景记忆，最多保留 1000 项。")
                            }
                        }
                    }
                    1 -> item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            LorebookHint("Persona 是可切换的用户设定与世界书分组。当前版本用于世界书来源管理，不会自动重写聊天昵称或助手系统提示词。")
                            Select(listOf<Uuid?>(null) + sources.personas.map { it.id }, sources.activePersonaId, onOptionSelected = { id -> vm.updateLorebookSources { it.copy(activePersonaId = id) } }, optionToString = { id -> sources.personas.firstOrNull { it.id == id }?.name ?: "不启用 Persona" })
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { editPersona = LorebookPersona() }) { Text("新增 Persona") }
                                if (persona != null) { TextButton(onClick = { editPersona = persona }) { Text("编辑") }; TextButton(onClick = { deletePersona = persona }) { Text("删除") } }
                            }
                            persona?.let { LorebookHint(it.description) }
                        }
                    }
                    2 -> item {
                        Select(settings.assistants, assistant ?: settings.assistants.firstOrNull(), onOptionSelected = { assistantId = it?.id?.toString() }, optionToString = { it?.name?.ifBlank { "未命名助手" }.orEmpty() })
                        assistant?.let { selected ->
                            LorebookToggle("继承全局世界书", selected.useGlobalLorebooks) { enabled -> vm.updateSourceAssistant(selected.id) { it.copy(useGlobalLorebooks = enabled) } }
                            LorebookToggle("继承当前 Persona 世界书", selected.usePersonaLorebooks) { enabled -> vm.updateSourceAssistant(selected.id) { it.copy(usePersonaLorebooks = enabled) } }
                            LorebookHint("下面设置助手自己的世界书，与助手扩展设置共用同一绑定。Chat 来源仍由聊天扩展管理选择。")
                            var fieldsExpanded by rememberSaveable { mutableStateOf(false) }
                            TextButton(onClick = { fieldsExpanded = !fieldsExpanded }) { Text(if (fieldsExpanded) "收起角色匹配资料" else "编辑角色匹配资料") }
                            if (fieldsExpanded) lorebookMatchingSourceLabels.filterKeys { it != "matchPersonaDescription" }.forEach { (key, label) ->
                                OutlinedTextField(selected.lorebookCharacterFields[key].orEmpty(), { value -> vm.updateSourceAssistant(selected.id) { it.copy(lorebookCharacterFields = it.lorebookCharacterFields + (key to value)) } }, label = { Text(label) }, modifier = Modifier.fillMaxWidth(), maxLines = 5)
                            }
                        }
                    }
                }
                vm.saveError?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
                item { OutlinedTextField(query, { query = it }, label = { Text("搜索世界书") }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
                if (tab == 1 && persona == null) item { LorebookHint("先选择或创建 Persona，再为它绑定世界书。") }
                else if (tab != 2 || assistant != null) items(books, key = { it.id.toString() }) { book ->
                    val selected = when (tab) { 0 -> book.id in sources.globalIds; 1 -> book.id in persona!!.lorebookIds; else -> book.id in assistant!!.lorebookIds }
                    LorebookToggle(book.name, selected, if (book.enabled) "${book.entries.size} 个条目" else "此书已停用，绑定后仍不参与注入") { enabled ->
                        fun Set<Uuid>.updated() = if (enabled) this + book.id else this - book.id
                        when (tab) {
                            0 -> vm.updateLorebookSources { it.copy(globalIds = it.globalIds.updated()) }
                            1 -> vm.updateLorebookSources { it.copy(personas = it.personas.map { p -> if (p.id == persona!!.id) p.copy(lorebookIds = p.lorebookIds.updated()) else p }) }
                            2 -> vm.updateSourceAssistant(assistant!!.id) { it.copy(lorebookIds = it.lorebookIds.updated()) }
                        }
                    }
                }
            }
        }
    }
    editPersona?.let { original ->
        var name by remember(original.id) { mutableStateOf(original.name) }
        var description by remember(original.id) { mutableStateOf(original.description) }
        AppearanceAlertDialog(onDismissRequest = { editPersona = null }, title = { Text("Persona") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("名称") })
                OutlinedTextField(description, { description = it }, label = { Text("设定描述（可选）") }, maxLines = 5)
            }
        }, confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = {
            vm.updateLorebookSources { current ->
                val persona = original.copy(name = name.trim(), description = description)
                current.copy(personas = if (current.personas.any { it.id == original.id }) current.personas.map { if (it.id == original.id) it.copy(name = persona.name, description = persona.description) else it } else current.personas + persona, activePersonaId = original.id)
            }; editPersona = null
        }) { Text("保存") } }, dismissButton = { TextButton(onClick = { editPersona = null }) { Text("取消") } })
    }
    deletePersona?.let { target -> AppearanceAlertDialog(onDismissRequest = { deletePersona = null }, title = { Text("删除 Persona？") }, text = { Text("仅删除“${target.name}”及其绑定，不删除世界书和聊天记录。") }, confirmButton = { TextButton(onClick = { vm.updateLorebookSources { it.copy(personas = it.personas.filterNot { it.id == target.id }, activePersonaId = it.activePersonaId?.takeUnless { id -> id == target.id }) }; deletePersona = null }) { Text("删除") } }, dismissButton = { TextButton(onClick = { deletePersona = null }) { Text("取消") } }) }
}
