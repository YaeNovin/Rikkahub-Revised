package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.rerere.ai.provider.GeminiSearchOptions
import me.rerere.ai.provider.providers.google.geminiSearchTimeRangeError
import me.rerere.rikkahub.ui.components.ui.FormItem

@Composable
internal fun GeminiSearchSettings(
    options: GeminiSearchOptions,
    nativeProtocol: Boolean,
    supportsImageSearch: Boolean,
    legacySearchEnabled: Boolean = false,
    legacyUrlContextEnabled: Boolean = false,
    interactions: Boolean = false,
    update: (GeminiSearchOptions) -> Unit,
) {
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Google 内置搜索与 URL 上下文", style = MaterialTheme.typography.titleMedium)
        if (!nativeProtocol) Text("这些工具需要供应商使用 Gemini 原生接口。")
        FormItem(label = { Text("Google 搜索") }, tail = {
            Switch(options.enabled ?: legacySearchEnabled, { update(options.copy(enabled = it)) }, enabled = nativeProtocol)
        })
        FormItem(label = { Text("网页搜索") }, tail = {
            Switch(options.webSearch || !supportsImageSearch || !options.imageSearch,
                { update(options.copy(webSearch = it)) },
                enabled = nativeProtocol && supportsImageSearch && options.imageSearch)
        })
        if (supportsImageSearch) {
            FormItem(label = { Text("图片搜索增强") }, description = {
                Text("检索网页图片作为图像生成参考，可与上传图片、网页搜索组合使用。")
            }, tail = {
                Switch(options.imageSearch, { update(options.copy(imageSearch = it)) }, enabled = nativeProtocol)
            })
        } else {
            Text("当前模型支持网页搜索和 URL 内容读取。图片搜索增强仅对官方支持的图像模型开放。")
            if (options.imageSearch) Text("已保留图像模型的搜索偏好，当前模型使用网页搜索。")
        }
        val error = geminiSearchTimeRangeError(options)
        if (interactions) Text("Interactions API(Beta) 尚无搜索时间范围参数。可清空已有时间，或切回 GenerateContent 使用。")
        OutlinedTextField(options.startTime, { update(options.copy(startTime = it)) },
            label = { Text("搜索开始时间（含）") }, placeholder = { Text("2026-01-01T00:00:00Z，可留空") },
            enabled = nativeProtocol, isError = error != null, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(options.endTime, { update(options.copy(endTime = it)) },
            label = { Text("搜索结束时间（不含）") }, placeholder = { Text("2026-12-31T00:00:00Z，可留空") },
            enabled = nativeProtocol, isError = error != null, singleLine = true, modifier = Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        FormItem(label = { Text("URL 上下文") }, tail = {
            Switch(options.urlContext ?: legacyUrlContextEnabled, { update(options.copy(urlContext = it)) }, enabled = nativeProtocol)
        })
        Text("Gemini 2 系列不能将内置工具与自定义函数混用；在此明确开启内置工具时，会优先使用内置工具。Gemini 3 及以上可组合使用。")
        Text("将完整网址放在消息中，可与搜索组合读取公开网页、图片、PDF。每次最多 20 个 URL，每个 URL 最大 34 MB；仅访问给出的 URL，不会递归读取页面链接。登录页、付费墙和非公开地址可能无法读取。")
    }
}
