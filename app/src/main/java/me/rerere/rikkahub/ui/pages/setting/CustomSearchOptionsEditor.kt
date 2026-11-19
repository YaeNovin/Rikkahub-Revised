package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.ui.components.ui.AppearanceOptionSurface
import me.rerere.rikkahub.ui.components.ui.OutlinedNumberInput
import me.rerere.rikkahub.ui.components.ui.appearanceOutlinedTextFieldColors
import me.rerere.search.*

@Composable
internal fun CustomSearchOptionsEditor(
    options: SearchServiceOptions.CustomOptions,
    onUpdate: (SearchServiceOptions.CustomOptions) -> Unit,
) {
    val normalized = remember(options) { options.withDedicatedSearchModel() }
    CustomSearchOptionsFields(normalized, onUpdate)
}

@Composable
private fun CustomSearchOptionsFields(
    options: SearchServiceOptions.CustomOptions,
    onUpdate: (SearchServiceOptions.CustomOptions) -> Unit,
) {
    val error = remember(options) { customSearchConfigurationError(options) }
    CustomSearchField("服务名称", options.name, { onUpdate(options.copy(name = it)) })
    Text("接口类型", style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(CustomSearchKind.WEB to "网页搜索", CustomSearchKind.X to "X 搜索", CustomSearchKind.CUSTOM to "其他 JSON POST")
            .forEach { (kind, label) ->
                FilterChip(selected = options.kind == kind, onClick = {
                    val path = when (kind) {
                        CustomSearchKind.WEB -> "/v1/web_search"
                        CustomSearchKind.X -> "/v1/x_search"
                        CustomSearchKind.CUSTOM -> options.endpoint
                    }
                    // Switching templates changes only a known default relative path.
                    // A full endpoint or custom path is never silently overwritten.
                    onUpdate(options.copy(kind = kind, endpoint = if (options.endpoint in setOf("/v1/web_search", "/v1/x_search")) path else options.endpoint))
                }, label = { Text(label) })
            }
    }
    Text("模板只决定参数适用范围与默认路径；不预填第三方服务器，不使用对话接口的内置搜索工具。", style = MaterialTheme.typography.bodySmall)
    SearchParameterToggle("启用 Base URL", options.baseUrlEnabled, { onUpdate(options.copy(baseUrlEnabled = it)) })
    CustomSearchField("Base URL（默认空）", options.baseUrl, { onUpdate(options.copy(baseUrl = it)) }, options.baseUrlEnabled)
    CustomSearchField(if (options.baseUrlEnabled) "接口路径，例如 /v1/web_search" else "完整接口 URL（必填）",
        options.endpoint, { onUpdate(options.copy(endpoint = it)) })
    Text("启用时按 Base URL + 接口路径拼接并保留 Base URL 的路径前缀；关闭时只使用完整接口 URL。地址必须为 HTTP(S)，不含查询参数。关闭不会删除已保存的值。建议使用 HTTPS。", style = MaterialTheme.typography.bodySmall)
    SearchParameterToggle("发送 API Key", options.apiKeyEnabled, { onUpdate(options.copy(apiKeyEnabled = it)) })
    OutlinedTextField(value = options.apiKey, onValueChange = { onUpdate(options.copy(apiKey = it)) },
        enabled = options.apiKeyEnabled, singleLine = true, visualTransformation = PasswordVisualTransformation(),
        label = { Text("API Key") }, modifier = Modifier.fillMaxWidth(), colors = appearanceOutlinedTextFieldColors())
    Text("勾选后以 Authorization: Bearer <API Key> 发送；取消勾选时完全不发送该认证头，密钥保留。", style = MaterialTheme.typography.bodySmall)
    HorizontalDivider()
    Text("固定请求参数", style = MaterialTheme.typography.titleSmall)
    SearchParameterToggle("query · 必填，由实际搜索内容自动填入", true, {}, enabled = false)
    OutlinedTextField(value = "POST\nContent-Type: application/json\nAccept: application/json",
        onValueChange = {}, enabled = false, readOnly = true, modifier = Modifier.fillMaxWidth(),
        label = { Text("固定协议（不可调整）") }, colors = appearanceOutlinedTextFieldColors())
    Text("可选请求参数：只有勾选才发送", style = MaterialTheme.typography.titleSmall)
    OptionalSearchField("model · 模型名称（选填）", options.model ?: OptionalSearchValue(),
        { onUpdate(options.copy(model = it)) })
    Text("勾选且填写后发送 model；留空或取消勾选时不发送，采用服务端默认值。关闭会保留已填写的模型名称，与聊天模型选择无关。", style = MaterialTheme.typography.bodySmall)
    OptionalSearchField("max_results · 返回条数（1–20）", options.maxResults,
        { onUpdate(options.copy(maxResults = it)) })
    Text("未勾选时不发送 max_results，采用服务端默认值；不自动套用页面底部的全局返回条数。截图接口的默认值为 5。", style = MaterialTheme.typography.bodySmall)
    val x = options.kind == CustomSearchKind.X
    Text("X 搜索过滤（仅选择 X 搜索时适用）", style = MaterialTheme.typography.titleSmall)
    OptionalSearchField("allowed_x_handles · 只搜索这些账号", options.allowedXHandles,
        { onUpdate(options.copy(allowedXHandles = it)) }, x, multiline = true)
    OptionalSearchField("excluded_x_handles · 排除这些账号", options.excludedXHandles,
        { onUpdate(options.copy(excludedXHandles = it)) }, x, multiline = true)
    Text("账号用逗号、空格或换行分隔；自动去掉开头的 @。切换到其他类型时保留配置，但不发送这四项过滤。", style = MaterialTheme.typography.bodySmall)
    OptionalSearchField("from_date · 起始日期 YYYY-MM-DD", options.fromDate, { onUpdate(options.copy(fromDate = it)) }, x)
    OptionalSearchField("to_date · 结束日期 YYYY-MM-DD", options.toDate, { onUpdate(options.copy(toDate = it)) }, x)
    HorizontalDivider()
    Text("额外 JSON 请求参数", style = MaterialTheme.typography.titleSmall)
    Text("用于其他服务支持的 language、search_depth、include_domains 等字段；应用不假定服务端支持它们。仅勾选的行加入 JSON，不能覆盖 query 或上面的固定名称。", style = MaterialTheme.typography.bodySmall)
    options.extraParameters.forEach { parameter ->
        key(parameter.id) {
            fun update(value: CustomSearchParameter) = onUpdate(options.copy(extraParameters = options.extraParameters.map { if (it.id == parameter.id) value else it }))
            AppearanceOptionSurface {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(parameter.enabled, { update(parameter.copy(enabled = it)) })
                            Text("发送此参数")
                        }
                        TextButton(onClick = { onUpdate(options.copy(extraParameters = options.extraParameters.filterNot { it.id == parameter.id })) }) { Text("删除") }
                    }
                    CustomSearchField("参数名", parameter.name, { update(parameter.copy(name = it)) }, parameter.enabled)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(CustomSearchParameterType.STRING to "文本", CustomSearchParameterType.NUMBER to "数字",
                            CustomSearchParameterType.BOOLEAN to "布尔", CustomSearchParameterType.JSON to "JSON")
                            .forEach { (type, label) -> FilterChip(selected = parameter.type == type, enabled = parameter.enabled,
                                onClick = { update(parameter.copy(type = type)) }, label = { Text(label) }) }
                    }
                    CustomSearchField("参数值（布尔用 true/false，JSON 可填数组或对象）", parameter.value,
                        { update(parameter.copy(value = it)) }, parameter.enabled, multiline = true)
                }
            }
        }
    }
    TextButton(enabled = options.extraParameters.size < 32, onClick = {
        onUpdate(options.copy(extraParameters = options.extraParameters + CustomSearchParameter()))
    }) { Text("添加参数（最多 32 项）") }
    HorizontalDivider()
    Text("本地请求与结果解析（这些设置不传给服务器）", style = MaterialTheme.typography.titleSmall)
    OutlinedNumberInput(value = options.timeoutSeconds, onValueChange = { onUpdate(options.copy(timeoutSeconds = it.coerceIn(10, 180))) },
        label = "请求超时（秒，10–180）", modifier = Modifier.fillMaxWidth())
    Text("默认 60 秒，适用于耗时 10–30 秒的搜索。失败不自动重试，不跟随重定向，避免重复计费或向其他地址转发密钥。", style = MaterialTheme.typography.bodySmall)
    CustomSearchField("结果列表路径（留空表示顶层数组）", options.resultsPath, { onUpdate(options.copy(resultsPath = it)) })
    CustomSearchField("链接字段", options.urlPath, { onUpdate(options.copy(urlPath = it)) })
    CustomSearchField("标题字段", options.titlePath, { onUpdate(options.copy(titlePath = it)) })
    CustomSearchField("摘要字段", options.snippetPath, { onUpdate(options.copy(snippetPath = it)) })
    Text("默认读取 results 中每项的 url/title/snippet；支持 data.results 这样的点分对象路径，不执行 JSONPath 或脚本。缺少标题使用占位标题，缺少摘要为空；无有效链接会报错。", style = MaterialTheme.typography.bodySmall)
    Text(error ?: "配置有效，可在下方执行一次搜索测试。测试会向你填写的服务发送查询，可能产生费用。",
        color = if (error == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall)
    val body = remember(options) { runCatching { buildCustomSearchBody("<实际搜索内容>", options) }.getOrNull() }
    Text("将发送的 JSON 字段：${body?.keys?.joinToString() ?: "请先修正已勾选参数"}", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun SearchParameterToggle(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onCheckedChange, enabled = enabled)
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun OptionalSearchField(label: String, field: OptionalSearchValue, update: (OptionalSearchValue) -> Unit,
    available: Boolean = true, multiline: Boolean = false) {
    SearchParameterToggle(label, field.enabled, { update(field.copy(enabled = it)) }, available)
    CustomSearchField(label, field.value, { update(field.copy(value = it)) }, available && field.enabled, multiline)
}

@Composable
private fun CustomSearchField(label: String, value: String, update: (String) -> Unit, enabled: Boolean = true, multiline: Boolean = false) {
    OutlinedTextField(value, update, label = { Text(label) }, modifier = Modifier.fillMaxWidth(), enabled = enabled,
        singleLine = !multiline, maxLines = if (multiline) 5 else 1, colors = appearanceOutlinedTextFieldColors())
}
