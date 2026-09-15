package me.rerere.rikkahub.ui.pages.extensions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

internal data class ScanDepthReference(val range: IntRange, val label: String, val usage: String)

/** Practical starting points, not SillyTavern requirements or automatic presets. */
internal val lorebookScanDepthReferences = listOf(
    ScanDepthReference(0..0, "0：不扫描聊天", "用于不依赖聊天关键词的配置。不会自动常驻；常驻、递归、额外资料或向量触发需分别配置。"),
    ScanDepthReference(1..2, "1–2：紧跟眼前消息", "适合即时指令、临时场景和不希望被旧话题反复唤起的条目。历史线索容易漏掉。"),
    ScanDepthReference(3..6, "3–6：日常起步（推荐先用 4）", "适合一般聊天、人物和地点设定。兼顾短期上下文与误触发控制，先从这里测试。"),
    ScanDepthReference(7..12, "7–12：连续场景", "适合多次往返的角色扮演、当前任务或剧情。若人物或地点刚离开扫描范围，可尝试增大到这里。"),
    ScanDepthReference(13..30, "13–30：较长话题", "适合跨多条消息仍需关注的线索。建议使用精确关键词和排除条件，防止已结束话题继续触发。"),
    ScanDepthReference(31..100, "31–100：定向回查", "仅在确实需要较早历史时使用。先用模拟器验证，并设置 Token 预算；不建议整本统一调大。"),
    ScanDepthReference(101..1000, "101–1000：高级排查范围", "属于允许输入的高值区间，不是推荐日常配置。长消息、复杂正则和大量条目会明显增加计算量与无关命中风险。"),
)

internal const val LOREBOOK_SCAN_DEPTH_START = "建议先用 4；即时触发可选 1–2，连续剧情可试 7–12。下方可展开完整范围参考。"
internal const val LOREBOOK_SCAN_DEPTH_UNITS = "单位是消息条数，不是 Token 或对话轮数。选择全部消息时，用户和助手各算一条；只选用户／助手来源时，按筛选后的消息计数。"
internal const val LOREBOOK_SCAN_DEPTH_CAVEATS = "以上为本应用的调参起点，不是官方强制标准。范围只针对聊天关键词扫描：最少激活可扩大回溯，递归、额外资料和向量查询另有规则；实际也受当前可用上下文限制。"

internal val lorebookScanDepthHelpSteps: List<String> = listOf(LOREBOOK_SCAN_DEPTH_UNITS) +
    lorebookScanDepthReferences.map { "${it.label}。${it.usage}" } + listOf(
        "计数示例：按时间从早到晚是用户 A、助手 B、用户 C、助手 D、用户 E。全部来源、深度 4 会扫描 B/C/D/E；仅用户、深度 2 扫描 C/E。若只要当前用户输入，直接选择同名扫描模式，不要把深度 1 当成完全等价。",
        "调参顺序：先用 4，在完整场景模拟中观察未采用原因。确认是历史线索超出范围后再试 8、12；若旧话题反复命中，先缩小深度或收紧关键词。不要用加大深度解决预算不足、概率未命中或条目未绑定。",
        "增大深度不会直接把扫描的全部历史重新注入模型，但可能命中更多条目，间接增加上下文占用。想让已触发的事件保持一段时间，可考虑持续激活，而不是无限扩大扫描。",
        "本书默认深度只影响选择“继承本书默认”的条目；自定义深度、仅当前输入和不扫描模式不会被默认值覆盖。导入值只供参考，不会被本教程自动改写。",
        LOREBOOK_SCAN_DEPTH_CAVEATS,
    )

/** Small, opt-in reference beside the fields; shares content with the offline tutorial. */
@Composable
internal fun LorebookScanDepthReference() {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起扫描深度范围参考" else "扫描深度怎么选？查看范围参考") }
        if (expanded) {
            LorebookHint(LOREBOOK_SCAN_DEPTH_UNITS)
            lorebookScanDepthReferences.forEach { reference ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(reference.label, style = MaterialTheme.typography.labelLarge)
                    LorebookHint(reference.usage)
                }
            }
            LorebookHint(LOREBOOK_SCAN_DEPTH_CAVEATS)
        }
    }
}
