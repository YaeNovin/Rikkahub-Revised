package me.rerere.rikkahub.ui.components.richtext

import android.content.ClipData
import android.content.Intent
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.rerere.rikkahub.ui.components.ui.AppearanceModalBottomSheet
import me.rerere.rikkahub.utils.openUrl
import java.net.URI

data class LinkTarget(val url: String, val label: String = "", val onPreviewImage: (() -> Unit)? = null) {
    val isWeb: Boolean get() = runCatching {
        val uri = URI(url)
        uri.scheme.lowercase() in setOf("https", "http") && !uri.host.isNullOrBlank()
    }.getOrDefault(false)
}

val LocalLinkActionHandler = staticCompositionLocalOf<((LinkTarget) -> Unit)?> { null }
internal val LocalCitationLinkAction = staticCompositionLocalOf<((String) -> Unit)?> { null }

internal fun linkQuestionDraft(target: LinkTarget, summarize: Boolean): String {
    require(target.isWeb)
    val instruction = if (summarize) {
        "请读取以下网页，总结主要内容和关键结论，并保留来源链接。如果无法读取网页，请明确说明，不要猜测网页内容。"
    } else {
        "我想围绕以下链接提问：\n"
    }
    return "$instruction\n${target.url}"
}

@Composable
fun Modifier.linkActions(url: String, label: String = ""): Modifier {
    val uriHandler = LocalUriHandler.current
    val showActions = LocalLinkActionHandler.current
    return combinedClickable(
        onClick = { uriHandler.openUri(url) },
        onLongClick = showActions?.let { { it(LinkTarget(url, label)) } },
        onClickLabel = "打开链接",
        onLongClickLabel = "链接操作",
    )
}

@Composable
fun LinkActionsSheet(target: LinkTarget?, onDismiss: () -> Unit, onDraft: (String) -> Unit) {
    if (target == null) return
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    AppearanceModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = 600.dp).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp)) {
            Text("链接操作", style = MaterialTheme.typography.titleLarge)
            target.label.takeIf { it.isNotBlank() && it != target.url }?.let {
                Text(it, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            }
            SelectionContainer {
                Text(target.url, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 12.dp))
            }
            TextButton(onClick = { context.openUrl(target.url); onDismiss() }) { Text("打开链接") }
            target.onPreviewImage?.let { preview ->
                TextButton(onClick = { onDismiss(); preview() }) { Text("查看图片") }
            }
            TextButton(onClick = {
                scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("链接", target.url))); onDismiss() }
            }) { Text("复制链接") }
            if (target.label.isNotBlank() && target.label != target.url) {
                TextButton(onClick = {
                    scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("链接文字", target.label))); onDismiss() }
                }) { Text("复制链接文字") }
            }
            TextButton(onClick = {
                runCatching {
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, target.url)
                    }, "分享链接"))
                }
                onDismiss()
            }) { Text("分享链接") }
            if (target.isWeb) {
                TextButton(onClick = { onDraft(linkQuestionDraft(target, true)); onDismiss() }) { Text("让模型总结网页") }
                TextButton(onClick = { onDraft(linkQuestionDraft(target, false)); onDismiss() }) { Text("围绕链接提问") }
                Text("以上操作会填入草稿，确认发送后执行。读取网页需要为助手开启联网工具。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
