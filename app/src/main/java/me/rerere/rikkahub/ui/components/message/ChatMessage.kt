package me.rerere.rikkahub.ui.components.message

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.util.fastAll
import androidx.compose.ui.util.fastForEach
import androidx.compose.ui.util.fastForEachIndexed
import androidx.core.content.FileProvider
import androidx.core.net.toFile
import androidx.core.net.toUri
import kotlinx.coroutines.FlowPreview
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.blur.material3.Material3
import kotlinx.coroutines.flow.debounce
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.isEmptyUIMessage
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.MusicNote03
import me.rerere.hugeicons.stroke.Video01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.datastore.ChatBubbleStyle
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.hasActiveChatBackground
import me.rerere.rikkahub.data.datastore.isEnhancedChatBubbleActive
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.replaceRegexes
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.components.richtext.ZoomableAsyncImage
import me.rerere.rikkahub.ui.components.richtext.buildMarkdownPreviewHtml
import me.rerere.rikkahub.ui.components.webview.WebViewContentCache
import me.rerere.rikkahub.ui.components.ui.ChainOfThought
import me.rerere.rikkahub.ui.components.ui.Favicon
import me.rerere.rikkahub.ui.components.ui.LocalAdvancedAppearanceCapabilities
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.modifier.shimmer
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.theme.LocalChatBackgroundForeground
import me.rerere.rikkahub.ui.theme.LocalChatFontFamily
import me.rerere.rikkahub.ui.theme.rememberChatFontFamily
import me.rerere.rikkahub.ui.theme.resolveChatBodyTextStyle
import me.rerere.rikkahub.ui.theme.extendColors
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.openUrl
import me.rerere.rikkahub.utils.urlDecode
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun ChatMessage(
    node: MessageNode,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    model: Model? = null,
    assistant: Assistant? = null,
    hazeState: HazeState? = null,
    lastMessage: Boolean = false,
    onFork: () -> Unit,
    onRegenerate: () -> Unit,
    onEdit: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onUpdate: (MessageNode) -> Unit,
    isFavorite: Boolean = false,
    onToggleFavorite: (() -> Unit)? = null,
    onTranslate: ((UIMessage, Locale) -> Unit)? = null,
    onClearTranslation: (UIMessage) -> Unit = {},
    onToolApproval: ((toolCallId: String, approved: Boolean, reason: String) -> Unit)? = null,
    onToolAnswer: ((toolCallId: String, answer: String) -> Unit)? = null,
) {
    val message = node.messages[node.selectIndex]
    val appSettings = LocalSettings.current
    val settings = appSettings.displaySetting
    val chatBackgroundActive = appSettings.hasActiveChatBackground()
    val chatBackgroundForeground = LocalChatBackgroundForeground.current
        .takeUnless { it == Color.Unspecified }
        ?: MaterialTheme.colorScheme.onSurface
    val chatFontFamily = LocalChatFontFamily.current ?: rememberChatFontFamily(settings)
    val textStyle = resolveChatBodyTextStyle(
        baseStyle = MaterialTheme.typography.bodyLarge,
        fontSizeRatio = settings.fontSizeRatio,
        fontFamily = chatFontFamily,
        color = if (chatBackgroundActive) {
            chatBackgroundForeground.copy(alpha = 0.96f)
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        appearance = appSettings.advancedAppearanceSetting,
    )
    var showActionsSheet by remember { mutableStateOf(false) }
    var showSelectCopySheet by remember { mutableStateOf(false) }
    val navController = LocalNavController.current
    val context = LocalContext.current
    val colorScheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = if (message.role == MessageRole.USER) Alignment.End else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (!message.parts.isEmptyUIMessage()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                ChatMessageAssistantAvatar(
                    message = message,
                    model = model,
                    assistant = assistant,
                    loading = loading,
                    modifier = Modifier.weight(1f)
                )
                ChatMessageUserAvatar(
                    message = message,
                    avatar = settings.userAvatar,
                    nickname = settings.userNickname,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        CompositionLocalProvider(LocalContentColor provides textStyle.color) {
            ProvideTextStyle(textStyle) {
                MessagePartsBlock(
                    assistant = assistant,
                    role = message.role,
                    parts = message.parts,
                    annotations = message.annotations,
                    loading = loading,
                    model = model,
                    hazeState = hazeState,
                    onToolApproval = onToolApproval,
                    onToolAnswer = onToolAnswer,
                    onUserMessageClick = if (message.role == MessageRole.USER) onEdit else null,
                )

                message.translation?.let { translation ->
                    CollapsibleTranslationText(
                        content = translation,
                        onClickCitation = {}
                    )
                }
            }
        }

        val showActions = if (lastMessage) {
            !loading
        } else {
            message.parts.isEmptyUIMessage().not()
        }

        AnimatedVisibility(
            visible = showActions,
            enter = slideInVertically { it / 2 } + fadeIn(),
            exit = slideOutVertically { it / 2 } + fadeOut()
        ) {
            Column(
                modifier = Modifier.animateContentSize()
            ) {
                ChatMessageActionButtons(
                    message = message,
                    onRegenerate = onRegenerate,
                    node = node,
                    onUpdate = onUpdate,
                    onOpenActionSheet = {
                        showActionsSheet = true
                    },
                    onTranslate = onTranslate,
                    onClearTranslation = onClearTranslation
                )
            }
        }

        EditedFilesList(
            parts = message.parts,
            assistant = assistant,
        )

        ProvideTextStyle(textStyle) {
            ChatMessageNerdLine(
                message = message,
                color = if (chatBackgroundActive) {
                    chatBackgroundForeground.copy(alpha = 0.82f)
                } else {
                    MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f)
                },
            )
        }

    }
    if (showActionsSheet) {
        ChatMessageActionsSheet(
            message = message,
            onEdit = onEdit,
            onDelete = onDelete,
            onShare = onShare,
            onFork = onFork,
            model = model,
            onSelectAndCopy = {
                showSelectCopySheet = true
            },
            isFavorite = isFavorite,
            onToggleFavorite = onToggleFavorite,
            onWebViewPreview = {
                val textContent = message.parts
                    .filterIsInstance<UIMessagePart.Text>()
                    .joinToString("\n\n") { it.text }
                    .trim()
                if (textContent.isNotBlank()) {
                    val htmlContent = buildMarkdownPreviewHtml(
                        context = context,
                        markdown = textContent,
                        colorScheme = colorScheme
                    )
                    val contentId = WebViewContentCache.store(context.cacheDir, htmlContent)
                    navController.navigate(Screen.WebView(contentId = contentId))
                }
            },
            onDismissRequest = {
                showActionsSheet = false
            }
        )
    }

    if (showSelectCopySheet) {
        ChatMessageCopySheet(
            message = message,
            onDismissRequest = {
                showSelectCopySheet = false
            }
        )
    }
}

private data class MessageBubbleAppearance(
    val modifier: Modifier,
    val color: Color,
    val border: BorderStroke?,
    val shadowElevation: Dp,
)

private const val MAX_LIVE_BLUR_TEXT_LENGTH = 800
private const val MAX_LIVE_BLUR_LINE_COUNT = 10

internal fun canUseLiveChatBubbleBlur(isUser: Boolean, text: String): Boolean {
    if (!isUser || text.length > MAX_LIVE_BLUR_TEXT_LENGTH) return false
    if (text.count { it == '\n' } + 1 > MAX_LIVE_BLUR_LINE_COUNT) return false
    return !text.contains("```") &&
        !text.contains("<svg", ignoreCase = true) &&
        !text.contains("<table", ignoreCase = true)
}

internal fun shouldRenderAssistantMessageBubble(settings: Settings): Boolean =
    settings.displaySetting.showAssistantBubble

@Composable
private fun messageBubbleAppearance(
    settings: Settings,
    isUser: Boolean,
    hazeState: HazeState?,
    allowLiveBlur: Boolean,
): MessageBubbleAppearance {
    val opacity = settings.displaySetting.bubbleOpacity.coerceIn(0f, 1f)
    val baseColor = if (isUser) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    if (!settings.isEnhancedChatBubbleActive()) {
        return MessageBubbleAppearance(
            modifier = Modifier,
            color = baseColor.copy(alpha = opacity),
            border = null,
            shadowElevation = 0.dp,
        )
    }

    val capabilities = LocalAdvancedAppearanceCapabilities.current
    val style = capabilities.effectiveBubbleStyle(
        settings.advancedAppearanceSetting.chatBubbleStyle
    )
    val needsLiveBlur = hazeState != null &&
        capabilities.supportsRealtimeBlur &&
        settings.hasActiveChatBackground() &&
        allowLiveBlur &&
        style != ChatBubbleStyle.OUTLINED
    val modifier = if (needsLiveBlur) {
        val blurRadius = capabilities.limitLiveBlur(
            when (style) {
                ChatBubbleStyle.FROSTED -> 18f
                ChatBubbleStyle.LIQUID_GLASS -> 24f
                ChatBubbleStyle.OUTLINED -> 0f
            }
        ).dp
        Modifier.hazeBlur(
            input = HazeInput.Sources(requireNotNull(hazeState)),
            style = HazeBlurStyle.Material3 {
                blurRadius(blurRadius)
            },
        )
    } else {
        Modifier
    }

    return when (style) {
        ChatBubbleStyle.FROSTED -> MessageBubbleAppearance(
            modifier = modifier,
            color = baseColor.copy(alpha = (opacity * 0.78f).coerceIn(0.46f, 0.82f)),
            border = BorderStroke(
                width = 0.75.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f),
            ),
            shadowElevation = 0.dp,
        )

        ChatBubbleStyle.OUTLINED -> MessageBubbleAppearance(
            modifier = Modifier,
            color = baseColor.copy(alpha = (opacity * 0.24f).coerceIn(0.16f, 0.28f)),
            border = BorderStroke(
                width = 1.dp,
                color = if (isUser) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.58f)
                } else {
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.62f)
                },
            ),
            shadowElevation = 0.dp,
        )

        ChatBubbleStyle.LIQUID_GLASS -> MessageBubbleAppearance(
            modifier = modifier,
            color = baseColor.copy(
                alpha = (opacity * 0.78f).coerceIn(
                    if (isUser) 0.66f else 0.72f,
                    0.86f,
                )
            ),
            border = BorderStroke(
                width = 1.dp,
                brush = Brush.linearGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.42f),
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.18f),
                    )
                ),
            ),
            shadowElevation = 2.dp,
        )
    }
}

