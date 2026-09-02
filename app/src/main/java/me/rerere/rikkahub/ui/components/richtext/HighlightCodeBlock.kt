package me.rerere.rikkahub.ui.components.richtext

import android.content.ClipData
import android.net.Uri
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import me.rerere.highlight.HighlightTextColorPalette
import me.rerere.highlight.buildHighlightText
import me.rerere.highlight.CodeHighlightText
import me.rerere.highlight.CodeHighlighter
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.Code
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.Download04
import me.rerere.hugeicons.stroke.Eye
import me.rerere.hugeicons.stroke.View
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.components.webview.WebView
import me.rerere.rikkahub.ui.components.webview.WebViewContentCache
import me.rerere.rikkahub.ui.components.webview.rememberWebViewState
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.Navigator
import me.rerere.rikkahub.ui.modifier.onClick
import me.rerere.rikkahub.ui.theme.AtomOneDarkPalette
import me.rerere.rikkahub.ui.theme.AtomOneLightPalette
import me.rerere.rikkahub.ui.theme.JetbrainsMono
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import me.rerere.rikkahub.utils.toDp
import org.jsoup.Jsoup
import kotlin.time.Clock

private const val COLLAPSE_LINES = 10
private val PREVIEWABLE_LANGUAGES = setOf("html", "svg")
private val DIFF_LANGUAGES = setOf(
    "diff",
    "patch",
    "udiff",
    "unified-diff",
    "git-diff",
)

internal fun normalizeCodeFenceLanguage(language: String): String = language
    .trim()
    .lowercase()
    .removePrefix("language-")
    .removePrefix("{")
    .removeSuffix("}")
    .removePrefix(".")
    .substringBefore(' ')
    .substringBefore(',')

internal fun isDiffCodeFenceLanguage(language: String): Boolean =
    normalizeCodeFenceLanguage(language) in DIFF_LANGUAGES

internal fun looksLikeUnifiedDiff(code: String): Boolean {
    var hasOldFile = false
    var hasNewFile = false
    var hasHunk = false
    var hasChange = false
    code.lineSequence().take(200).forEach { rawLine ->
        // Models often indent a patch when it is embedded in an otherwise plain
        // text response.  Ignore that presentation indentation while retaining
        // the actual +/- marker used by unified diff syntax.
        val line = rawLine.removeSuffix("\r").trimStart()
        when {
            line.startsWith("diff --git ") -> hasHunk = true
            line.startsWith("--- ") -> hasOldFile = true
            line.startsWith("+++ ") -> hasNewFile = true
            line.startsWith("@@ ") || line.startsWith("@@-") -> hasHunk = true
            line.startsWith('+') && !line.startsWith("+++") -> hasChange = true
            line.startsWith('-') && !line.startsWith("---") -> hasChange = true
        }
    }
    return hasChange && (hasHunk || (hasOldFile && hasNewFile))
}

internal fun looksLikeStandaloneUnifiedDiff(code: String): Boolean {
    val firstLine = code.lineSequence().firstOrNull { it.isNotBlank() }?.trimStart() ?: return false
    return (firstLine.startsWith("diff --git ") ||
        firstLine.startsWith("--- ") ||
        firstLine.startsWith("@@ ") ||
        firstLine.startsWith("@@-")) &&
        looksLikeUnifiedDiff(code)
}

internal fun shouldRenderDiffCodeBlock(language: String, code: String): Boolean =
    isDiffCodeFenceLanguage(language) ||
        (normalizeCodeFenceLanguage(language) in setOf("", "text", "plaintext") &&
            looksLikeUnifiedDiff(code))

