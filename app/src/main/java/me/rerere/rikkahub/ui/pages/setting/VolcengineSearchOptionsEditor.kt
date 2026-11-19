package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.ui.components.ui.FormItem
import me.rerere.search.SearchServiceOptions.VolcengineOptions

@Composable
internal fun VolcengineSearchOptionsEditor(options: VolcengineOptions, onUpdate: (VolcengineOptions) -> Unit) {
    SearchOptionChoices("接口版本", if (options.global) "Global" else "Custom", listOf("Custom", "Global")) {
        onUpdate(options.copy(global = it == "Global", searchType = "web"))
    }
    SearchOptionText(if (options.global) "Global API Key" else "Custom API Key", options.activeApiKey(),
        if (options.global) "填写搜索控制台的按量后付费 Key；Global 不支持订阅套餐 Key。"
        else "支持搜索控制台的订阅套餐 Key 或按量后付费 Key。") {
        onUpdate(options.withActiveApiKey(it))
    }
    Text("订阅与后付费 Key 相互独立。两个版本的 Key 分别保存；若使用同一把具备相应权限的后付费 Key，可分别填入。切换版本不会覆盖另一版本的 Key。")
    Text(if (options.global) "Global：全球网页、文搜图与图搜图。" else "Custom：支持国内站点、发文时间、行业与权威度筛选。")
    SearchOptionChoices("搜索类型", options.searchType,
        if (options.global) listOf("web", "image", "visual") else listOf("web", "image")) {
        onUpdate(options.copy(searchType = it))
    }
    if (options.searchType == "visual") Text("图搜图：模型需提供可访问的图片 URL。可同时提供文字和 [0,1] 范围的检索区域。")
    if (options.searchType != "image" || options.global) {
        SearchOptionSwitch("超限请求排队", options.enableWaiting) { onUpdate(options.copy(enableWaiting = it)) }
        if (options.enableWaiting) SearchOptionText("最长排队时间（毫秒，1–10000）", options.maxWaitTime.toString()) {
            it.toIntOrNull()?.let { value -> onUpdate(options.copy(maxWaitTime = value.coerceIn(1, 10000))) }
        }
    }
    if (options.global) {
        SearchOptionText("每段摘要长度（最大 3000 tokens）", options.maxSnippetLength.toString()) {
            it.toIntOrNull()?.let { value -> onUpdate(options.copy(maxSnippetLength = value.coerceIn(1, 3000))) }
        }
        if (options.searchType == "web") {
            SearchOptionSwitch("仅搜索国内 ICP 备案网站", options.icpHostOnly) { onUpdate(options.copy(icpHostOnly = it)) }
            SearchOptionText("每个结果最多图片数（1–10）", options.maxImageCountPerDoc.toString()) {
                it.toIntOrNull()?.let { value -> onUpdate(options.copy(maxImageCountPerDoc = value.coerceIn(1, 10))) }
            }
        }
        if (options.searchType == "image") {
            SearchOptionText("图片短边下限（像素，可留空）", options.shortEdgePixelMin?.toString().orEmpty()) { onUpdate(options.copy(shortEdgePixelMin = it.toIntOrNull())) }
            SearchOptionText("图片短边上限（像素，可留空）", options.shortEdgePixelMax?.toString().orEmpty()) { onUpdate(options.copy(shortEdgePixelMax = it.toIntOrNull())) }
            SearchOptionText("高宽比下限（height / width，可留空）", options.aspectRatioMin?.toString().orEmpty()) { onUpdate(options.copy(aspectRatioMin = it.toDoubleOrNull())) }
            SearchOptionText("高宽比上限（height / width，可留空）", options.aspectRatioMax?.toString().orEmpty()) { onUpdate(options.copy(aspectRatioMax = it.toDoubleOrNull())) }
        }
    } else {
        SearchOptionSwitch("搜索词改写", options.queryRewrite) { onUpdate(options.copy(queryRewrite = it)) }
        if (options.searchType == "web") {
            SearchOptionSwitch("仅返回有正文的结果", options.needContent) { onUpdate(options.copy(needContent = it)) }
            SearchOptionSwitch("仅返回有原文链接的结果", options.needUrl) { onUpdate(options.copy(needUrl = it)) }
            SearchOptionSwitch("仅搜索国内 ICP 备案网站", options.icpHostOnly) { onUpdate(options.copy(icpHostOnly = it)) }
            SearchOptionSwitch("仅搜索非常权威内容", options.authInfoLevel == 1) { onUpdate(options.copy(authInfoLevel = if (it) 1 else 0)) }
            SearchOptionText("限定站点（最多 20 个，| 分隔）", options.sites) { onUpdate(options.copy(sites = it)) }
            SearchOptionText("屏蔽站点（最多 5 个，| 分隔）", options.blockHosts) { onUpdate(options.copy(blockHosts = it)) }
            SearchOptionText("发文时间", options.timeRange, "留空或 OneDay / OneWeek / OneMonth / OneYear / YYYY-MM-DD..YYYY-MM-DD") { onUpdate(options.copy(timeRange = it)) }
            SearchOptionChoices("正文格式", options.contentFormats, listOf("text", "markdown")) { onUpdate(options.copy(contentFormats = it)) }
            SearchOptionChoices("行业", options.industry, listOf("", "finance", "game", "health", "gov")) { onUpdate(options.copy(industry = it)) }
        } else {
            SearchOptionText("图片最小宽度（可留空）", options.imageWidthMin?.toString().orEmpty()) { onUpdate(options.copy(imageWidthMin = it.toIntOrNull())) }
            SearchOptionText("图片最大宽度（可留空）", options.imageWidthMax?.toString().orEmpty()) { onUpdate(options.copy(imageWidthMax = it.toIntOrNull())) }
            SearchOptionText("图片最小高度（可留空）", options.imageHeightMin?.toString().orEmpty()) { onUpdate(options.copy(imageHeightMin = it.toIntOrNull())) }
            SearchOptionText("图片最大高度（可留空）", options.imageHeightMax?.toString().orEmpty()) { onUpdate(options.copy(imageHeightMax = it.toIntOrNull())) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                listOf("横长方形", "竖长方形", "方形").forEach { shape ->
                    FilterChip(selected = shape in options.imageShapes, onClick = {
                        onUpdate(options.copy(imageShapes = if (shape in options.imageShapes) options.imageShapes - shape else options.imageShapes + shape))
                    }, label = { Text(shape) })
                }
            }
        }
    }
}

@Composable private fun SearchOptionText(title: String, value: String, description: String = "", update: (String) -> Unit) {
    FormItem(label = { Text(title) }, description = { if (description.isNotBlank()) Text(description) }) {
        OutlinedTextField(value = value, onValueChange = update, modifier = Modifier.fillMaxWidth(), singleLine = true)
    }
}

@Composable private fun SearchOptionSwitch(title: String, checked: Boolean, update: (Boolean) -> Unit) {
    FormItem(label = { Text(title) }, tail = { Switch(checked = checked, onCheckedChange = update) })
}

@Composable private fun SearchOptionChoices(title: String, value: String, values: List<String>, update: (String) -> Unit) {
    FormItem(label = { Text(title) }) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            values.forEach { option ->
                FilterChip(selected = value == option, onClick = { update(option) }, label = { Text(option.ifBlank { "不限" }) })
            }
        }
    }
}
