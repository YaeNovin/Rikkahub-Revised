package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.rikkahub.data.db.dao.KnowledgeDocumentPreviewChunk
import me.rerere.rikkahub.data.repository.KnowledgeBaseRepository
import me.rerere.rikkahub.ui.components.richtext.LinkText
import me.rerere.rikkahub.ui.components.ui.AppearanceModalBottomSheet
import me.rerere.rikkahub.utils.webLinkUrl
import org.koin.compose.koinInject

/** A citation list never loads source text, icons, images or a remote page. */
@Composable
internal fun CitationSources(annotations: List<UIMessageAnnotation>, onKnowledge: (UIMessageAnnotation.KnowledgeCitation) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        annotations.forEachIndexed { index, annotation ->
            when (annotation) {
                is UIMessageAnnotation.UrlCitation -> {
                    val url = webLinkUrl(annotation.url)
                    val title = citationLinkLabel(annotation)
                    val color = MaterialTheme.colorScheme.primary
                    if (url != null) LinkText(
                        text = buildAnnotatedString {
                            append("${index + 1}. ")
                            withLink(LinkAnnotation.Url(url, TextLinkStyles(SpanStyle(
                                color = color, textDecoration = TextDecoration.Underline,
                            )))) { append(title) }
                        },
                        modifier = Modifier.fillMaxWidth().testTag("citation-source-link"),
                    ) else Text("${index + 1}. $title（来源地址不可用）", style = MaterialTheme.typography.bodySmall)
                }
                is UIMessageAnnotation.KnowledgeCitation -> TextButton(
                    onClick = { onKnowledge(annotation) },
                    modifier = Modifier.fillMaxWidth().testTag("knowledge-citation-link"),
                    contentPadding = PaddingValues(0.dp),
                ) {
                    Column(Modifier.fillMaxWidth()) {
                        Text("${index + 1}. ${annotation.title}", style = MaterialTheme.typography.bodyMedium)
                        Text(knowledgeCitationLocation(annotation), style = MaterialTheme.typography.labelSmall)
                    }
                }
                else -> Unit
            }
        }
    }
}

internal fun citationLinkLabel(citation: UIMessageAnnotation.UrlCitation): String =
    citation.title.trim().ifBlank {
        runCatching { java.net.URI(citation.url).host }.getOrNull() ?: citation.url
    }

internal fun knowledgeCitationLocation(citation: UIMessageAnnotation.KnowledgeCitation, chunk: KnowledgeDocumentPreviewChunk? = null): String =
    buildList {
        val start = chunk?.pageStart ?: citation.pageStart
        val end = chunk?.pageEnd ?: citation.pageEnd
        if (start != null) add(if (end != null && end != start) "第 $start–$end 页" else "第 $start 页")
        (chunk?.sectionPath ?: citation.sectionPath).takeIf { it.isNotBlank() }?.let(::add)
        if (chunk != null) {
            add("分块 ${chunk.ordinal + 1}")
            if (chunk.charEnd > chunk.charStart) add("文档字符 ${chunk.charStart + 1}–${chunk.charEnd}")
        }
    }.joinToString(" · ").ifBlank { "查看引用原文" }

internal fun citationExcerptRange(content: String, excerpt: String): IntRange? {
    if (excerpt.isBlank()) return null
    val start = content.indexOf(excerpt)
    return if (start >= 0) start until start + excerpt.length else null
}

/** Center the first view on the cited passage instead of the beginning of a long chunk. */
internal fun citationSourceWindow(content: String, excerpt: String): String {
    val range = citationExcerptRange(content, excerpt) ?: return content
    val start = (range.first - 300).coerceAtLeast(0)
    val end = (range.last + 301).coerceAtMost(content.length)
    return (if (start > 0) "…\n" else "") + content.substring(start, end) + (if (end < content.length) "\n…" else "")
}

@Composable
internal fun KnowledgeCitationSheet(
    citation: UIMessageAnnotation.KnowledgeCitation,
    onDismiss: () -> Unit,
    repository: KnowledgeBaseRepository = koinInject(),
) {
    var chunk by remember(citation) { mutableStateOf<KnowledgeDocumentPreviewChunk?>(null) }
    var loading by remember(citation) { mutableStateOf(true) }
    var showFullChunk by remember(citation) { mutableStateOf(false) }
    LaunchedEffect(citation) {
        try { chunk = withContext(Dispatchers.IO) { repository.getCitationChunk(citation) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { chunk = null }
        finally { loading = false }
    }
    AppearanceModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = 640.dp).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(citation.title, style = MaterialTheme.typography.titleLarge)
            Text(knowledgeCitationLocation(citation, chunk), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary)
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            else {
                val source = chunk
                if (source == null) Text("原文分块已删除或不可读取，以下为本条消息保存的引用摘录。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val fullText = source?.content ?: citation.excerpt
                val text = if (showFullChunk) fullText else citationSourceWindow(fullText, citation.excerpt)
                val range = citationExcerptRange(text, citation.excerpt)
                val fullRange = source?.let { citationExcerptRange(it.content, citation.excerpt) }
                if (source != null && fullRange != null) Text(
                    "引用字符 ${source.charStart + fullRange.first + 1}–${source.charStart + fullRange.last + 1}",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val highlight = MaterialTheme.colorScheme.secondaryContainer
                val foreground = MaterialTheme.colorScheme.onSecondaryContainer
                SelectionContainer {
                    Text(buildAnnotatedString {
                        if (range == null) append(text)
                        else {
                            append(text.substring(0, range.first))
                            withStyle(SpanStyle(background = highlight, color = foreground)) { append(text.substring(range.first, range.last + 1)) }
                            append(text.substring(range.last + 1))
                        }
                    }, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("knowledge-citation-source"))
                }
                if (text != fullText || showFullChunk) TextButton(onClick = { showFullChunk = !showFullChunk }) {
                    Text(if (showFullChunk) "定位引用片段" else "查看完整分块")
                }
                if (source != null && range == null && citation.excerpt.isNotBlank()) {
                    Text("消息保存的引用摘录（原文可能已更新）", style = MaterialTheme.typography.labelMedium)
                    SelectionContainer { Text(citation.excerpt, style = MaterialTheme.typography.bodyMedium) }
                }
            }
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    }
}
