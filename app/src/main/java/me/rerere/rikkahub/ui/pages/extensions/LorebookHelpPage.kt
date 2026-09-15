package me.rerere.rikkahub.ui.pages.extensions

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.AppearanceModalBottomSheet
import me.rerere.rikkahub.ui.components.ui.LargeFlexibleTopAppBar
import me.rerere.rikkahub.ui.theme.CustomColors

internal data class LorebookHelpTopic(val id: String, val title: String, val summary: String, val steps: List<String>, val example: String? = null)

internal val lorebookHelpTopics = listOf(
    LorebookHelpTopic("start", "1. 五分钟建立第一本世界书", "创建 → 条目 → 测试 → 保存 → 绑定", listOf(
        "在提示词与世界书页新建世界书，填写名称。启用只表示允许使用，不代表自动作用于所有对话。",
        "在“条目”页新增条目：名称写“星港”，正文写完整设定，关键词每行写一个，例如“星港”。名称、分类和关键词本身不会自动注入模型。",
        "打开条目的“测试”，输入“我们去星港吧”，点击测试条件。这里只检查关键词／表达式，不消耗模型额度。",
        "点击“暂存条目”，再点击世界书底部的“保存”。条目暂存不等于已写入磁盘。离开未保存草稿会提示。",
        "在助手的扩展设置或当前对话的扩展管理中选择这本书；对话级开关需先允许对话单独配置。先用一小段对话确认注入，再逐步增加条目。"
    ), "名称：星港\n关键词：星港\n正文：星港是位于轨道上的中立补给站。"),
    LorebookHelpTopic("keywords", "2. 关键词与条件组合", "简单条件按行写，复杂条件使用辅助生成器", listOf(
        "关键词每行一个；默认命中任意一项即可。长短语与正则中的空格、逗号不是本编辑器的分隔符。",
        "填写 AND／OR／NOT 表达式后，它会替代普通关键词条件，而不是再追加一层。原始普通关键词仍保留，便于以后切回。",
        "AND 表示同时满足，OR 表示满足任一，NOT 表示排除。关键词用双引号包裹；可视化辅助能自动处理引号与反斜杠。生成前会确认是否替换已有表达式。",
        "SillyTavern 辅助关键词：AND ANY 要求主关键词和任一辅助词；AND ALL 要求主关键词和全部辅助词；NOT ANY 要求辅助词全部不命中；NOT ALL 排除辅助词全部命中的情况。导入后转为组合表达式。",
        "区分大小写适合英文名称。完整词匹配可防止 cat 命中 catalog；中文、日文通常关闭。常驻条目无需关键词，但仍受绑定、预算、概率等约束。"
    ), "\"星港\" AND (\"维修\" OR \"补给\") AND NOT \"梦境\""),
    LorebookHelpTopic("regex", "3. 正则表达式与导入字符", "不要把合法转义和标点当成乱码删除", listOf(
        "SillyTavern／V2 关键词可混用普通词与 /pattern/flags，不必开启“所有关键词按正则处理”。应用原生的正则开关使用 Android／Java 表达式，不加 /…/ 包裹。",
        "支持常见 i、m、s、u 标志，g 按无状态布尔匹配处理；y、d、v 和无效标志会显示兼容诊断。JavaScript 与 Java 正则仍不完全等价。",
        "V3 关闭正则时，斜杠也是普通字符。按当前适配，V3 正则模式忽略常驻和辅助条件。",
        "\\n、\\s、\\x01 等可能是正则语法，不是导入乱码。导入条目的扫描缓冲已加入 \\x01 分隔符；开启名称前缀后可以匹配具体用户或助手。原生扫描文本保持原有格式。",
        "复杂正则受到字符读取与长度限制，避免聊天卡死。遇到错误请先在测试页使用较短文本逐项检查，不要批量替换设定正文。"
    ), "导入关键词：/星港.*(?:维修|补给)/i\n原生正则关键词：星港.*(?:维修|补给)"),
    LorebookHelpTopic("scan", "4. 扫描深度与数值范围参考", "推荐从 4 起步；按场景选择范围，区分扫描与插入深度", listOf(
        "扫描深度决定从最近多少条消息中寻找触发词。0 表示不扫描聊天，不代表常驻。继承模式使用本书默认值；“仅当前用户输入”是独立选择。",
        "常驻跳过关键词匹配；仅在本书被绑定且条目启用时作为候选。扫描 0 的规则可能被递归触发，也可以通过已明确开启的向量语义匹配或额外资料来源触发。",
        "扫描来源可选用户、助手或全部消息；当前扫描以消息文本为主，附件和工具结果不应视为完整可扫描数据。",
        "插入位置决定设定进入上下文的地方；指定插入深度 0 表示提示末尾。插入角色仅在独立消息插入点生效。",
        "Outlet 是命名插槽，不自动注入；必须在提示词里引用 {{outlet::名称}}。名称区分大小写；不要在条目正文里嵌套 Outlet 来制造循环。"
    ) + lorebookScanDepthHelpSteps),
    LorebookHelpTopic("budget", "5. 预算、优先级与分组", "避免世界书挤满上下文", listOf(
        "本书预算与跨书总预算分别限制可用 Token，0 表示该层不限。Token 是估算值，最终请求还包含其他提示词、附件和协议开销。",
        "预算优先级越高越先保留。显式插入顺序越小越靠前；顺序留空沿用原生优先级排序，两者不是同一个选项。",
        "超限可选择跳过条目、截断最后一项或整本跳过。为保留完整设定，推荐优先跳过，不轻易截断。",
        "分组用于在同类候选中选出内容；无权重时按优先级，正权重参与随机。当前多组竞争和辅助条件评分仍有兼容差异，重要互斥建议先用单组测试。",
        "“忽略预算”绕过本书与跨书上限，容易挤占上下文，只应给确实必需的短条目使用。"
    )),
    LorebookHelpTopic("events", "6. 概率、持续、冷却和递归", "用于随机事件与阶段性剧情", listOf(
        "概率 100% 每次条件通过都可成为候选，0% 不通过概率。相同对话种子与轮次可复现，重复测试不一定抽到不同结果。",
        "原生按用户轮次计数；导入通常按消息计数，用户和助手消息分别计算。按消息计数时持续 0 表示不延续，按用户轮次时最小 1。",
        "持续期结束后进入冷却；延迟消息数要求历史先达到一定长度。修改规则会让不再适用的旧状态失效。",
        "开启本书递归扫描后，已采用条目的正文可以触发其他条目。“阻止继续递归”不让本条正文触发别人；“递归阶段排除”不让别人触发本条。",
        "“仅递归激活”不参加初始聊天扫描；同时选择“递归阶段排除”会没有可激活阶段。仅递归激活的条目按级别逐步放行；每本书预算跨扫描轮累计，不会在递归时重置。",
        "生成类型用中文选项选择；全不选代表不限制。静默生成只有通过世界书评估器的调用才适用，并不保证所有后台任务都会注入。"
    )),
    LorebookHelpTopic("diagnose", "7. 未触发时按这个顺序排查", "区分命中、采用和模型遵循", listOf(
        "确认世界书已绑定、书与条目均启用，且没有待映射的插入位置。来源标签 Global 不是“自动对所有对话启用”。",
        "确认扫描模式和深度，检查是否误填了替代普通关键词的表达式；普通模式下原生高级规则不运行，导入兼容规则则继续生效。",
        "在条目测试页先验证条件，再到世界书详情运行场景模拟，逐轮检查概率、持续、冷却、递归、组竞争及预算。",
        "聊天“上下文窗口”附近的“提示词与世界书诊断”可查看命中词、采用条目、位置、估算 Token 和实际注入正文。未采用不一定是关键词没有命中。",
        "独立测试不绑定真实助手变量，完整模拟也不是最终供应商请求。只有实际聊天诊断和请求才能确认本次注入；注入成功也不保证模型一定遵循。",
        "导入优化版时先保留原文件，避免旧书和新书同时绑定造成重复注入。出现来源信息缺失时重新导入，不按名称猜测并改写正文。"
    )),
    LorebookHelpTopic("compatibility", "8. 当前兼容边界与后续计划", "保留字段不等于支持执行", listOf(
        "已具备：原生／SillyTavern／角色卡 V2/V3 导入、条件组合、扫描模式、预算、事件基础、递归基础、命名插槽、角色筛选、诊断与本地版本恢复。",
        "已补齐导入扫描的消息边界、逐步回溯、延迟递归分层、NOT 不加分和跨轮预算；跨书全局竞争及时间状态仍需继续逐例对照。",
        "来源与绑定页可配置全局、Persona 和助手绑定，并选择插入顺序策略。Chat 由当前对话选择，停用规则优先；导入标签不会自动开启绑定。",
        "作者注释与示例消息位置采用本地映射，不是 SillyTavern 原始锚点；V3 装饰器只支持子集。",
        "已新增向量语义触发和额外角色／Persona 资料匹配，默认关闭；需要配置模型并明确选择条目。完整 Tavern 导出、精确锚点和 STscript 执行仍需后续接入，导入脚本不会自动运行。",
        "完整能力清单见仓库 docs/worldbook-compatibility-audit.md；本页随功能迭代更新。"
    )),
)