@OptIn(FlowPreview::class)
@Composable
private fun MessagePartsBlock(
    assistant: Assistant?,
    role: MessageRole,
    model: Model?,
    hazeState: HazeState?,
    parts: List<UIMessagePart>,
    annotations: List<UIMessageAnnotation>,
    loading: Boolean,
    onToolApproval: ((toolCallId: String, approved: Boolean, reason: String) -> Unit)? = null,
    onToolAnswer: ((toolCallId: String, answer: String) -> Unit)? = null,
    onUserMessageClick: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val settings = LocalSettings.current
    val contentColor = if (settings.hasActiveChatBackground()) {
        LocalChatBackgroundForeground.current
            .takeUnless { it == Color.Unspecified }
            ?.copy(alpha = 0.90f)
            ?: MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
    }

    // 消息输出HapticFeedback
    val hapticFeedback = LocalHapticFeedback.current
    val partsState by rememberUpdatedState(parts)

    val handleClickCitation: (String) -> Unit = remember {
        handler@{ citationId ->
            partsState.forEach { part ->
                if (part is UIMessagePart.Tool && part.toolName == "search_web" && part.isExecuted) {
                    val outputText = part.output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
                    val items =
                        runCatching { JsonInstant.parseToJsonElement(outputText).jsonObject["items"]?.jsonArray }.getOrNull()
                            ?: return@forEach
                    items.forEach { item ->
                        val id = item.jsonObject["id"]?.jsonPrimitive?.content ?: return@forEach
                        val url = item.jsonObject["url"]?.jsonPrimitive?.content ?: return@forEach
                        if (citationId == id) {
                            context.openUrl(url)
                            return@handler
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(settings.displaySetting) {
        snapshotFlow { partsState }
            .debounce(50.milliseconds)
            .collect { parts ->
                if (parts.isNotEmpty() && loading && settings.displaySetting.enableMessageGenerationHapticEffect) {
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.KeyboardTap)
                }
            }
    }

    // Render parts in original order (group thinking/tool as chain-of-thought)
    val groupedParts = remember(parts) { parts.groupMessageParts() }
    groupedParts.fastForEach { block ->
        when (block) {
            is MessagePartBlock.ThinkingBlock -> {
                if (block.steps.isNotEmpty()) {
                    val isReasoningOnlyBlock = block.steps.fastAll { it is ThinkingStep.ReasoningStep }
                    ChainOfThought(
                        modifier = Modifier.animateContentSize(),
                        steps = block.steps,
                        collapsedAdaptiveWidth = isReasoningOnlyBlock,
                        cardColors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = settings.displaySetting.bubbleOpacity),
                        ),
                    ) { step ->
                        when (step) {
                            is ThinkingStep.ReasoningStep -> {
                                key(step.reasoning.createdAt) {
                                    ChatMessageReasoningStep(
                                        reasoning = step.reasoning,
                                        model = model,
                                        assistant = assistant,
                                        collapsedAdaptiveWidth = isReasoningOnlyBlock,
                                    )
                                }
                            }

                            is ThinkingStep.ToolStep -> {
                                key(step.tool.toolCallId.ifBlank { step.hashCode().toString() }) {
                                    ChatMessageToolStep(
                                        tool = step.tool,
                                        loading = loading && !step.tool.isExecuted,
                                        onToolApproval = onToolApproval,
                                        onToolAnswer = onToolAnswer,
                                    )
                                }
                            }

                            is ThinkingStep.ServerToolStep -> {
                                key(step.tool.toolCallId.ifBlank { step.hashCode().toString() }) {
                                    ChatMessageServerToolStep(tool = step.tool)
                                }
                            }
                        }
                    }
                }
            }

            is MessagePartBlock.ContentBlock -> key(block.index) {
                when (val part = block.part) {
                    is UIMessagePart.Text -> {
                        val isUserMessage = role == MessageRole.USER
                        val bubbleAppearance = messageBubbleAppearance(
                            settings = settings,
                            isUser = isUserMessage,
                            hazeState = hazeState,
                            allowLiveBlur = canUseLiveChatBubbleBlur(
                                isUser = isUserMessage,
                                text = part.text,
                            ),
                        )
                        val textContent = @Composable {
                            if (isUserMessage) {
                                Surface(
                                    modifier = bubbleAppearance.modifier.then(
                                        if (loading) Modifier else Modifier.animateContentSize()
                                    ),
                                    shape = RoundedCornerShape(16.dp),
                                    color = bubbleAppearance.color,
                                    border = bubbleAppearance.border,
                                    shadowElevation = bubbleAppearance.shadowElevation,
                                    onClick = { onUserMessageClick?.invoke() },
                                ) {
                                    Column(
                                        modifier = Modifier.padding(
                                            horizontal = 12.dp,
                                            vertical = 10.dp,
                                        )
                                    ) {
                                        MarkdownBlock(
                                            content = part.text.replaceRegexes(
                                                assistant = assistant,
                                                scope = AssistantAffectScope.USER,
                                                visual = true,
                                            ),
                                            onClickCitation = handleClickCitation
                                        )
                                    }
                                }
                            } else {
                                if (shouldRenderAssistantMessageBubble(settings)) {
                                    Surface(
                                        modifier = bubbleAppearance.modifier.then(
                                            if (loading) Modifier else Modifier.animateContentSize()
                                        ),
                                        shape = RoundedCornerShape(16.dp),
                                        color = bubbleAppearance.color,
                                        border = bubbleAppearance.border,
                                        shadowElevation = bubbleAppearance.shadowElevation,
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(
                                                horizontal = 12.dp,
                                                vertical = 10.dp,
                                            )
                                        ) {
                                            MarkdownBlock(
                                                content = part.text.replaceRegexes(
                                                    assistant = assistant,
                                                    scope = AssistantAffectScope.ASSISTANT,
                                                    visual = true,
                                                ),
                                                onClickCitation = handleClickCitation,
                                            )
                                        }
                                    }
                                } else {
                                    MarkdownBlock(
                                        content = part.text.replaceRegexes(
                                            assistant = assistant,
                                            scope = AssistantAffectScope.ASSISTANT,
                                            visual = true,
                                        ),
                                        onClickCitation = handleClickCitation,
                                        modifier = if (loading) Modifier else Modifier.animateContentSize(),
                                    )
                                }
                            }
                        }

                        // 流式生成期间不启用 SelectionContainer：Markdown 在不断重渲染，
                        // 内部可选择的 Text 会频繁注册/注销，与 Compose 选择工具栏在绘制阶段
                        // 对 selectable 列表的排序产生并发修改，导致 ConcurrentModificationException。
                        // 生成结束后内容稳定，再启用文本选择。
                        if (loading) {
                            textContent()
                        } else {
                            SelectionContainer {
                                textContent()
                            }
                        }
                    }

                    is UIMessagePart.Video -> {
                        Surface(
                            tonalElevation = 2.dp,
                            onClick = {
                                val intent = Intent(Intent.ACTION_VIEW)
                                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                intent.data = FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    part.url.toUri().toFile()
                                )
                                val chooserIndent = Intent.createChooser(intent, null)
                                context.startActivity(chooserIndent)
                            },
                            modifier = Modifier,
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Box(modifier = Modifier.size(72.dp), contentAlignment = Alignment.Center) {
                                Icon(HugeIcons.Video01, null)
                            }
                        }
                    }

                    is UIMessagePart.Audio -> {
                        Surface(
                            tonalElevation = 2.dp,
                            onClick = {
                                val intent = Intent(Intent.ACTION_VIEW)
                                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                intent.data = FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    part.url.toUri().toFile()
                                )
                                val chooserIndent = Intent.createChooser(intent, null)
                                context.startActivity(chooserIndent)
                            },
                            modifier = Modifier,
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            ProvideTextStyle(MaterialTheme.typography.labelSmall) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = HugeIcons.MusicNote03,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }

                    is UIMessagePart.Image -> {
                        val isImageLoading =
                            part.url.isBlank() || part.url.matches(Regex("^data:image/[^;]*;base64,\\s*$"))
                        if (isImageLoading) {
                            Box(
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(MaterialTheme.shapes.medium)
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .shimmer(isLoading = true)
                            )
                        } else {
                            ZoomableAsyncImage(
                                model = part.url,
                                contentDescription = null,
                                modifier = Modifier
                                    .clip(MaterialTheme.shapes.medium)
                                    .height(72.dp)
                            )
                        }
                    }

                    is UIMessagePart.Document -> {
                        Surface(
                            tonalElevation = 2.dp,
                            onClick = {
                                val intent = Intent(Intent.ACTION_VIEW)
                                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                intent.data = FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    part.url.toUri().toFile()
                                )
                                val chooserIndent = Intent.createChooser(intent, null)
                                context.startActivity(chooserIndent)
                            },
                            modifier = Modifier,
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.tertiaryContainer
                        ) {
                            ProvideTextStyle(MaterialTheme.typography.labelSmall) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    when (part.mime) {
                                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> {
                                            Icon(
                                                painter = painterResource(R.drawable.docx),
                                                contentDescription = null,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }

                                        "application/pdf" -> {
                                            Icon(
                                                painter = painterResource(R.drawable.pdf),
                                                contentDescription = null,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }

                                        else -> {
                                            Icon(
                                                imageVector = HugeIcons.File02,
                                                contentDescription = null,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }

                                    Text(
                                        text = part.fileName,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.widthIn(max = 200.dp)
                                    )
                                }
                            }
                        }
                    }

                    else -> {
                        // Skip unknown part types (e.g., deprecated ToolCall, ToolResult, Search)
                    }
                }
            }
        }
    }

    // Annotations (always rendered at the end)
    if (annotations.isNotEmpty()) {
        Column(
            modifier = Modifier.animateContentSize(),
        ) {
            var expand by remember(annotations) {
                mutableStateOf(annotations.any { it is UIMessageAnnotation.KnowledgeCitation })
            }
            if (expand) {
                ProvideTextStyle(
                    MaterialTheme.typography.labelMedium.copy(
                        color = MaterialTheme.extendColors.gray8.copy(alpha = 0.65f)
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .drawWithContent {
                                drawContent()
                                drawRoundRect(
                                    color = contentColor.copy(alpha = 0.2f),
                                    size = Size(width = 10f, height = size.height),
                                )
                            }
                            .padding(start = 16.dp)
                            .padding(4.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        annotations.fastForEachIndexed { index, annotation ->
                            when (annotation) {
                                is UIMessageAnnotation.UrlCitation -> {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Favicon(annotation.url, modifier = Modifier.size(20.dp))
                                        Text(
                                            text = buildAnnotatedString {
                                                append("${index + 1}. ")
                                                withLink(LinkAnnotation.Url(annotation.url)) {
                                                    append(annotation.title.urlDecode())
                                                }
                                            }
                                        )
                                    }
                                }

                                is UIMessageAnnotation.KnowledgeCitation -> {
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = CardDefaults.cardColors(
                                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                        ),
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(10.dp),
                                            verticalArrangement = Arrangement.spacedBy(4.dp),
                                        ) {
                                            Text(
                                                text = "${index + 1}. ${annotation.title}",
                                                style = MaterialTheme.typography.titleSmall,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            val location = buildString {
                                                annotation.pageStart?.let { start ->
                                                    append("Page $start")
                                                    annotation.pageEnd?.takeIf { it != start }?.let { append("-$it") }
                                                }
                                                if (annotation.sectionPath.isNotBlank()) {
                                                    if (isNotEmpty()) append(" · ")
                                                    append(annotation.sectionPath)
                                                }
                                            }
                                            if (location.isNotBlank()) {
                                                Text(
                                                    text = location,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                            Text(
                                                text = annotation.excerpt,
                                                style = MaterialTheme.typography.bodySmall,
                                                maxLines = 4,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            TextButton(
                onClick = {
                    expand = !expand
                }
            ) {
                Text(stringResource(R.string.citations_count, annotations.size))
            }
        }
    }
}