@Composable
fun HighlightCodeBlock(
    code: String,
    language: String,
    modifier: Modifier = Modifier,
    completeCodeBlock: Boolean = true,
    style: TextStyle? = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
) {
    val darkMode = LocalDarkMode.current
    val colorPalette = if (darkMode) AtomOneDarkPalette else AtomOneLightPalette
    val scrollState = rememberScrollState()
    val clipboardManager = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val navController = LocalNavController.current
    val context = LocalContext.current
    val settings = LocalSettings.current
    val richContent = richContentColors()
    val colorScheme = MaterialTheme.colorScheme
    val normalizedLanguage = remember(language) { normalizeCodeFenceLanguage(language) }
    val canInlinePreview = completeCodeBlock && normalizedLanguage in PREVIEWABLE_LANGUAGES
    val canRenderMermaid = completeCodeBlock && normalizedLanguage == "mermaid"
    val canRenderDiff = shouldRenderDiffCodeBlock(normalizedLanguage, code)
    val interactiveRenderer = remember(normalizedLanguage) {
        InteractiveCodeRenderer.fromLanguage(normalizedLanguage)
    }
    val canRenderInteractive = completeCodeBlock &&
        interactiveRenderer != null &&
        canRenderInteractiveCodeBlock(normalizedLanguage, code)
    val canShowVisualPreview = canInlinePreview || canRenderMermaid || canRenderInteractive
    val renderErrorMessage = stringResource(R.string.error_message_rich_content_render)
    val fullScreenPreviewHtml = remember(
        code,
        normalizedLanguage,
        interactiveRenderer,
        canShowVisualPreview,
        colorScheme,
        renderErrorMessage,
    ) {
        when {
            !canShowVisualPreview -> null
            normalizedLanguage in PREVIEWABLE_LANGUAGES -> buildCodePreviewHtml(
                code = code,
                language = normalizedLanguage,
            )
            canRenderMermaid -> buildMermaidHtml(
                code = normalizeMermaidCode(code),
                colorScheme = colorScheme,
                renderErrorMessage = renderErrorMessage,
            )
            canRenderInteractive -> buildInteractiveRendererHtml(
                renderer = requireNotNull(interactiveRenderer),
                code = code,
                colorScheme = colorScheme,
                renderErrorMessage = renderErrorMessage,
            )
            else -> null
        }
    }
    var previewMode by remember(canShowVisualPreview, code, normalizedLanguage) {
        mutableStateOf(canShowVisualPreview)
    }

    var isExpanded by remember(settings.displaySetting.codeBlockAutoCollapse) {
        mutableStateOf(!settings.displaySetting.codeBlockAutoCollapse)
    }
    val autoWrap = settings.displaySetting.codeBlockAutoWrap
    val showLineNumbers = settings.displaySetting.showLineNumbers
    val textStyle = LocalTextStyle.current.merge(style)
    val codeLines = remember(code) { code.lines() }

    val createDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*")
    ) { uri: Uri? ->
        uri?.let {
            scope.launch {
                try {
                    context.contentResolver.openOutputStream(it)?.use { outputStream ->
                        outputStream.write(code.toByteArray())
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    Column(
        modifier = modifier
            .border(1.dp, richContent.border, MaterialTheme.shapes.large)
            .clip(MaterialTheme.shapes.large)
            .background(richContent.container),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(richContent.toolbar)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            HighlightCodeActions(
                language = language,
                scope = scope,
                clipboardManager = clipboardManager,
                code = code,
                createDocumentLauncher = createDocumentLauncher,
                navController = navController,
                completeCodeBlock = completeCodeBlock,
                previewMode = previewMode,
                canTogglePreview = canShowVisualPreview,
                fullScreenPreviewHtml = fullScreenPreviewHtml,
                onTogglePreviewMode = {
                    previewMode = !previewMode
                },
            )
        }
        Column(
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp)
        ) {
            when {
                canRenderDiff -> {
                    DiffView(
                        diff = code,
                        maxLines = if (isExpanded) Int.MAX_VALUE else COLLAPSE_LINES,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    CodeBlockExpandControl(
                        visible = settings.displaySetting.codeBlockAutoCollapse &&
                            codeLines.size > COLLAPSE_LINES,
                        expanded = isExpanded,
                        textStyle = textStyle,
                        onToggle = { isExpanded = !isExpanded },
                    )
                }
                previewMode && canInlinePreview -> {
                    val previewHeight = if (normalizedLanguage == "svg") {
                        svgPreviewHeight(remember(code) { extractSvgAspectRatio(code) })
                    } else {
                        richPreviewHeight(
                            minHeightDp = 220,
                            maxHeightDp = 360,
                            widthFraction = 0.72f,
                        )
                    }
                    CodeBlockPreview(
                        code = code,
                        language = normalizedLanguage,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(previewHeight),
                    )
                }
                previewMode && canRenderMermaid -> {
                    Mermaid(
                        code = code,
                        modifier = Modifier.fillMaxWidth(),
                        showFullScreenAction = false,
                    )
                }
                previewMode && canRenderInteractive -> {
                    InteractiveCodeBlock(
                        renderer = requireNotNull(interactiveRenderer),
                        code = code,
                    )
                }
                else -> {
                    val collapsedCode = remember(codeLines) { codeLines.take(COLLAPSE_LINES).joinToString("\n") }
                    val displayCode = if (isExpanded) code else collapsedCode
                    val displayLines = remember(displayCode) { displayCode.lines() }

                    // 如果显示行号且自动换行，需要逐行渲染以保持对齐
                    when {
                        showLineNumbers && autoWrap -> {
                            CodeBlockWithLineNumbersWrapped(
                                displayLines = displayLines,
                                language = language,
                                textStyle = textStyle,
                                colorPalette = colorPalette,
                            )
                        }
                        else -> {
                            CodeBlockDefault(
                                displayCode = displayCode,
                                displayLines = displayLines,
                                language = language,
                                textStyle = textStyle,
                                colorPalette = colorPalette,
                                autoWrap = autoWrap,
                                showLineNumbers = showLineNumbers,
                                scrollState = scrollState,
                            )
                        }
                    }

                    CodeBlockExpandControl(
                        visible = settings.displaySetting.codeBlockAutoCollapse &&
                            codeLines.size > COLLAPSE_LINES,
                        expanded = isExpanded,
                        textStyle = textStyle,
                        onToggle = { isExpanded = !isExpanded },
                    )
                }
            }
        }
    }
}

@Composable
private fun CodeBlockExpandControl(
    visible: Boolean,
    expanded: Boolean,
    textStyle: TextStyle,
    onToggle: () -> Unit,
) {
    if (!visible) return
    Spacer(Modifier.height(4.dp))
    Box(
        modifier = Modifier
            .onClick(onClick = onToggle)
            .fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (expanded) HugeIcons.ArrowUp01 else HugeIcons.ArrowDown01,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(textStyle.fontSize.toDp()),
            )
            Text(
                text = if (expanded) {
                    stringResource(id = R.string.code_block_collapse)
                } else {
                    stringResource(id = R.string.code_block_expand)
                },
                fontSize = textStyle.fontSize,
                lineHeight = textStyle.lineHeight,
            )
        }
    }
}

@Composable
private fun CodeBlockWithLineNumbersWrapped(
    displayLines: List<String>,
    language: String,
    textStyle: TextStyle,
    colorPalette: HighlightTextColorPalette,
) {
    val lineNumberWidth = remember(displayLines.size) {
        displayLines.size.toString().length
    }
    SelectionContainer {
        Column {
            displayLines.forEachIndexed { index, line ->
                Row(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = (index + 1).toString().padStart(lineNumberWidth, ' '),
                        fontSize = textStyle.fontSize,
                        lineHeight = textStyle.lineHeight,
                        fontFamily = JetbrainsMono,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        softWrap = false,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    CodeHighlightText(
                        code = line,
                        language = language,
                        fontSize = textStyle.fontSize,
                        lineHeight = textStyle.lineHeight,
                        colors = colorPalette,
                        overflow = TextOverflow.Visible,
                        softWrap = true,
                        fontFamily = JetbrainsMono,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun CodeBlockDefault(
    displayCode: String,
    displayLines: List<String>,
    language: String,
    textStyle: TextStyle,
    colorPalette: HighlightTextColorPalette,
    autoWrap: Boolean,
    showLineNumbers: Boolean,
    scrollState: ScrollState,
) {
    Row(
        modifier = Modifier.then(
            if (autoWrap) {
                Modifier
            } else {
                Modifier.horizontalScroll(scrollState)
            }
        )
    ) {
        // 行号列
        if (showLineNumbers) {
            val lineNumberWidth = remember(displayLines.size) {
                displayLines.size.toString().length
            }
            Column(
                modifier = Modifier.padding(end = 8.dp)
            ) {
                displayLines.forEachIndexed { index, _ ->
                    Text(
                        text = (index + 1).toString().padStart(lineNumberWidth, ' '),
                        fontSize = textStyle.fontSize,
                        lineHeight = textStyle.lineHeight,
                        fontFamily = JetbrainsMono,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        softWrap = false,
                    )
                }
            }
        }

        // 代码列
        SelectionContainer {
            CodeHighlightText(
                code = displayCode,
                language = language,
                modifier = Modifier.animateContentSize(),
                fontSize = textStyle.fontSize,
                lineHeight = textStyle.lineHeight,
                colors = colorPalette,
                overflow = TextOverflow.Visible,
                softWrap = autoWrap,
                fontFamily = JetbrainsMono
            )
        }
    }
}

@Composable
private fun HighlightCodeActions(
    language: String,
    scope: CoroutineScope,
    clipboardManager: Clipboard,
    code: String,
    createDocumentLauncher: ManagedActivityResultLauncher<String, Uri?>,
    navController: Navigator,
    completeCodeBlock: Boolean = true,
    previewMode: Boolean = false,
    canTogglePreview: Boolean = false,
    fullScreenPreviewHtml: String? = null,
    onTogglePreviewMode: () -> Unit = {},
) {
    val context = LocalContext.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = language,
            fontSize = 12.sp,
            lineHeight = 12.sp,
            fontFamily = JetbrainsMono,
            color = MaterialTheme.colorScheme.onSurfaceVariant
                .copy(alpha = 0.5f),
        )
        Spacer(Modifier.weight(1f))
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val iconSize = 16.dp
            val iconTint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)

            Icon(
                imageVector = HugeIcons.Download04,
                contentDescription = stringResource(id = R.string.chat_page_save),
                tint = iconTint,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .onClick {
                        val extension = when (language.lowercase()) {
                            "kotlin" -> "kt"
                            "java" -> "java"
                            "python" -> "py"
                            "javascript" -> "js"
                            "typescript" -> "ts"
                            "cpp", "c++" -> "cpp"
                            "c" -> "c"
                            "html" -> "html"
                            "css" -> "css"
                            "xml" -> "xml"
                            "json" -> "json"
                            "yaml", "yml" -> "yml"
                            "markdown", "md" -> "md"
                            "sql" -> "sql"
                            "sh", "bash" -> "sh"
                            "svg" -> "svg"
                            "diff", "patch", "udiff" -> "diff"
                            else -> "txt"
                        }
                        createDocumentLauncher.launch(
                            "code_${
                                Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
                            }.$extension"
                        )
                    }
                    .padding(4.dp)
                    .size(iconSize)
            )

            Icon(
                imageVector = HugeIcons.Copy01,
                contentDescription = stringResource(id = R.string.code_block_copy),
                tint = iconTint,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .onClick {
                        scope.launch {
                            clipboardManager.setClipEntry(ClipEntry(ClipData.newPlainText("code", code)))
                        }
                    }
                    .padding(4.dp)
                    .size(iconSize)
            )

            if (canTogglePreview) {
                Icon(
                    imageVector = if (previewMode) HugeIcons.Code else HugeIcons.View,
                    contentDescription = if (previewMode) {
                        stringResource(id = R.string.code_block_source)
                    } else {
                        stringResource(id = R.string.code_block_preview)
                    },
                    tint = iconTint,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .onClick {
                            onTogglePreviewMode()
                        }
                        .padding(4.dp)
                        .size(iconSize)
                )
            }

            if (completeCodeBlock && fullScreenPreviewHtml != null) {
                Icon(
                    imageVector = HugeIcons.Eye,
                    contentDescription = stringResource(id = R.string.code_block_preview),
                    tint = iconTint,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .onClick {
                            val contentId = WebViewContentCache.store(
                                context.cacheDir,
                                fullScreenPreviewHtml,
                            )
                            navController.navigate(Screen.WebView(contentId = contentId))
                        }
                        .padding(4.dp)
                        .size(iconSize)
                )
            }
        }
    }
}

@Composable
private fun CodeBlockPreview(
    code: String,
    language: String,
    modifier: Modifier = Modifier,
) {
    val state = rememberWebViewState(
        data = buildCodePreviewHtml(code = code, language = language),
        baseUrl = "https://rikkahub.local",
        mimeType = "text/html",
        settings = {
            builtInZoomControls = true
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
        }
    )

    WebView(
        state = state,
        deferUntilVisible = !me.rerere.rikkahub.ui.components.ui.LocalExportContext.current,
        preferParentVerticalScroll = true,
        transparentBackground = true,
        modifier = modifier.clip(RoundedCornerShape(4.dp)),
    )
}

internal fun extractSvgAspectRatio(code: String): Float? {
    val viewBox = Regex(
        pattern = """\bviewBox\s*=\s*["']([^"']+)["']""",
        option = RegexOption.IGNORE_CASE,
    ).find(code)?.groupValues?.getOrNull(1)
        ?.trim()
        ?.split(Regex("[,\\s]+"))
        ?.mapNotNull(String::toFloatOrNull)
    if (viewBox != null && viewBox.size == 4 && viewBox[2] > 0f && viewBox[3] > 0f) {
        return viewBox[2] / viewBox[3]
    }

    fun dimension(name: String): Float? = Regex(
        pattern = """\b$name\s*=\s*["']\s*([0-9]+(?:\.[0-9]+)?)(?:px)?\s*["']""",
        option = RegexOption.IGNORE_CASE,
    ).find(code)?.groupValues?.getOrNull(1)?.toFloatOrNull()

    val width = dimension("width") ?: return null
    val height = dimension("height") ?: return null
    return if (width > 0f && height > 0f) width / height else null
}

internal fun buildCodePreviewHtml(code: String, language: String): String {
    return if (language == "svg") {
        """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0, minimum-scale=0.25, maximum-scale=8.0, user-scalable=yes">
                <style>
                    html, body {
                        width: 100%;
                        min-height: 100%;
                        margin: 0;
                        overflow: auto;
                        background: transparent;
                    }
                    body {
                        display: flex;
                        justify-content: center;
                        align-items: center;
                    }
                    svg {
                        display: block;
                        max-width: 100%;
                        height: auto;
                        overflow: visible;
                    }
                </style>
            </head>
            <body>
                $code
                <script>
                    (function() {
                        const svg = document.querySelector('svg');
                        if (!svg) return;
                        svg.setAttribute('preserveAspectRatio', 'xMidYMid meet');
                        requestAnimationFrame(function() {
                            try {
                                const bounds = svg.getBBox();
                                if (!(bounds.width > 0 && bounds.height > 0)) return;
                                const current = svg.viewBox && svg.viewBox.baseVal;
                                const hasViewBox = current && current.width > 0 && current.height > 0;
                                const minX = hasViewBox ? Math.min(current.x, bounds.x) : bounds.x;
                                const minY = hasViewBox ? Math.min(current.y, bounds.y) : bounds.y;
                                const maxX = hasViewBox
                                    ? Math.max(current.x + current.width, bounds.x + bounds.width)
                                    : bounds.x + bounds.width;
                                const maxY = hasViewBox
                                    ? Math.max(current.y + current.height, bounds.y + bounds.height)
                                    : bounds.y + bounds.height;
                                const padding = Math.max(2, Math.min(maxX - minX, maxY - minY) * 0.01);
                                svg.setAttribute(
                                    'viewBox',
                                    [minX - padding, minY - padding, maxX - minX + padding * 2, maxY - minY + padding * 2].join(' ')
                                );
                                svg.removeAttribute('width');
                                svg.removeAttribute('height');
                                svg.style.width = '100%';
                                svg.style.height = 'auto';
                            } catch (error) {
                                console.warn('Unable to normalize SVG viewport', error);
                            }
                        });
                    })();
                </script>
            </body>
            </html>
        """.trimIndent()
    } else buildResponsiveHtmlPreview(code)
}

private fun buildResponsiveHtmlPreview(code: String): String {
    val document = Jsoup.parse(code)
    if (document.head().selectFirst("meta[name=viewport]") == null) {
        document.head().appendElement("meta")
            .attr("name", "viewport")
            .attr("content", "width=device-width, initial-scale=1.0, minimum-scale=0.25, maximum-scale=8.0, user-scalable=yes")
    }
    document.head().appendElement("style")
        .attr("id", "rikkahub-responsive-preview")
        .appendText(
            """
                html, body { max-width: 100%; min-height: 100%; margin: 0; overflow: auto; background: transparent; }
                img, svg, video, canvas, iframe { max-width: 100%; box-sizing: border-box; }
                img, svg, video, canvas { display: block; margin-left: auto; margin-right: auto; }
                img, svg, video { height: auto; }
                svg { overflow: visible; }
            """.trimIndent(),
        )
    return document.outerHtml()
}

class HighlightCodeVisualTransformation(
    val language: String,
    val highlighter: CodeHighlighter,
    val darkMode: Boolean
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val annotatedString = try {
            val colorPalette = if (darkMode) AtomOneDarkPalette else AtomOneLightPalette
            if (text.text.isEmpty()) {
                AnnotatedString("")
            } else {
                val tokens = highlighter.highlight(text.text, language)
                buildAnnotatedString {
                    tokens.forEach { token ->
                        buildHighlightText(token, colorPalette)
                    }
                }
            }
        } catch (e: Exception) {
            AnnotatedString(text.text)
        }

        return TransformedText(
            text = annotatedString,
            offsetMapping = OffsetMapping.Identity
        )
    }
}
