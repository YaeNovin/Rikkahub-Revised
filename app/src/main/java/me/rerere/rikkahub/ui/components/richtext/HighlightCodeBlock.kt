package me.rerere.rikkahub.ui.components.richtext

import android.content.ClipData
import android.net.Uri
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import me.rerere.highlight.CodeHighlighter
import me.rerere.highlight.rememberHighlightedCode
import me.rerere.highlight.highlightedLines
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
import me.rerere.rikkahub.ui.theme.codeHighlightPalette
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
    .substringBefore(';')
    .let {
        when (it) {
            "application/json", "text/json" -> "json"
            "application/xml", "text/xml" -> "xml"
            "image/svg+xml" -> "svg"
            "application/geo+json", "application/vnd.geo+json", "geo-json" -> "geojson"
            else -> it
        }
    }

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
    renderVisuals: Boolean = true,
) {
    androidx.compose.runtime.CompositionLocalProvider(LocalChatProseDecorationsEnabled provides false) {
        HighlightCodeBlockBody(code, language, modifier, completeCodeBlock, style, renderVisuals)
    }
}

@Composable
private fun HighlightCodeBlockBody(
    code: String,
    language: String,
    modifier: Modifier,
    completeCodeBlock: Boolean,
    style: TextStyle?,
    renderVisuals: Boolean,
) {
    val darkMode = LocalDarkMode.current
    val originalPalette = if (darkMode) AtomOneDarkPalette else AtomOneLightPalette
    val scrollState = rememberScrollState()
    val clipboardManager = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val navController = LocalNavController.current
    val context = LocalContext.current
    val settings = LocalSettings.current
    val exporting = me.rerere.rikkahub.ui.components.ui.LocalExportContext.current
    val richContent = richContentColors()
    val colorScheme = me.rerere.rikkahub.ui.theme.LocalBackgroundBaseColorScheme.current ?: MaterialTheme.colorScheme
    val codeSurface = rememberReadableTextSurface(richContent.container, originalPalette.fallback)
    val colorPalette = remember(darkMode, codeSurface.backgrounds) {
        codeHighlightPalette(darkMode, codeSurface.backgrounds)
    }
    val streaming = LocalRichTextStreaming.current
    val renderComplete = remember(language, code, completeCodeBlock, streaming) {
        completeCodeBlock || canRenderFinishedJsonFence(language, code, streaming)
    }
    val normalizedLanguage = remember(language, code, renderComplete) {
        if (renderComplete) resolveDiagramLanguage(language, code) else normalizeCodeFenceLanguage(language)
    }
    val canInlinePreview = renderComplete && normalizedLanguage in PREVIEWABLE_LANGUAGES && code.length <= MAX_STATIC_PREVIEW_CHARS
    val canRenderMermaid = renderComplete && normalizedLanguage == "mermaid"
    val canRenderDiff = renderVisuals && shouldRenderDiffCodeBlock(normalizedLanguage, code)
    val canRenderTerminal = canRenderTerminalPreview(normalizedLanguage, code, renderComplete)
    val geometry = geometryRenderer(normalizedLanguage, code)
    val canRenderGeometry = canRenderGeometryPreview(normalizedLanguage, code, renderComplete)
    val interactiveRenderer = remember(normalizedLanguage) {
        InteractiveCodeRenderer.fromLanguage(normalizedLanguage)
    }
    val canRenderInteractive = renderComplete &&
        interactiveRenderer != null &&
        canRenderInteractiveCodeBlock(normalizedLanguage, code)
    val canShowVisualPreview = renderVisuals && (canInlinePreview || canRenderMermaid || canRenderInteractive || canRenderTerminal || canRenderGeometry)
    val badgeDocument = rememberMarkdownFenceBadges(code, renderVisuals && renderComplete && isMarkdownBadgeFence(normalizedLanguage))
    val canRenderBadges = badgeDocument?.badges?.isNotEmpty() == true
    val canTogglePreview = canShowVisualPreview || canRenderBadges
    val renderErrorMessage = stringResource(R.string.error_message_rich_content_render)
    val fullScreenPreviewHtml = remember(
        code,
        normalizedLanguage,
        interactiveRenderer,
        canShowVisualPreview,
        colorScheme,
        renderErrorMessage,
        darkMode,
    ) {
        when {
            !canShowVisualPreview -> null
            canRenderGeometry -> buildGeometryPreviewHtml(requireNotNull(geometry), code, darkMode)
            canRenderTerminal -> buildTerminalPreviewHtml(code, darkMode)
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
    // HTML consent is per content and ephemeral: streaming edits/reopens cannot reuse it.
    var previewMode by if (!codePreviewDefaultsToRendered(normalizedLanguage)) {
        remember(canTogglePreview, code) { mutableStateOf(false) }
    } else {
        androidx.compose.runtime.saveable.rememberSaveable(canTogglePreview, normalizedLanguage) {
            mutableStateOf(canTogglePreview)
        }
    }

    var isExpanded by androidx.compose.runtime.saveable.rememberSaveable(settings.displaySetting.codeBlockAutoCollapse) {
        mutableStateOf(exporting || !settings.displaySetting.codeBlockAutoCollapse)
    }
    // The header is painted over the configured code surface, except decorative previews
    // whose outer container is transparent. Contrast correction must never paint a backing.
    val toolbarSurface = rememberReadableTextSurface(
        richContent.toolbar,
        MaterialTheme.colorScheme.onSurfaceVariant,
        underlays = if (previewMode && canShowVisualPreview && canRenderGeometry) null else codeSurface.backgrounds,
    )
    val autoWrap = exporting || settings.displaySetting.codeBlockAutoWrap
    val showLineNumbers = settings.displaySetting.showLineNumbers
    val textStyle = LocalTextStyle.current.merge(style)
    val codeLines = remember(code) { code.lines() }
    val sourceHighlightLanguage = remember(language, code) { rendererSourceHighlightLanguage(language, code) }
    val sourceHighlightLimit = remember(language, code.length) { rendererSourceHighlightLimit(language, code.length) }

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

    if (previewMode && canShowVisualPreview) {
        var previewView by remember { androidx.compose.runtime.mutableStateOf<android.webkit.WebView?>(null) }
        Column(modifier.fillMaxWidth()
            .then(if (canRenderGeometry) Modifier else Modifier.border(1.dp, richContent.border, MaterialTheme.shapes.large))
            .clip(MaterialTheme.shapes.large)
            .background(if (canRenderGeometry) androidx.compose.ui.graphics.Color.Transparent else richContent.container)) {
            Box(Modifier.fillMaxWidth().background(richContent.toolbar).padding(horizontal = 12.dp, vertical = 8.dp)) {
                HighlightCodeActions(
                    language = language, scope = scope, clipboardManager = clipboardManager,
                    code = code, createDocumentLauncher = createDocumentLauncher, navController = navController,
                    completeCodeBlock = renderComplete, previewMode = true, canTogglePreview = true,
                    fullScreenPreviewHtml = null,
                    onTogglePreviewMode = { previewMode = false },
                    contentColor = toolbarSurface.foreground,
                )
            }
            Column(Modifier.fillMaxWidth().padding(if (canRenderGeometry) 0.dp else 12.dp)) {
                when {
                    canRenderGeometry -> GeometryCodePreview(fullScreenPreviewHtml.orEmpty(), onViewCreated = { previewView = it })
                    canRenderTerminal -> TerminalCodePreview(fullScreenPreviewHtml.orEmpty(), onViewCreated = { previewView = it })
                    canRenderMermaid -> Mermaid(code, Modifier.fillMaxWidth(), showFullScreenAction = true)
                    canRenderInteractive -> InteractiveCodeBlock(requireNotNull(interactiveRenderer), code, onViewCreated = { previewView = it })
                    else -> CodeBlockPreview(code, normalizedLanguage, Modifier.fillMaxWidth(), onViewCreated = { previewView = it })
                }
                if (!canRenderMermaid && !exporting) GraphPreviewActions(fullScreenPreviewHtml.orEmpty(), previewView)
            }
        }
        return
    }
    androidx.compose.runtime.CompositionLocalProvider(
        LocalTextSurfaceBackgrounds provides codeSurface.backgrounds,
        androidx.compose.material3.LocalContentColor provides codeSurface.foreground,
    ) {
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
                completeCodeBlock = renderComplete,
                previewMode = previewMode,
                canTogglePreview = canTogglePreview,
                fullScreenPreviewHtml = fullScreenPreviewHtml,
                onTogglePreviewMode = {
                    previewMode = !previewMode
                },
                contentColor = toolbarSurface.foreground,
            )
        }
        Column(
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp)
        ) {
            when {
                previewMode && canRenderBadges -> {
                    val highlighted = rememberHighlightedCode(code, sourceHighlightLanguage, colorPalette, sourceHighlightLimit)
                    MarkdownBadgeCodeRows(requireNotNull(badgeDocument), highlighted,
                        if (isExpanded || exporting) Int.MAX_VALUE else COLLAPSE_LINES,
                        showLineNumbers, autoWrap, scrollState, textStyle, colorPalette)
                    CodeBlockExpandControl(
                        visible = !exporting && settings.displaySetting.codeBlockAutoCollapse && codeLines.size > COLLAPSE_LINES,
                        expanded = isExpanded, textStyle = textStyle, onToggle = { isExpanded = !isExpanded },
                    )
                }
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
                            .fillMaxWidth(),
                        previewHeight = previewHeight,
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
                    val highlighted = rememberHighlightedCode(code, sourceHighlightLanguage, colorPalette, sourceHighlightLimit)
                    val highlightedRows = remember(highlighted, isExpanded) {
                        highlighted.highlightedLines().let { if (isExpanded) it else it.take(COLLAPSE_LINES) }
                    }
                    val highlightedDisplay = remember(highlightedRows) {
                        buildAnnotatedString {
                            highlightedRows.forEachIndexed { index, row ->
                                if (index > 0) append('\n')
                                append(row)
                            }
                        }
                    }

                    // 如果显示行号且自动换行，需要逐行渲染以保持对齐
                    when {
                        showLineNumbers && autoWrap -> {
                            CodeBlockWithLineNumbersWrapped(
                                displayLines = highlightedRows,
                                textStyle = textStyle,
                                colorPalette = colorPalette,
                            )
                        }
                        else -> {
                            CodeBlockDefault(
                                displayCode = highlightedDisplay,
                                displayLines = displayLines,
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
                tint = androidx.compose.material3.LocalContentColor.current,
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
    displayLines: List<AnnotatedString>,
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
                        color = colorPalette.comment,
                        softWrap = false,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Text(
                        text = line,
                        fontSize = textStyle.fontSize,
                        lineHeight = textStyle.lineHeight,
                        color = colorPalette.fallback,
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
    displayCode: AnnotatedString,
    displayLines: List<String>,
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
            val lineNumbers = remember(displayLines.size, lineNumberWidth) {
                (1..displayLines.size).joinToString("\n") { it.toString().padStart(lineNumberWidth, ' ') }
            }
            // A single layout shares the exact line height with the code. One
            // Text per number multiplies composition and measurement for long output.
            androidx.compose.foundation.text.selection.DisableSelection {
                Text(
                    text = lineNumbers,
                    modifier = Modifier.padding(end = 8.dp),
                    fontSize = textStyle.fontSize,
                    lineHeight = textStyle.lineHeight,
                    fontFamily = JetbrainsMono,
                    color = colorPalette.comment,
                    softWrap = false,
                )
            }
        }

        // 代码列
        SelectionContainer {
            Text(
                text = displayCode,
                fontSize = textStyle.fontSize,
                lineHeight = textStyle.lineHeight,
                color = colorPalette.fallback,
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
    contentColor: androidx.compose.ui.graphics.Color = androidx.compose.material3.LocalContentColor.current,
) {
    val openPreview = me.rerere.rikkahub.ui.components.webview.rememberWebPreviewLauncher()
    val context = LocalContext.current
    if (me.rerere.rikkahub.ui.components.ui.LocalExportContext.current) {
        Text(language, fontSize = 12.sp, fontFamily = JetbrainsMono, color = contentColor)
        return
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = when {
                isTerminalFence(language) -> "$language · 只读展示"
                normalizeCodeFenceLanguage(language) == "html" -> "$language · 手动静态预览"
                else -> language
            },
            fontSize = 12.sp,
            lineHeight = 12.sp,
            fontFamily = JetbrainsMono,
            color = contentColor,
        )
        Spacer(Modifier.weight(1f))
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val iconSize = 16.dp
            val iconTint = contentColor

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
                            openPreview { fullScreenPreviewHtml }
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
    onViewCreated: (android.webkit.WebView) -> Unit = {},
    previewHeight: androidx.compose.ui.unit.Dp = 200.dp,
) {
    val state = rememberWebViewState(
        data = remember(code, language) { buildCodePreviewHtml(code = code, language = language) },
        baseUrl = "https://rikkahub.local",
        mimeType = "text/html",
        settings = {
            blockNetworkLoads = true
            domStorageEnabled = false
            builtInZoomControls = true
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
        }
    )

    WebView(
        state = state,
        onCreated = onViewCreated,
        deferUntilVisible = !me.rerere.rikkahub.ui.components.ui.LocalExportContext.current,
        preferParentVerticalScroll = true,
        transparentBackground = true,
        modifier = modifier.clip(RoundedCornerShape(4.dp)),
        previewHeight = previewHeight,
    )
}

internal fun extractSvgAspectRatio(code: String): Float? {
    // Read dimensions from the root SVG only; nested rect/image sizes are unrelated.
    val svg = Jsoup.parse(code).selectFirst("svg") ?: return null
    val parts = svg.attr("viewBox").trim().split(Regex("[,\\s]+"))
    val numbers = parts.map { it.toFloatOrNull() }
    if (numbers.size == 4 && numbers.all { it != null && it.isFinite() }) {
        val width = requireNotNull(numbers[2])
        val height = requireNotNull(numbers[3])
        if (width > 0 && height > 0) return (width / height).takeIf { it.isFinite() && it > 0 }
    }
    fun dimension(name: String): Float? = svg.attr(name).trim().removeSuffix("px")
        .toFloatOrNull()?.takeIf { it.isFinite() && it > 0 }
    val width = dimension("width") ?: return null
    val height = dimension("height") ?: return null
    return (width / height).takeIf { it.isFinite() && it > 0 }
}

internal fun buildCodePreviewHtml(code: String, language: String): String =
    buildStaticHtmlPreview(code, svgOnly = normalizeCodeFenceLanguage(language) == "svg")

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