@Composable
fun LorebookHelpPage(onClose: (() -> Unit)? = null) {
    var query by rememberSaveable { mutableStateOf("") }
    val topics = remember(query) { lorebookHelpTopics.filter { query.isBlank() || (it.title + it.summary + it.steps.joinToString()).contains(query.trim(), true) } }
    val uri = LocalUriHandler.current
    Scaffold(topBar = { LargeFlexibleTopAppBar(title = { Text("世界书帮助与教程") }, navigationIcon = { if (onClose == null) BackButton() else TextButton(onClick = onClose) { Text("返回") } }, colors = CustomColors.topBarColors) }, containerColor = CustomColors.scaffoldContainerColor) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { OutlinedTextField(query, { query = it }, label = { Text("搜索：关键词、预算、递归、未触发…") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
            if (topics.isEmpty()) item { LorebookHint("没有匹配的帮助主题，请尝试更短的关键词。") }
            items(topics, key = { it.id }) { topic ->
                var expanded by rememberSaveable(topic.id) { mutableStateOf(topic.id == "start") }
                Card(colors = CustomColors.cardColorsOnSurfaceContainer) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) { Text("${if (expanded) "▾" else "▸"} ${topic.title}", Modifier.weight(1f)) }
                        LorebookHint(topic.summary)
                        if (expanded || query.isNotBlank()) {
                            topic.steps.forEachIndexed { index, text -> SelectionContainer { Text("${index + 1}. $text", style = MaterialTheme.typography.bodyMedium) } }
                            topic.example?.let { SelectionContainer { Text(it, style = MaterialTheme.typography.bodySmall) } }
                        }
                    }
                }
            }
            item {
                LorebookHint("教程可离线阅读；打开官方资料需要网络。资料描述的是 SillyTavern，本应用的实际支持以当前兼容说明为准。")
                TextButton(onClick = { uri.openUri("https://docs.sillytavern.app/usage/core-concepts/worldinfo/") }) { Text("SillyTavern 官方 World Info 文档") }
            }
        }
    }
}

@Composable internal fun LorebookHelpSheet(onDismiss: () -> Unit) {
    AppearanceModalBottomSheet(onDismissRequest = onDismiss) {
        Box(Modifier.fillMaxWidth().fillMaxHeight(.95f)) { LorebookHelpPage(onClose = onDismiss) }
    }
}
