package me.rerere.rikkahub.ui.components.message

import android.content.Intent
import android.widget.Toast
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.util.fastAll
import androidx.compose.ui.util.fastForEach
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
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
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
import me.rerere.rikkahub.ui.components.richtext.MarkdownRenderMode
import me.rerere.rikkahub.ui.components.richtext.ZoomableAsyncImage
import me.rerere.rikkahub.ui.components.richtext.buildMarkdownPreviewHtml
import me.rerere.rikkahub.ui.components.webview.WebViewContentCache
import me.rerere.rikkahub.ui.components.message.tools.RenderingToolContent
import me.rerere.rikkahub.ui.components.ui.ChainOfThought
import me.rerere.rikkahub.ui.components.ui.LocalAdvancedAppearanceCapabilities
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.modifier.shimmer
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.theme.LocalChatBackgroundForeground
import me.rerere.rikkahub.ui.theme.LocalChatFontFamily
import me.rerere.rikkahub.ui.theme.rememberChatFontFamily
import me.rerere.rikkahub.ui.theme.resolveChatBodyTextStyle
import me.rerere.rikkahub.data.ai.tools.searchWebCitations
import me.rerere.rikkahub.utils.webLinkUrl
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
    showActions: Boolean = true,
    onFork: () -> Unit,
    forkEnabled: Boolean = true,
    forkInProgress: Boolean = false,
    onRegenerate: () -> Unit,
    onContinue: (() -> Unit)? = null,
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
    onToolCancel: ((toolCallId: String, reason: String) -> Unit)? = null,
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
            chatBackgroundForeground
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        appearance = appSettings.advancedAppearanceSetting,
    )
    var showActionsSheet by remember { mutableStateOf(false) }
    var showSelectCopySheet by remember { mutableStateOf(false) }
    val navController = LocalNavController.current
    val context = LocalContext.current
    val openWebPreview = me.rerere.rikkahub.ui.components.webview.rememberWebPreviewLauncher()
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
        CompositionLocalProvider(
            LocalContentColor provides textStyle.color,
            me.rerere.rikkahub.ui.components.richtext.LocalRichTextStreaming provides loading,
        ) {
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
                    onToolCancel = onToolCancel,
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

        val actionsVisible = showActions && if (lastMessage) {
            !loading
        } else {
            message.parts.isEmptyUIMessage().not()
        }

        AnimatedVisibility(
            visible = actionsVisible,
            enter = slideInVertically { it / 2 } + fadeIn(),
            exit = slideOutVertically { it / 2 } + fadeOut()
        ) {
            Column(
                modifier = Modifier.animateContentSize()
            ) {
                ChatMessageActionButtons(
                    message = message,
                    onRegenerate = onRegenerate,
                    onContinue = onContinue,
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
            forkEnabled = forkEnabled,
            forkInProgress = forkInProgress,
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
                    openWebPreview { buildMarkdownPreviewHtml(
                        context = context,
                        markdown = textContent,
                        colorScheme = colorScheme
                    ) }
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
            style = me.rerere.rikkahub.ui.components.ui.backgroundOnlyBlurStyle(blurRadius.value),
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
            border = me.rerere.rikkahub.ui.components.ui.liquidGlassBorder(strength = .6f),
            shadowElevation = 2.dp,
        )
    }
}

@OptIn(FlowPreview::class)
@Composable
internal fun MessagePartsBlock(
    assistant: Assistant?,
    role: MessageRole,
    model: Model?,
    hazeState: HazeState?,
    parts: List<UIMessagePart>,
    annotations: List<UIMessageAnnotation>,
    loading: Boolean,
    onToolApproval: ((toolCallId: String, approved: Boolean, reason: String) -> Unit)? = null,
    onToolAnswer: ((toolCallId: String, answer: String) -> Unit)? = null,
    onToolCancel: ((toolCallId: String, reason: String) -> Unit)? = null,
    onUserMessageClick: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val settings = LocalSettings.current
    // 消息输出HapticFeedback
    val hapticFeedback = LocalHapticFeedback.current
    var selectedKnowledgeCitation by remember { mutableStateOf<UIMessageAnnotation.KnowledgeCitation?>(null) }
    val annotationState by rememberUpdatedState(annotations)
    val partsState by rememberUpdatedState(parts)
    val loadingState by rememberUpdatedState(loading)
    val uriHandler by rememberUpdatedState(LocalUriHandler.current)
    val citationContext by rememberUpdatedState(context)
    val linkActions by rememberUpdatedState(me.rerere.rikkahub.ui.components.richtext.LocalLinkActionHandler.current)
    val resolveCitation = remember {
        { citationId: String ->
            partsState.asSequence().filterIsInstance<UIMessagePart.Tool>()
                .flatMap { it.searchWebCitations().asSequence() }
                .firstNotNullOfOrNull { citation ->
                    if (citation.id != citationId) null else webLinkUrl(citation.url)?.let {
                        me.rerere.rikkahub.ui.components.richtext.LinkTarget(it, citation.title)
                    }
                }.also {
                    if (it == null) Toast.makeText(citationContext, "找不到此引用的网页地址。", Toast.LENGTH_SHORT).show()
                }
        }
    }
    val handleClickCitation: (String) -> Unit = remember {
        { citationId ->
            val knowledge = annotationState.filterIsInstance<UIMessageAnnotation.KnowledgeCitation>()
                .firstOrNull { it.chunkId == citationId.removePrefix("knowledge:") }
            if (knowledge != null) selectedKnowledgeCitation = knowledge
            else resolveCitation(citationId)?.let { uriHandler.openUri(it.url) }
        }
    }
    val handleLongClickCitation: (String) -> Unit = remember {
        { citationId -> resolveCitation(citationId)?.let { linkActions?.invoke(it) } }
    }
    LaunchedEffect(settings.displaySetting) {
        snapshotFlow { partsState }
            .debounce(50.milliseconds)
            .collect { parts ->
                if (parts.isNotEmpty() && loadingState && settings.displaySetting.enableMessageGenerationHapticEffect) {
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
                                        onToolCancel = onToolCancel,
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

            is MessagePartBlock.RenderedContentBlock -> key(block.index) {
                // Accepted automatic renderers are answer content. The tool
                // call remains in the persisted parts for replay/export, but
                // its visual is no longer enclosed by ChainOfThought.
                RenderingToolContent(block.tool)
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
                        val theme = me.rerere.rikkahub.ui.theme.LocalBaseThemeColorScheme.current
                            ?: MaterialTheme.colorScheme
                        val themeText = if (isUserMessage) theme.onPrimaryContainer else theme.onSurface
                        val hasWallpaper = settings.hasActiveChatBackground()
                        val backgroundSamples = me.rerere.rikkahub.ui.components.ui.LocalAppearanceBackground.current?.readability?.backgrounds
                        val paintedContainer = if (isUserMessage || shouldRenderAssistantMessageBubble(settings))
                            bubbleAppearance.color else Color.Transparent
                        val textPalette = rememberMessageTextPalette(paintedContainer,
                            backgroundSamples?.takeIf { hasWallpaper && it.isNotEmpty() }
                                ?: listOf(theme.background.copy(alpha = 1f)), themeText)
                        val bubbleText = textPalette.foreground
                        val textBackgrounds = textPalette.backgrounds
                        val bubbleTextStyle = androidx.compose.material3.LocalTextStyle.current.copy(color = bubbleText)
                        val textContent = @Composable {
                            if (isUserMessage) {
                                Surface(
                                    modifier = bubbleAppearance.modifier,
                                    shape = RoundedCornerShape(16.dp),
                                    color = bubbleAppearance.color,
                                    contentColor = bubbleText,
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
                                            style = bubbleTextStyle,
                                            content = part.text.replaceRegexes(
                                                assistant = assistant,
                                                scope = AssistantAffectScope.USER,
                                                visual = true,
                                            ),
                                            onClickCitation = handleClickCitation,
                                            renderMode = MarkdownRenderMode.USER_MESSAGE,
                                        )
                                    }
                                }
                            } else {
                                if (shouldRenderAssistantMessageBubble(settings)) {
                                    Surface(
                                        modifier = bubbleAppearance.modifier,
                                        shape = RoundedCornerShape(16.dp),
                                        color = bubbleAppearance.color,
                                        contentColor = bubbleText,
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
                                                style = bubbleTextStyle,
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
                                        style = bubbleTextStyle,
                                        content = part.text.replaceRegexes(
                                            assistant = assistant,
                                            scope = AssistantAffectScope.ASSISTANT,
                                            visual = true,
                                        ),
                                        onClickCitation = handleClickCitation,
                                        modifier = Modifier,
                                    )
                                }
                            }
                        }

                        CompositionLocalProvider(
                            androidx.compose.material3.LocalContentColor provides bubbleText,
                            androidx.compose.material3.LocalTextStyle provides bubbleTextStyle,
                            me.rerere.rikkahub.ui.components.richtext.LocalTextSurfaceBackgrounds provides textBackgrounds,
                            me.rerere.rikkahub.ui.components.richtext.LocalCitationLinkAction provides handleLongClickCitation,
                            me.rerere.rikkahub.ui.components.richtext.LocalGitHubCardsEnabled provides
                                (role == MessageRole.ASSISTANT),
                        ) {
                            me.rerere.rikkahub.ui.theme.SurfaceTextTheme(bubbleText) {
                                StreamingSelectionContainer(streaming = loading, content = textContent)
                            }
                        }
                    }

                    is UIMessagePart.Video -> {
                        InlineVideo(part.url)
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
                                    modifier = Modifier
                                        .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = HugeIcons.MusicNote03,
                                        contentDescription = stringResource(R.string.audio),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }

                    is UIMessagePart.Image -> {
                        val isImageLoading =
                            part.url.isBlank() || part.url.matches(Regex("^data:image/[^;]*;base64,\\s*$"))
                        val isGeneratedImage = role == MessageRole.ASSISTANT &&
                            model?.type == ModelType.IMAGE
                        if (isImageLoading && !isGeneratedImage) {
                            Box(
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(MaterialTheme.shapes.medium)
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .shimmer(isLoading = true)
                            )
                        } else if (isGeneratedImage) {
                            GeneratedChatImage(part.url)
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
                        val scorePreview = me.rerere.rikkahub.ui.components.richtext.rememberScoreAttachmentPreview()
                        Surface(
                            tonalElevation = 2.dp,
                            onClick = {
                                scorePreview(part) {
                                    try {
                                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", part.url.toUri().toFile())
                                        val intent = Intent(Intent.ACTION_VIEW).apply {
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            setDataAndType(uri, part.mime.ifBlank { "application/octet-stream" })
                                        }
                                        context.startActivity(Intent.createChooser(intent, null))
                                    } catch (_: Exception) {
                                        Toast.makeText(context, "无法打开此附件，请检查文件或可用的查看应用", Toast.LENGTH_SHORT).show()
                                    }
                                }
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
    annotations.filterIsInstance<UIMessageAnnotation.GoogleSearchSuggestions>().distinct().forEach {
        GoogleSearchSuggestions(it)
    }
    val citationAnnotations = annotations.filterNot { it is UIMessageAnnotation.GoogleSearchSuggestions }
    if (citationAnnotations.isNotEmpty()) {
        Column(modifier = Modifier.animateContentSize()) {
            var expand by remember { mutableStateOf(false) }
            if (expand) CitationSources(citationAnnotations) { selectedKnowledgeCitation = it }
            TextButton(onClick = { expand = !expand }) {
                Text(pluralStringResource(R.plurals.citations_count, citationAnnotations.size, citationAnnotations.size))
            }
        }
    }
    selectedKnowledgeCitation?.let { citation ->
        KnowledgeCitationSheet(citation, onDismiss = { selectedKnowledgeCitation = null })
    }
}
