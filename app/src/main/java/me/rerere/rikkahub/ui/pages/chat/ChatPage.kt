package me.rerere.rikkahub.ui.pages.chat

import me.rerere.rikkahub.data.model.placeholderNames
import me.rerere.rikkahub.data.model.render

import android.content.ClipData
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.layout.LazyLayoutCacheWindow
import androidx.compose.foundation.lazy.rememberLazyListState
import me.rerere.rikkahub.ui.components.ui.AppearanceAlertDialog as AlertDialog
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.PermanentNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.adaptive.currentWindowDpSize
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.layout
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.dokar.sonner.ToastType
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.blur.material3.Material3
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.ImageGenerationConstraints
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.providers.google.requestChannel
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.android.appTempFolder
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AiBrain01
import me.rerere.hugeicons.stroke.ArrowTurnBackward
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.LeftToRightListBullet
import me.rerere.hugeicons.stroke.Link01
import me.rerere.hugeicons.stroke.Menu03
import me.rerere.hugeicons.stroke.MessageAdd01
import me.rerere.hugeicons.stroke.Settings02
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SuggestionInsertMode
import me.rerere.rikkahub.data.datastore.ExtensionManagementMode
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.data.datastore.hasActiveChatBackground
import me.rerere.rikkahub.data.datastore.resolveChatBackground
import me.rerere.rikkahub.data.ai.transformers.DocumentAsPromptTransformer
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.memoryAssistant
import me.rerere.rikkahub.data.datastore.resolveMemoryExtractionModel
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.ChatSuggestionAction
import me.rerere.rikkahub.data.model.ChatSuggestionItem
import me.rerere.rikkahub.data.model.availableSuggestionActions
import me.rerere.rikkahub.data.model.currentChatSuggestions
import me.rerere.rikkahub.data.model.*
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.collectLatest
import me.rerere.rikkahub.data.model.ActiveMode
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.model.constrained
import me.rerere.rikkahub.data.model.LorebookEntryStatus
import me.rerere.rikkahub.data.model.InjectionPosition
import me.rerere.rikkahub.data.model.MemoryType
import me.rerere.rikkahub.data.model.PromptInjectionDiagnostics
import me.rerere.rikkahub.data.memory.MemoryExtractionOutcome
import me.rerere.rikkahub.data.memory.MemoryExtractionStatus
import me.rerere.rikkahub.data.model.resolveActiveModes
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.service.ChatError
import me.rerere.rikkahub.ui.components.ai.ChatInput
import me.rerere.rikkahub.ui.components.ui.AppearanceModalBottomSheet
import me.rerere.rikkahub.ui.components.ui.LocalAdvancedAppearanceCapabilities
import me.rerere.rikkahub.ui.components.ui.AppearanceBackgroundSpec
import me.rerere.rikkahub.ui.components.ui.LocalAppearanceBackground
import me.rerere.rikkahub.ui.components.ai.ChatContextUsage
import me.rerere.rikkahub.ui.components.ai.ContextUsageSummary
import me.rerere.rikkahub.ui.components.ai.calculateChatContextUsage
import me.rerere.rikkahub.ui.components.ai.FilesPicker
import me.rerere.rikkahub.ui.components.ai.SearchMode
import me.rerere.rikkahub.ui.components.ai.completion.WorkspaceCompletionProvider
import me.rerere.rikkahub.ui.components.ai.LOCAL_WORKSPACE_CWD_PREFIX
import me.rerere.rikkahub.ui.components.ai.useCropLauncher
import me.rerere.rikkahub.ui.components.ui.permission.PermissionCamera
import me.rerere.rikkahub.ui.components.ui.permission.PermissionManager
import me.rerere.rikkahub.ui.components.ui.permission.rememberPermissionState
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.context.Navigator
import me.rerere.rikkahub.ui.context.LocalAppearanceSurfaceOpacityPolicy
import me.rerere.rikkahub.ui.context.LocalGlobalBackgroundActive
import me.rerere.rikkahub.ui.context.appearanceSurfaceOpacityPolicy
import me.rerere.rikkahub.ui.theme.LocalChatBackgroundForeground
import me.rerere.rikkahub.ui.theme.BackgroundReadabilityTheme
import me.rerere.rikkahub.ui.theme.rememberBackgroundReadability
import me.rerere.rikkahub.ui.hooks.ChatInputState
import me.rerere.rikkahub.ui.hooks.EditStateContent
import me.rerere.rikkahub.ui.hooks.useEditState
import me.rerere.rikkahub.ui.pages.imggen.ImageGenerationSettingsBottomSheet
import me.rerere.rikkahub.utils.ImageUtils
import me.rerere.rikkahub.utils.base64Decode
import me.rerere.rikkahub.utils.isAllowedFileType
import me.rerere.rikkahub.utils.isMidiFileType
import me.rerere.rikkahub.utils.navigateToChatPage
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf
import java.io.File
import kotlin.math.roundToInt
import kotlin.uuid.Uuid

private enum class ConversationContentStage {
    LOADING,
    READY,
    FAILED,
}

private data class ConversationContentKey(
    val conversationId: Uuid,
    val stage: ConversationContentStage,
)

private fun ConversationLoadState.contentStage(): ConversationContentStage = when (this) {
    ConversationLoadState.Loading -> ConversationContentStage.LOADING
    ConversationLoadState.Ready -> ConversationContentStage.READY
    is ConversationLoadState.Failed -> ConversationContentStage.FAILED
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatPage(id: Uuid, text: String?, files: List<Uri>, nodeId: Uuid? = null) {
    val vm: ChatVM = koinViewModel(
        key = "chat:$id",
        parameters = {
            parametersOf(id.toString())
        }
    )
    val filesManager: FilesManager = koinInject()
    val navController = LocalNavController.current
    val scope = rememberCoroutineScope()

    val setting by vm.settings.collectAsStateWithLifecycle()
    val conversation by vm.conversation.collectAsStateWithLifecycle()
    val loadingJob by vm.conversationJob.collectAsStateWithLifecycle()
    val processingStatus by vm.processingStatus.collectAsStateWithLifecycle()
    val conversationLoadState by vm.conversationLoadState.collectAsStateWithLifecycle()
    val branchSourceAvailable by vm.branchSourceAvailable.collectAsStateWithLifecycle()
    val promptInjectionDiagnostics by vm.promptInjectionDiagnostics.collectAsStateWithLifecycle()
    val currentChatModel by vm.currentChatModel.collectAsStateWithLifecycle()
    val chatImageModel by vm.chatImageModel.collectAsStateWithLifecycle()
    val chatImageConstraints by vm.chatImageConstraints.collectAsStateWithLifecycle()
    val chatImageState by vm.chatImageState.collectAsStateWithLifecycle()
    val enableWebSearch by vm.enableWebSearch.collectAsStateWithLifecycle()
    val errors by vm.errors.collectAsStateWithLifecycle()

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val chatChromeHazeState = rememberHazeState()
    val navigationHazeState = rememberHazeState()
    val softwareKeyboardController = LocalSoftwareKeyboardController.current

    // Handle back press when drawer is open
    BackHandler(enabled = drawerState.isOpen) {
        scope.launch {
            drawerState.close()
        }
    }

    // Hide keyboard when drawer is open
    LaunchedEffect(drawerState.isOpen) {
        if (drawerState.isOpen) {
            softwareKeyboardController?.hide()
        }
    }

    val windowAdaptiveInfo = currentWindowDpSize()
    val isBigScreen =
        windowAdaptiveInfo.width > windowAdaptiveInfo.height && windowAdaptiveInfo.width >= 1100.dp

    // 进入大屏（永久抽屉）模式时重置抽屉状态为关闭，
    // 避免从横屏旋转回竖屏后，模态抽屉残留为打开状态且无法关闭（#1304）
    LaunchedEffect(isBigScreen) {
        if (isBigScreen && drawerState.isOpen) {
            drawerState.close()
        }
    }

    val inputState = vm.inputState
    var webPreviewId by remember(id) { mutableStateOf<String?>(null) }
    me.rerere.rikkahub.ui.components.webview.ChatWebPreviewHost(webPreviewId) { webPreviewId = null }

    // 初始化输入状态（处理传入的 files 和 text 参数）
    LaunchedEffect(files, text) {
        val decodedText = text?.base64Decode()?.takeIf { it.isNotEmpty() }
        if (files.isNotEmpty() || decodedText != null) {
            // Explicit share/import content replaces the existing draft. A
            // normal navigation without arguments keeps the shared draft.
            inputState.clearInput()
        }
        if (files.isNotEmpty()) {
            val localFiles = filesManager.createChatFilesByContents(files)
            val contentTypes = files.map { file ->
                filesManager.getFileMimeType(file)
            }
            val parts = buildList {
                localFiles.forEachIndexed { index, file ->
                    val type = contentTypes.getOrNull(index)
                    val fileName = filesManager.getFileNameFromUri(files.getOrNull(index) ?: file) ?: "file"
                    if (isMidiFileType(fileName, type)) {
                        add(UIMessagePart.Document(url = file.toString(), fileName = fileName, mime = type ?: "audio/midi"))
                    } else if (type?.startsWith("image/") == true) {
                        add(UIMessagePart.Image(url = file.toString()))
                    } else if (type?.startsWith("video/") == true) {
                        add(UIMessagePart.Video(url = file.toString()))
                    } else if (type?.startsWith("audio/") == true) {
                        add(UIMessagePart.Audio(url = file.toString()))
                    }
                }
            }
            inputState.messageContent = parts
        }
        decodedText?.let {
            inputState.setMessageText(it)
        }
    }

    val chatListState = key(id) {
        rememberLazyListState(
            cacheWindow = LazyLayoutCacheWindow(
                aheadFraction = 0.5f,
                behindFraction = 1f,
            ),
        )
    }
    val previewReturn = rememberChatPreviewReturn(
        conversationKey = id.toString(),
        listState = chatListState,
        messageKeys = remember(conversation.messageNodes) { conversation.messageNodes.map { it.id.toString() } },
        ready = conversationLoadState == ConversationLoadState.Ready,
        active = (navController.currentScreen as? me.rerere.rikkahub.Screen.Chat)?.id == id.toString(),
    )
    LaunchedEffect(id, nodeId, conversationLoadState, conversation.messageNodes.size) {
        if (conversationLoadState == ConversationLoadState.Ready &&
            !vm.chatListInitialized &&
            conversation.messageNodes.isNotEmpty()
        ) {
            if (previewReturn.anchor != null) {
                // Returning from a preview must not run the initial jump-to-bottom again.
            } else if (nodeId != null) {
                val index = conversation.messageNodes.indexOfFirst { it.id == nodeId }
                if (index >= 0) {
                    chatListState.scrollToItem(index)
                }
            } else {
                chatListState.requestScrollToItem(conversation.currentMessages.size + 5)
            }
            vm.chatListInitialized = true
        }
    }

    val resolvedChatBackground = setting.resolveChatBackground()
    val glassBackdrop = me.rerere.rikkahub.ui.components.ui.rememberGlassBackdrop()
    val chatReadability = rememberBackgroundReadability(
        background = resolvedChatBackground.background,
        backgroundOpacity = resolvedChatBackground.opacity,
        useGradientBackground = resolvedChatBackground.useGradientBackground,
        gradientFollowTheme = resolvedChatBackground.gradientFollowTheme,
        gradientPreset = resolvedChatBackground.gradientPreset,
        gradientCustomColors = resolvedChatBackground.gradientCustomColors,
        gradientIntensity = resolvedChatBackground.gradientIntensity,
        gradientVignette = resolvedChatBackground.gradientVignette,
    )
    val appearanceForeground = chatReadability.foreground
    val assistantBackgroundSpec = AppearanceBackgroundSpec(
        background = resolvedChatBackground.background,
        opacity = resolvedChatBackground.opacity,
        blurRadius = resolvedChatBackground.blurRadius,
        useGradientBackground = resolvedChatBackground.useGradientBackground,
        gradientAnimation = resolvedChatBackground.gradientAnimation,
        gradientSpeed = resolvedChatBackground.gradientSpeed,
        gradientFollowTheme = resolvedChatBackground.gradientFollowTheme,
        gradientPreset = resolvedChatBackground.gradientPreset,
        gradientCustomColors = resolvedChatBackground.gradientCustomColors,
        gradientIntensity = resolvedChatBackground.gradientIntensity,
        gradientMotionScale = resolvedChatBackground.gradientMotionScale,
        gradientBlobCount = resolvedChatBackground.gradientBlobCount,
        gradientSoftness = resolvedChatBackground.gradientSoftness,
        gradientAngle = resolvedChatBackground.gradientAngle,
        gradientVignette = resolvedChatBackground.gradientVignette,
        gradientPerformanceEffectsEnabled = setting.advancedAppearanceSetting
            .enableGradientPerformanceEffects,
        gradientRendererMode = setting.advancedAppearanceSetting.gradientRendererMode,
        gradientInteractionInProgress = chatListState.isScrollInProgress || loadingJob != null,
        respectSystemReducedMotion = setting.advancedAppearanceSetting
            .respectSystemReducedMotion,
        foreground = appearanceForeground,
        readability = chatReadability,
    )

    CompositionLocalProvider(
        me.rerere.rikkahub.ui.components.webview.LocalChatWebPreview provides { previewId -> webPreviewId = previewId },
        me.rerere.rikkahub.ui.components.webview.LocalChatWebPreviewVisible provides (webPreviewId != null),
        LocalChatPreviewReturn provides previewReturn,
        me.rerere.rikkahub.ui.components.webview.LocalBeforeWebPreview provides { previewReturn.capture(chatListState) },
        LocalGlobalBackgroundActive provides setting.hasActiveChatBackground(),
        me.rerere.rikkahub.ui.components.ui.LocalGlassBackdrop provides glassBackdrop,
        me.rerere.rikkahub.ui.components.ui.LocalGlobalBackgroundHazeState provides navigationHazeState,
        me.rerere.rikkahub.ui.components.ui.LocalGlassBusy provides (me.rerere.rikkahub.ui.components.ui.LocalGlassBusy.current || glassBackdrop.interacting || chatListState.isScrollInProgress || loadingJob != null),
        LocalAppearanceBackground provides assistantBackgroundSpec,
        me.rerere.rikkahub.ui.components.message.LocalAskUserDraftWriter provides { toolId, input, answers, displayed ->
            vm.saveAskUserDraft(conversation.id, toolId, input, answers, displayed)
        },
        me.rerere.rikkahub.ui.components.message.LocalAskUserDraftScope provides conversation.id.toString(),
        LocalChatBackgroundForeground provides appearanceForeground,
        LocalAppearanceSurfaceOpacityPolicy provides appearanceSurfaceOpacityPolicy(
            cardOpacity = setting.advancedAppearanceSetting.pageSurfaceOpacity,
            topBarOpacity = setting.displaySetting.topBarSurfaceOpacity,
            inputOpacity = setting.displaySetting.inputSurfaceOpacity,
            dockOpacity = setting.advancedAppearanceSetting.chatDockGlassOpacity,
            cardBackgroundActive = setting.hasActiveChatBackground(),
        ),
    ) {
            BackgroundReadabilityTheme(
            active = setting.hasActiveChatBackground() ||
                me.rerere.rikkahub.ui.theme.LocalTextColorMode.current != me.rerere.rikkahub.data.datastore.TextColorMode.THEME,
                foreground = appearanceForeground,
        ) {
            Box(Modifier.fillMaxSize()) {
            // A stable, full-page source includes both the sidebar and chat column.
            AssistantBackground(
                setting = setting,
                interactionInProgress = chatListState.isScrollInProgress || loadingJob != null,
                modifier = Modifier.fillMaxSize().hazeSource(chatChromeHazeState).hazeSource(navigationHazeState),
            )
            when {
            isBigScreen -> {
            PermanentNavigationDrawer(
                drawerContent = {
                    ChatDrawerContent(
                        navController = navController,
                        current = conversation,
                        vm = vm,
                        settings = setting,
                        onSelectConversation = { selectedConversation ->
                            if (selectedConversation.id != conversation.id) {
                                navigateToChatPage(navController, selectedConversation.id)
                            }
                        },
                    )
                }
            ) {
                ChatPageContent(
                    inputState = inputState,
                    loadingJob = loadingJob,
                    processingStatus = processingStatus,
                    conversationLoadState = conversationLoadState,
                    promptInjectionDiagnostics = promptInjectionDiagnostics,
                    setting = setting,
                    conversation = conversation,
                    drawerState = drawerState,
                    navController = navController,
                    vm = vm,
                    chatListState = chatListState,
                    chatChromeHazeState = chatChromeHazeState,
                    navigationHazeState = navigationHazeState,
                    enableWebSearch = enableWebSearch,
                    currentChatModel = currentChatModel,
                    chatImageModel = chatImageModel,
                    chatImageConstraints = chatImageConstraints,
                    chatImageState = chatImageState,
                    bigScreen = true,
                    errors = errors,
                    branchSourceAvailable = branchSourceAvailable,
                    onDismissError = { vm.dismissError(it) },
                    onClearAllErrors = { vm.clearAllErrors() },
                )
            }
            }

            else -> {
            ModalNavigationDrawer(
                drawerState = drawerState,
                scrimColor = MaterialTheme.colorScheme.scrim.copy(alpha = 0.56f),
                drawerContent = {
                    ChatDrawerContent(
                        navController = navController,
                        current = conversation,
                        vm = vm,
                        settings = setting,
                        onSelectConversation = { selectedConversation ->
                            scope.launch {
                                drawerState.close()
                                if (selectedConversation.id != conversation.id) {
                                    navigateToChatPage(navController, selectedConversation.id)
                                }
                            }
                        },
                        onNavigate = { destination ->
                            scope.launch {
                                navController.navigate(destination)
                                // The destination transition owns the handoff. Keep the drawer
                                // state until its parent entry is removed so it does not flash shut.
                            }
                        },
                    )
                }
            ) {
                val drawerOccludesContent = drawerState.currentValue != DrawerValue.Closed ||
                    drawerState.targetValue != DrawerValue.Closed
                if (!drawerOccludesContent) {
                    ChatPageContent(
                    inputState = inputState,
                    loadingJob = loadingJob,
                    processingStatus = processingStatus,
                    conversationLoadState = conversationLoadState,
                    promptInjectionDiagnostics = promptInjectionDiagnostics,
                    setting = setting,
                    conversation = conversation,
                    drawerState = drawerState,
                    navController = navController,
                    vm = vm,
                    chatListState = chatListState,
                    chatChromeHazeState = chatChromeHazeState,
                    navigationHazeState = navigationHazeState,
                    enableWebSearch = enableWebSearch,
                    currentChatModel = currentChatModel,
                    chatImageModel = chatImageModel,
                    chatImageConstraints = chatImageConstraints,
                    chatImageState = chatImageState,
                    bigScreen = false,
                    errors = errors,
                    branchSourceAvailable = branchSourceAvailable,
                    onDismissError = { vm.dismissError(it) },
                    onClearAllErrors = { vm.clearAllErrors() },
                    )
                }
            }
            BackHandler(drawerState.isOpen) {
                scope.launch { drawerState.close() }
            }
            }
            }
            }
        }
    }
}

@OptIn(kotlinx.coroutines.FlowPreview::class)
@Composable
private fun ChatPageContent(
    inputState: ChatInputState,
    loadingJob: Job?,
    processingStatus: String? = null,
    conversationLoadState: ConversationLoadState,
    promptInjectionDiagnostics: PromptInjectionDiagnostics? = null,
    setting: Settings,
    bigScreen: Boolean,
    conversation: Conversation,
    drawerState: DrawerState,
    navController: Navigator,
    vm: ChatVM,
    chatListState: LazyListState,
    chatChromeHazeState: HazeState,
    navigationHazeState: HazeState,
    enableWebSearch: Boolean,
    currentChatModel: Model?,
    chatImageModel: Model?,
    chatImageConstraints: ImageGenerationConstraints?,
    chatImageState: ChatVM.ChatImageState,
    errors: List<ChatError>,
    branchSourceAvailable: Boolean?,
    onDismissError: (Uuid) -> Unit,
    onClearAllErrors: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val context = LocalContext.current
    var showChatImageDialog by remember(conversation.id) { mutableStateOf(false) }
    // Save primitive coordinates instead of Offset, which is not Bundle-saveable
    // on all Compose versions used by supported Android devices.
    var imageButtonOffsetX by rememberSaveable(conversation.id) { mutableStateOf(Float.NaN) }
    var imageButtonOffsetY by rememberSaveable(conversation.id) { mutableStateOf(Float.NaN) }
    val clipboard = LocalClipboard.current
    val selectModelRequired = stringResource(R.string.chat_page_select_model_required)
    val createForkFailed = stringResource(R.string.create_fork_failed)
    val workspaceRepository: WorkspaceRepository = koinInject()
    var previewMode by rememberSaveable(conversation.id) { mutableStateOf(false) }
    val assistant = setting.getAssistantById(conversation.assistantId)
        ?: setting.getCurrentAssistant()
    val chatImageProvider = chatImageModel?.findProvider(setting.providers)
    val chatImageGoogleProvider = chatImageProvider as? ProviderSetting.Google
    val isGeminiChatImageModel = chatImageGoogleProvider != null &&
        chatImageConstraints?.sizeRequestField == "aspect_ratio" &&
        chatImageModel.modelId
            .substringAfterLast('/')
            .startsWith("gemini-", ignoreCase = true)
    val chatImageGeminiModelId = chatImageModel?.modelId?.takeIf { isGeminiChatImageModel }
    val chatImageRequestChannel = chatImageGoogleProvider
        ?.requestChannel()
        ?.takeIf { isGeminiChatImageModel }
    LaunchedEffect(chatImageModel?.id, chatImageConstraints) {
        chatImageConstraints?.let { constraints ->
            val current = inputState.imageGenerationSettings
            val constrained = current.constrained(constraints)
            if (constrained != current) {
                inputState.imageGenerationSettings = constrained
            }
        }
    }
    val memoryExtractionStatus by vm.memoryExtractionStatus.collectAsStateWithLifecycle()
    val suggestionGenerationState by vm.suggestionGenerationState.collectAsStateWithLifecycle()
    val suggestionConfig = conversation.suggestionConfig(setting)
    var suggestionUndo by remember(conversation.id) { mutableStateOf<SuggestionDraftEdit?>(null) }
    var suggestionPreview by remember(conversation.id) { mutableStateOf<ChatSuggestionItem?>(null) }
    var inspirationDraft by rememberSaveable(conversation.id, conversation.assistantId, stateSaver = InspirationCardStateSaver) {
        mutableStateOf<me.rerere.rikkahub.data.model.InspirationCard?>(null)
    }
    fun insertInspiration(prompt: String, mode: me.rerere.rikkahub.data.model.InspirationInsert, reviewed: String): Boolean {
        if (vm.conversation.value.id != conversation.id || vm.conversation.value.assistantId != conversation.assistantId) return false
        val edit = me.rerere.rikkahub.data.model.inspirationDraftEdit(inputState.textContent.text.toString(), prompt, mode, reviewed)
            ?: return false
        inputState.setMessageText(edit.after)
        inputState.textContent.edit { selection = androidx.compose.ui.text.TextRange(edit.cursor) }
        return true
    }
    var showSuggestionPanel by remember(conversation.id) { mutableStateOf(false) }
    fun insertSuggestionText(text: String, location: SuggestionInsertLocation = if (suggestionConfig.insertMode == SuggestionInsertMode.APPEND)
        SuggestionInsertLocation.APPEND else SuggestionInsertLocation.REPLACE) {
        val selection = inputState.textContent.selection
        val edit = prepareSuggestionInsertion(inputState.textContent.text.toString(), text, location, selection.start, selection.end)
        inputState.cancelEditing()
        inputState.setMessageText(edit.after)
        inputState.textContent.edit { this.selection = androidx.compose.ui.text.TextRange(edit.cursor) }
        suggestionUndo = edit
    }
    val recentMemories by vm.recentMemories.collectAsStateWithLifecycle()
    val recentConversationMemories by vm.recentConversationMemories.collectAsStateWithLifecycle()
    var showFilesSheet by remember(conversation.id) { mutableStateOf(false) }
    val contextCapacityTokens = currentChatModel?.let { it.contextWindowTokens?.takeIf { value -> value > 0 } ?: me.rerere.ai.provider.inferContextWindowTokens(it.modelId) }
    val scopeKey = currentChatModel?.let { me.rerere.rikkahub.data.ai.context.requestContextScopeKey(conversation, setting, it) }
    val latestContextConversation by rememberUpdatedState(conversation)
    val contextUsage by produceState(
        initialValue = ChatContextUsage(
            usedTokens = 0,
            capacityTokens = contextCapacityTokens,
            isEstimated = true,
        ),
        conversation.id,
        showFilesSheet,
        scopeKey,
        contextCapacityTokens,
    ) {
        if (!showFilesSheet) return@produceState
        androidx.compose.runtime.snapshotFlow { latestContextConversation.currentMessages }
            .sample(400).onStart { emit(latestContextConversation.currentMessages) }.collectLatest { raw ->
            val source = latestContextConversation
            val contextMessages = DocumentAsPromptTransformer.transformDocumentContents(raw)
            value = withContext(Dispatchers.Default) {
                calculateChatContextUsage(messages = contextMessages, rawMessages = raw,
                    rollingContextSummary = source.rollingContextSummary.takeIf { assistant.enableRollingContextCompression },
                    capacityTokens = contextCapacityTokens, scopeKey = scopeKey, modelId = currentChatModel?.id?.toString(),
                    includeReasoning = me.rerere.rikkahub.data.ai.context.countHistoryReasoning(currentChatModel?.findProvider(setting.providers)),
                    systemPrompt = if (assistant.allowConversationSystemPrompt && !source.customSystemPrompt.isNullOrBlank()) source.customSystemPrompt.orEmpty() else assistant.systemPrompt)
            }
        }
    }
    var showPromptDiagnostics by remember(conversation.id) { mutableStateOf(false) }
    var showRecentMemories by remember(conversation.id) { mutableStateOf(false) }
    var forkingMessageId by remember(conversation.id) { mutableStateOf<Uuid?>(null) }
    fun applySuggestion(suggestion: ChatSuggestionItem, text: String, location: SuggestionInsertLocation) {
        val latest = vm.conversation.value
        if (latest.id != conversation.id || suggestion.action !in latest.availableSuggestionActions(vm.settings.value) ||
            (suggestion.sourceMessageId != null && latest.currentChatSuggestions().none { it.id == suggestion.id })) {
            toaster.show("建议已失效，请重新生成"); return
        }
        when (suggestion.action) {
            ChatSuggestionAction.COPY_TEXT -> scope.launch {
                clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(null, text))); toaster.show(context.getString(R.string.copied))
            }
            ChatSuggestionAction.SAVE_QUICK_MESSAGE -> {
                val targetAssistantId = latest.assistantId
                scope.launch {
                    try { vm.saveSuggestionAsQuickMessage(suggestion, targetAssistantId); toaster.show(context.getString(R.string.chat_page_suggestion_saved)) }
                    catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (_: Exception) { toaster.show(context.getString(R.string.chat_page_suggestion_save_failed), type = ToastType.Error) }
                }
            }
            ChatSuggestionAction.CREATE_BRANCH -> {
                val source = suggestion.sourceMessageId?.let { id -> latest.currentMessages.firstOrNull { it.id == id } } ?: latest.suggestionSourceMessage()
                if (source != null && forkingMessageId == null && loadingJob == null) {
                    forkingMessageId = source.id
                    scope.launch {
                        try {
                            val fork = vm.forkMessage(source)
                            insertSuggestionText(text, location)
                            navigateToChatPage(navController, chatId = fork.id)
                        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                        catch (_: Exception) { toaster.show(createForkFailed, type = ToastType.Error) }
                        finally { forkingMessageId = null }
                    }
                }
            }
            else -> {
                insertSuggestionText(text, location)
                if (suggestion.action == ChatSuggestionAction.IMAGE_DRAFT) showChatImageDialog = true
            }
        }
    }
    fun selectSuggestion(suggestion: ChatSuggestionItem) {
        if (suggestion.action !in setOf(ChatSuggestionAction.COPY_TEXT, ChatSuggestionAction.SAVE_QUICK_MESSAGE) &&
            (suggestionConfig.options.previewBeforeInsert || suggestion.parameterForm != null || suggestion.action == ChatSuggestionAction.IMAGE_DRAFT)) {
            suggestionPreview = suggestion
        } else applySuggestion(suggestion, suggestionActionInput(context, suggestion),
            if (suggestionConfig.insertMode == SuggestionInsertMode.APPEND) SuggestionInsertLocation.APPEND else SuggestionInsertLocation.REPLACE)
    }
    val suggestionActions = SuggestionUiActions(
        changeSession = { edited -> vm.updateSuggestionSession { latest -> latest.copy(
            settings = if (edited.settings != conversation.suggestionSession.settings) edited.settings else latest.settings,
            paused = if (edited.paused != conversation.suggestionSession.paused) edited.paused else latest.paused,
            collapsed = if (edited.collapsed != conversation.suggestionSession.collapsed) edited.collapsed else latest.collapsed,
        ) } },
        refreshOne = { vm.generateSuggestion(vm.conversation.value, replaceId = it) },
        pin = { vm.toggleSuggestionPin(it) }, undoBatch = { vm.undoSuggestionRefresh() }, resetTarget = { vm.resetSuggestionTarget() },
        feedback = { item, reason -> vm.feedbackSuggestion(item, reason) }, clearFeedback = { vm.clearSuggestionFeedback() },
        canUndoDraft = suggestionUndo?.after == inputState.textContent.text.toString(),
        undoDraft = { suggestionUndo?.takeIf { it.after == inputState.textContent.text.toString() }?.let { inputState.setMessageText(it.before); suggestionUndo = null } },
    )
    val activeModes = if (setting.extensionManagementMode == ExtensionManagementMode.ENTERTAINMENT) {
        resolveActiveModes(
            modeInjections = setting.modeInjections,
            assistantModeIds = assistant.modeInjectionIds,
            conversationModeIds = conversation.modeInjectionIds,
            temporaryModes = conversation.temporaryModeInjections,
            currentUserTurn = conversation.currentMessages.count { it.role == me.rerere.ai.core.MessageRole.USER },
        )
    } else emptyList()

    val completionProviders = remember(assistant.workspaceId, conversation.workspaceCwd, workspaceRepository) {
        assistant.workspaceId?.let { workspaceId ->
            listOf(
                WorkspaceCompletionProvider(
                    workspaceId = workspaceId.toString(),
                    repository = workspaceRepository,
                    // SAF trees are content URIs, not Rootfs paths; local completion is provided
                    // by the SAF tools and must not make the workspace index query an invalid path.
                    currentCwd = conversation.workspaceCwd
                        ?.takeUnless { it.startsWith(LOCAL_WORKSPACE_CWD_PREFIX) },
                )
            )
        }.orEmpty()
    }

    TTSAutoPlay(vm = vm, setting = setting, conversation = conversation)

    val chatBackgroundForeground = LocalChatBackgroundForeground.current

    Surface(
        color = if (setting.hasActiveChatBackground()) {
            Color.Transparent
        } else {
            MaterialTheme.colorScheme.background
        },
        modifier = Modifier.fillMaxSize()
    ) {
        CompositionLocalProvider(
            LocalChatBackgroundForeground provides chatBackgroundForeground,
            LocalMessageSuggestionAction provides { message, selection ->
                vm.generateSuggestionFor(message, selection)
                showSuggestionPanel = true
            },
        ) {
            Scaffold(
            topBar = {
                Column {
                    TopBar(
                        settings = setting,
                        conversation = conversation,
                        titleEditable = conversationLoadState == ConversationLoadState.Ready,
                        hazeState = chatChromeHazeState,
                        bigScreen = bigScreen,
                        drawerState = drawerState,
                        previewMode = previewMode,
                        onNewChat = {
                            navigateToChatPage(navController)
                        },
                        onClickMenu = {
                            previewMode = !previewMode
                        },
                        onUpdateTitle = {
                            vm.updateTitle(it)
                        }
                    )
                    conversation.sourceConversationId?.let { sourceConversationId ->
                        BranchSourceBar(
                            sourceTitle = conversation.sourceConversationTitle
                                ?.takeIf { it.isNotBlank() }
                                ?: stringResource(R.string.chat_page_new_chat),
                            sourceAvailable = branchSourceAvailable,
                            settings = setting,
                            onOpenSource = {
                                navigateToChatPage(navController, chatId = sourceConversationId)
                            },
                        )
                    }
                    if (setting.extensionManagementMode == ExtensionManagementMode.ENTERTAINMENT &&
                        activeModes.isNotEmpty()
                    ) {
                        ActiveModesBar(
                            activeModes = activeModes,
                        )
                    }
                }
            },
            bottomBar = {
                if (conversationLoadState == ConversationLoadState.Ready) {
                    ChatInput(
                    state = inputState,
                    loading = loadingJob != null,
                    settings = setting,
                    hazeState = chatChromeHazeState,
                    completionProviders = completionProviders,
                    onCancelClick = {
                        vm.stopGeneration()
                    },
                    enableSearch = enableWebSearch,
                    onUpdateSearchMode = { mode ->
                        val current = setting.getCurrentAssistant()
                        val model = setting.getCurrentChatModel()
                        vm.updateSettings(
                            setting.copy(
                                assistants = setting.assistants.map { assistant ->
                                    if (assistant.id == current.id) {
                                        assistant.copy(enableWebSearch = mode == SearchMode.LOCAL)
                                    } else {
                                        assistant
                                    }
                                },
                                providers = if (model == null) {
                                    setting.providers
                                } else {
                                    setting.providers.map { provider ->
                                        provider.editModel(
                                            model.copy(
                                                tools = if (mode == SearchMode.BUILT_IN) {
                                                    model.tools + BuiltInTools.Search
                                                } else {
                                                    model.tools - BuiltInTools.Search
                                                }
                                            )
                                        )
                                    }
                                },
                            )
                        )
                    },
                    onSendClick = {
                        if (currentChatModel == null) {
                            toaster.show(selectModelRequired, type = ToastType.Error)
                            return@ChatInput
                        }
                        if (inputState.isEditing()) {
                            vm.handleMessageEdit(
                                parts = inputState.getContents(),
                                messageId = inputState.editingMessage!!,
                            )
                        } else {
                            vm.handleMessageSend(inputState.getContents())
                            scope.launch {
                                chatListState.requestScrollToItem(conversation.currentMessages.size + 5)
                            }
                        }
                        inputState.clearInput()
                    },
                    onUpdateChatModel = {
                        vm.setChatModel(assistant = setting.getCurrentAssistant(), model = it)
                    },
                    onUpdateAssistant = {
                        vm.updateSettings(
                            setting.copy(
                                assistants = setting.assistants.map { assistant ->
                                    if (assistant.id == it.id) {
                                        it
                                    } else {
                                        assistant
                                    }
                                }
                            )
                        )
                    },
                    onUpdateSearchService = { index ->
                        vm.updateSettings(
                            setting.copy(
                                searchServiceSelected = index
                            )
                        )
                    },
                    onMoreClick = {
                        showFilesSheet = true
                    },
                    imageModel = chatImageModel,
                    imageGenerationLoading = chatImageState is ChatVM.ChatImageState.Running,
                    onGenerateImageClick = {
                        val prompt = inputState.textContent.text.toString().trim()
                        if (prompt.isNotEmpty()) {
                            vm.generateChatImage(prompt)
                            inputState.setMessageText("")
                            scope.launch {
                                chatListState.requestScrollToItem(
                                    conversation.messageNodes.size + 1,
                                )
                            }
                        }
                    },
                    )
                }
            },
            containerColor = Color.Transparent,
        ) { innerPadding ->
            Box(Modifier.fillMaxSize()) {
            var hasShownConversation by rememberSaveable(conversation.id) {
                mutableStateOf(conversationLoadState == ConversationLoadState.Ready)
            }
            androidx.compose.runtime.SideEffect {
                if (conversationLoadState == ConversationLoadState.Ready) hasShownConversation = true
            }
            AnimatedContent(
                targetState = ConversationContentKey(
                    conversationId = conversation.id,
                    stage = if (hasShownConversation || conversationLoadState == ConversationLoadState.Ready)
                        ConversationContentStage.READY else conversationLoadState.contentStage(),
                ),
                transitionSpec = {
                    if (initialState.stage == ConversationContentStage.LOADING && targetState.stage == ConversationContentStage.READY) {
                        androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(160)) togetherWith
                            androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(80))
                    } else androidx.compose.animation.EnterTransition.None togetherWith androidx.compose.animation.ExitTransition.None
                },
                contentKey = { it },
                label = "ConversationContentTransition",
                modifier = Modifier.fillMaxSize(),
            ) { contentKey ->
                if (contentKey.stage == ConversationContentStage.READY) {
                    ChatList(
                innerPadding = innerPadding,
                conversation = conversation,
                state = chatListState,
                loading = loadingJob != null,
                imageGenerationLoading = chatImageState is ChatVM.ChatImageState.Running,
                processingStatus = processingStatus,
                previewMode = previewMode,
                settings = setting,
                hazeState = chatChromeHazeState,
                errors = errors,
                onDismissError = onDismissError,
                onClearAllErrors = onClearAllErrors,
                onRegenerate = {
                    vm.regenerateAtMessage(it)
                },
                onContinue = {
                    vm.continueAtMessage(it)
                },
                onEdit = {
                    inputState.editingMessage = it.id
                    inputState.setContents(it.parts)
                },
                onForkMessage = {
                    if (forkingMessageId == null && loadingJob == null) {
                        forkingMessageId = it.id
                        scope.launch {
                            try {
                                val fork = vm.forkMessage(message = it)
                                forkingMessageId = null
                                navigateToChatPage(navController, chatId = fork.id)
                            } catch (_: Exception) {
                                forkingMessageId = null
                                toaster.show(
                                    createForkFailed,
                                    type = ToastType.Error,
                                )
                            }
                        }
                    }
                },
                forkingMessageId = forkingMessageId,
                onDelete = {
                    if (loadingJob != null) {
                        vm.showDeleteBlockedWhileGeneratingError()
                    } else {
                        vm.deleteMessage(it)
                    }
                },
                onUpdateMessage = { newNode ->
                    vm.updateConversation(
                        conversation.copy(
                            messageNodes = conversation.messageNodes.map { node ->
                                if (node.id == newNode.id) {
                                    newNode
                                } else {
                                    node
                                }
                            }
                        ))
                    vm.saveConversationAsync()
                },
                onSuggestionAction = ::selectSuggestion,
                onInspirationSelected = { card ->
                    val current = vm.settings.value
                    val owner = current.getAssistantById(conversation.assistantId)
                    if (owner != null) {
                        val automatic = me.rerere.rikkahub.data.model.automaticQuickMessageValues(current, owner)
                        val template = card.template()
                        val unresolved = template.placeholderNames().filterNot(automatic::containsKey)
                        if (unresolved.isNotEmpty() || !insertInspiration(template.render(automatic),
                                me.rerere.rikkahub.data.model.InspirationInsert.AUTO, inputState.textContent.text.toString())) {
                            inspirationDraft = card
                        }
                    }
                },
                suggestionGenerationState = suggestionGenerationState,
                suggestionActions = suggestionActions,
                onRefreshSuggestions = {
                    vm.generateSuggestion(conversation)
                },
                onDismissSuggestions = {
                    vm.dismissSuggestions(conversation)
                },
                onTranslate = { message, locale ->
                    vm.translateMessage(message, locale)
                },
                onClearTranslation = { message ->
                    vm.clearTranslationField(message.id)
                },
                onJumpToMessage = { index ->
                    previewMode = false
                    scope.launch {
                        chatListState.requestScrollToItem(index)
                    }
                },
                onToolApproval = { toolCallId, approved, reason ->
                    vm.handleToolApproval(toolCallId, approved, reason)
                },
                onToolAnswer = { toolCallId, answer ->
                    vm.handleToolAnswer(toolCallId, answer)
                },
                onToolCancel = { toolCallId, reason ->
                    vm.handleToolCancellation(toolCallId, reason)
                },
                onToggleFavorite = { node ->
                    vm.toggleMessageFavorite(node)
                },
                onConversationSystemPromptChange = { newPrompt ->
                    vm.updateConversation(conversation.copy(customSystemPrompt = newPrompt))
                    vm.saveConversationAsync()
                },
                    )
                } else {
                    ConversationLoadContent(
                        state = conversationLoadState,
                        onRetry = vm::retryConversationLoad,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                    )
                }
            }
                if (hasShownConversation && conversationLoadState != ConversationLoadState.Ready) {
                    androidx.compose.material3.Surface(Modifier.align(Alignment.TopCenter).padding(innerPadding)) {
                        if (conversationLoadState is ConversationLoadState.Failed) {
                            TextButton(onClick = vm::retryConversationLoad) { Text("对话更新失败，点击重试") }
                        } else {
                            androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                    }
                }
        }

            if (showFilesSheet) {
                ChatFilesPickerSheet(
                    inputState = inputState,
                    setting = setting,
                    conversation = conversation,
                    assistant = assistant,
                    vm = vm,
                    contextUsage = contextUsage,
                    promptInjectionDiagnostics = promptInjectionDiagnostics,
                    memoryExtractionStatus = memoryExtractionStatus,
                    recentMemoryCount = recentMemories.size,
                    onShowRecentMemories = {
                        showFilesSheet = false
                        showRecentMemories = true
                    },
                    onShowSuggestions = { showFilesSheet = false; showSuggestionPanel = true },
                    onShowPromptDiagnostics = {
                        showFilesSheet = false
                        showPromptDiagnostics = true
                    },
                    onDismiss = { showFilesSheet = false },
                )
            }
            if (showPromptDiagnostics) {
                PromptInjectionDiagnosticsDialog(
                    diagnostics = promptInjectionDiagnostics,
                    onDismiss = { showPromptDiagnostics = false },
                )
            }
            if (showRecentMemories) {
                RecentMemoriesDialog(
                    conversation = conversation,
                    memoryAssistant = conversation.memoryAssistant(assistant, setting),
                    extractionAvailable = setting.resolveMemoryExtractionModel() != null,
                    onMemoryModeChange = vm::setMemoryMode,
                    status = memoryExtractionStatus,
                    memories = recentMemories,
                    conversationMemories = recentConversationMemories,
                    onDismiss = { showRecentMemories = false },
                    onRetry = vm::retryMemoryExtraction,
                    onDeleteExcerpt = vm::deleteConversationMemory,
                    loadMemoryPreview = vm::loadMemoryPreview,
                    rebuildMemoryIndex = vm::rebuildMemoryIndex,
                    onOpenMemorySource = { sourceId, messageId ->
                        scope.launch {
                            val destination = vm.resolveMemorySource(sourceId, messageId)
                            if (destination == null) toaster.show("来源对话已不存在") else {
                                showRecentMemories = false
                                navigateToChatPage(navController, chatId = destination.first, nodeId = destination.second)
                            }
                        }
                    },
                )
            }
            if (chatImageModel != null && conversationLoadState == ConversationLoadState.Ready) {
                // Keep the action below the app bar and above the input bar. The old
                // overlay had no anchor, so its zero offset placed the button at the
                // window's top-left corner (inside the system status bar).
                val imageButtonSize = 40.dp
                val imageButtonTouchTarget = 48.dp
                val imageButtonMargin = 12.dp
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                ) {
                    val density = LocalDensity.current
                    val containerWidthPx = with(density) { maxWidth.toPx() }
                    val containerHeightPx = with(density) { maxHeight.toPx() }
                    val touchTargetPx = with(density) { imageButtonTouchTarget.toPx() }
                    val marginPx = with(density) { imageButtonMargin.toPx() }
                    val minOffsetX = marginPx
                    val maxOffsetX = (containerWidthPx - touchTargetPx - marginPx)
                        .coerceAtLeast(minOffsetX)
                    val minOffsetY = marginPx
                    val maxOffsetY = (containerHeightPx - touchTargetPx - marginPx)
                        .coerceAtLeast(minOffsetY)

                    // Clamp positions restored from an earlier screen size or
                    // orientation so the button cannot remain off-screen.
                    val safeOffsetX = imageButtonOffsetX
                        .takeUnless(Float::isNaN)
                        ?.coerceIn(minOffsetX, maxOffsetX)
                        ?: maxOffsetX
                    val safeOffsetY = imageButtonOffsetY
                        .takeUnless(Float::isNaN)
                        ?.coerceIn(minOffsetY, maxOffsetY)
                        ?: minOffsetY
                    LaunchedEffect(minOffsetX, maxOffsetX, minOffsetY, maxOffsetY) {
                        imageButtonOffsetX = safeOffsetX
                        imageButtonOffsetY = safeOffsetY
                    }

                    Box(
                        modifier = Modifier
                            .offset {
                                IntOffset(safeOffsetX.roundToInt(), safeOffsetY.roundToInt())
                            }
                            .size(imageButtonTouchTarget)
                            .pointerInput(conversation.id, minOffsetX, maxOffsetX, minOffsetY, maxOffsetY) {
                                detectDragGestures { change, dragAmount ->
                                    change.consume()
                                    imageButtonOffsetX = (imageButtonOffsetX + dragAmount.x)
                                        .coerceIn(minOffsetX, maxOffsetX)
                                    imageButtonOffsetY = (imageButtonOffsetY + dragAmount.y)
                                        .coerceIn(minOffsetY, maxOffsetY)
                                }
                            },
                    ) {
                        SmallFloatingActionButton(
                            onClick = {
                                if (chatImageState !is ChatVM.ChatImageState.Running) {
                                    showChatImageDialog = true
                                }
                            },
                            modifier = Modifier
                                .align(Alignment.Center)
                                .size(imageButtonSize),
                        ) {
                            if (chatImageState is ChatVM.ChatImageState.Running) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.5.dp,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            } else {
                                Icon(
                                    HugeIcons.Settings02,
                                    contentDescription = stringResource(R.string.chat_image_settings),
                                )
                            }
                        }
                    }
                }
            }
            }
        }
    }

    if (showSuggestionPanel) SuggestionControlPanel(conversation, setting, suggestionGenerationState, suggestionActions,
        onRefresh = { vm.generateSuggestion(vm.conversation.value) }, onClear = { vm.dismissSuggestions(vm.conversation.value) },
        onAction = ::selectSuggestion, onDismiss = { showSuggestionPanel = false }, mainGenerating = loadingJob != null)
    suggestionPreview?.let { item -> SuggestionPreviewDialog(item, suggestionActionInput(context, item),
        onInsert = { text, location -> applySuggestion(item, text, location) }, onDismiss = { suggestionPreview = null }) }
    inspirationDraft?.let { card ->
        val owner = setting.getAssistantById(conversation.assistantId)
        if (owner != null) InspirationDraftDialog(card,
            me.rerere.rikkahub.data.model.automaticQuickMessageValues(setting, owner),
            readDraft = { inputState.textContent.text.toString() }, onInsert = ::insertInspiration,
            onDismiss = { inspirationDraft = null })
    }

    if (showChatImageDialog && chatImageModel != null && chatImageConstraints != null) {
        key(conversation.id, chatImageModel.id) {
            val imageSettingsSheetState = rememberBottomSheetState(
                initialValue = SheetValue.Hidden,
                enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
            )
            ImageGenerationSettingsBottomSheet(
                state = inputState.imageGenerationSettings,
                constraints = chatImageConstraints,
                onStateChange = { inputState.imageGenerationSettings = it },
                geminiModelId = chatImageGeminiModelId,
                geminiRequestChannel = chatImageRequestChannel,
                referenceImageCount = 0,
                sheetState = imageSettingsSheetState,
                onDismiss = { showChatImageDialog = false },
                headerContent = {
                    Text(
                        text = buildString {
                            append(chatImageModel.displayName.ifBlank { chatImageModel.modelId })
                            chatImageProvider?.name?.takeIf(String::isNotBlank)?.let { providerName ->
                                append(" (")
                                append(providerName)
                                append(')')
                            }
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                },
                footerContent = {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = { showChatImageDialog = false }) {
                            Text(stringResource(R.string.confirm))
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun RecentMemoryMenuButton(
    status: MemoryExtractionStatus,
    memoryCount: Int,
    onClick: () -> Unit,
) {
    val isFailure = status is MemoryExtractionStatus.Failed
    TextButton(
        onClick = onClick,
        modifier = Modifier
            .padding(start = 12.dp, end = 12.dp, bottom = 4.dp)
            .heightIn(min = 36.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = 12.dp,
            vertical = 4.dp,
        ),
    ) {
        if (status is MemoryExtractionStatus.Running) {
            CircularProgressIndicator(
                modifier = Modifier.size(15.dp),
                strokeWidth = 2.dp,
            )
        } else {
            Icon(
                imageVector = HugeIcons.AiBrain01,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = if (isFailure) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.primary,
            )
        }
        androidx.compose.foundation.layout.Spacer(Modifier.width(6.dp))
        Text(
            text = stringResource(R.string.chat_page_recent_memories_title),
            style = MaterialTheme.typography.labelMedium,
            color = if (isFailure) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.primary,
        )
        if (memoryCount > 0) {
            androidx.compose.foundation.layout.Spacer(Modifier.width(4.dp))
            Text(
                text = memoryCount.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RecentMemoriesDialog(
    conversation: Conversation,
    memoryAssistant: Assistant,
    extractionAvailable: Boolean,
    onMemoryModeChange: (me.rerere.rikkahub.data.model.ConversationMemoryMode) -> Unit,
    status: MemoryExtractionStatus,
    memories: List<AssistantMemory>,
    conversationMemories: List<me.rerere.rikkahub.data.memory.ConversationMemoryRecord>,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onDeleteExcerpt: suspend (String) -> Boolean,
    loadMemoryPreview: suspend (Int) -> me.rerere.rikkahub.data.repository.MemorySearchRecord?,
    onOpenMemorySource: (String, String) -> Unit,
    rebuildMemoryIndex: suspend (Int) -> Unit,
) {
    var showConversationExcerpts by remember(conversation.id) { mutableStateOf(false) }
    var preview by remember(conversation.id) { mutableStateOf<MemoryPreviewContent?>(null) }
    var deleteExcerpt by remember(conversation.id) { mutableStateOf<me.rerere.rikkahub.data.memory.ConversationMemoryRecord?>(null) }
    var deleting by remember { mutableStateOf(false) }
    var operationError by remember { mutableStateOf<String?>(null) }
    val previewScope = rememberCoroutineScope()
    val extractionActive = memoryAssistant.enableMemory && extractionAvailable
    val visibleExcerpts = if (memoryAssistant.enableMemoryRag) conversationMemories else emptyList()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_page_recent_memories_title)) },
        text = {
            LazyColumn(
                modifier = Modifier.heightIn(max = 440.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "memory-mode") {
                    ConversationMemoryControls(conversation, onMemoryModeChange)
                    Text("情景记忆与片段仅用于本对话；事实记忆沿用助手/全局范围。分支继承聊天历史，后续索引独立。", style = MaterialTheme.typography.bodySmall)
                    operationError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    if (memoryAssistant.enableMemoryRag && !memoryAssistant.enableMemory) {
                        Text(
                            stringResource(R.string.setting_memory_vector_model_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                item(key = "status") {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = if (!memoryAssistant.enableMemory && !memoryAssistant.enableMemoryRag) {
                                stringResource(R.string.conversation_memory_disabled_desc)
                            } else if (!memoryAssistant.enableMemory && memoryAssistant.enableMemoryRag) {
                                stringResource(R.string.conversation_memory_rag_desc)
                            } else if (!extractionAvailable) {
                                stringResource(R.string.conversation_memory_extraction_model_required)
                            } else memoryExtractionStatusText(status),
                            style = MaterialTheme.typography.titleSmall,
                            color = if (extractionActive && status is MemoryExtractionStatus.Failed) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                        )
                        if (extractionActive && status is MemoryExtractionStatus.Failed) {
                            Text(
                                text = status.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                item(key = "memory-maintenance") { ClearAllMemoryButton() }
                if (visibleExcerpts.isNotEmpty()) {
                    item(key = "rag-title") {
                        TextButton(onClick = { showConversationExcerpts = !showConversationExcerpts }) {
                            Text(
                                stringResource(
                                    if (showConversationExcerpts) R.string.conversation_memory_excerpts_collapse
                                    else R.string.conversation_memory_excerpts_expand,
                                    visibleExcerpts.size,
                                )
                            )
                        }
                    }
                    if (showConversationExcerpts) {
                        items(visibleExcerpts, key = { "rag-${it.id}" }) { record ->
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    stringResource(if (record.embedding != null) R.string.conversation_memory_indexed
                                        else R.string.conversation_memory_lexical),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(record.content, style = MaterialTheme.typography.bodySmall, maxLines = 4, overflow = TextOverflow.Ellipsis)
                                Row {
                                    TextButton(onClick = { preview = MemoryPreviewContent("对话片段与索引", record.content, record.id,
                                        record.contentHash, record.sourceMessageId, record.embedding, record.embeddingModelId, record.embeddingDimension) }) { Text("全屏预览") }
                                    TextButton(onClick = { deleteExcerpt = record }) { Text("删除") }
                                }
                            }
                        }
                    }
                }
                if (memories.isEmpty() && visibleExcerpts.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            text = stringResource(R.string.chat_page_recent_memories_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    items(memories, key = AssistantMemory::id) { memory ->
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(
                                text = stringResource(
                                    if (memory.type == MemoryType.EPISODIC) {
                                        R.string.assistant_page_memory_filter_episodic
                                    } else {
                                        R.string.assistant_page_memory_filter_fact
                                    }
                                ),
                                style = MaterialTheme.typography.labelMedium,
                                color = if (memory.type == MemoryType.EPISODIC) {
                                    MaterialTheme.colorScheme.tertiary
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                            )
                            Text(
                                text = memory.content,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis,
                            )
                            TextButton(onClick = {
                                preview = MemoryPreviewContent("记忆详情", memory.content, memory.id.toString(), memoryContentHash(memory.content), memory.sourceConversationId)
                                previewScope.launch {
                                    try {
                                        loadMemoryPreview(memory.id)?.let { record ->
                                            if (preview?.id == memory.id.toString()) preview = MemoryPreviewContent("记忆详情", record.memory.content,
                                                record.memory.id.toString(), memoryContentHash(record.memory.content), record.memory.sourceConversationId,
                                                record.embedding, record.embeddingModelId, record.embeddingDimension,
                                                identity = memoryIdentityDescription(record), sources = record.sources)
                                        }
                                    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                                    catch (_: Exception) { operationError = "索引详情读取失败，请重试" }
                                }
                            }) { Text("全屏预览") }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (status is MemoryExtractionStatus.Failed && extractionActive) {
                    TextButton(onClick = onRetry) {
                        Text(stringResource(R.string.chat_page_memory_retry))
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.confirm))
                }
            }
        },
    )
    preview?.let { shown -> MemoryFullscreenPreview(shown, onOpenSource = onOpenMemorySource,
        rebuildIndex = shown.id.toIntOrNull()?.let { id -> { rebuildMemoryIndex(id)
            loadMemoryPreview(id)?.let { record -> if (preview?.id == shown.id) preview = shown.copy(
                vector = record.embedding, modelId = record.embeddingModelId, dimension = record.embeddingDimension,
                identity = memoryIdentityDescription(record), sources = record.sources) }
        } }) { preview = null } }
    deleteExcerpt?.let { record -> AlertDialog(onDismissRequest = { if (!deleting) deleteExcerpt = null },
        title = { Text("删除片段及索引？") }, text = { Text("聊天原文会保留。本条片段和向量会移除，同一内容版本不会被后台自动重新加入。") },
        confirmButton = { TextButton(enabled = !deleting, onClick = {
            deleting = true
            previewScope.launch {
                try { onDeleteExcerpt(record.id); deleteExcerpt = null; operationError = null }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { operationError = "删除失败，请重试"; deleteExcerpt = null }
                finally { deleting = false }
            }
        }) { Text(if (deleting) "删除中…" else "删除") } }, dismissButton = { TextButton(enabled = !deleting, onClick = { deleteExcerpt = null }) { Text("取消") } }) }
}

@Composable
private fun memoryExtractionStatusText(status: MemoryExtractionStatus): String = when (status) {
    MemoryExtractionStatus.Idle -> stringResource(R.string.chat_page_memory_status_ready)
    is MemoryExtractionStatus.Running -> stringResource(R.string.chat_page_memory_status_running)
    is MemoryExtractionStatus.Queued -> stringResource(
        R.string.chat_page_memory_status_queued,
        status.pendingUserTurns,
    )
    is MemoryExtractionStatus.Completed -> stringResource(
        R.string.chat_page_memory_status_saved,
        status.savedCount,
    )
    is MemoryExtractionStatus.NoChanges -> stringResource(
        when (status.reason) {
            MemoryExtractionOutcome.Reason.NO_COMPLETE_TURN ->
                R.string.chat_page_memory_status_waiting
            MemoryExtractionOutcome.Reason.NOTHING_TO_REMEMBER ->
                R.string.chat_page_memory_status_no_changes
            MemoryExtractionOutcome.Reason.ALL_DUPLICATES ->
                R.string.chat_page_memory_status_up_to_date
        }
    )
    is MemoryExtractionStatus.Failed -> stringResource(R.string.chat_page_memory_status_failed)
}

@Composable
private fun ConversationLoadContent(
    state: ConversationLoadState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state == ConversationLoadState.Ready) return
    if (state == ConversationLoadState.Loading) {
        ConversationLoadingSkeleton(modifier)
        return
    }
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (state) {
                ConversationLoadState.Loading -> Unit

                is ConversationLoadState.Failed -> {
                    Text(
                        text = stringResource(R.string.chat_page_load_conversation_failed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(onClick = onRetry) {
                        Text(stringResource(R.string.chat_page_retry_load_conversation))
                    }
                }

                ConversationLoadState.Ready -> Unit
            }
        }
    }
}

@Composable
private fun ActiveModesBar(
    activeModes: List<ActiveMode>,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (activeModes.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.chat_active_modes),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            activeModes.forEach { active ->
                Text(
                    text = active.injection.name,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun PromptInjectionDiagnosticsDialog(
    diagnostics: PromptInjectionDiagnostics?,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.prompt_diagnostics_title)) },
        text = {
            if (diagnostics == null || diagnostics.entries.isEmpty()) {
                Text(stringResource(R.string.prompt_diagnostics_empty))
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        Text(
                            stringResource(
                                R.string.prompt_diagnostics_summary,
                                diagnostics.userTurn,
                                diagnostics.totalEstimatedTokens,
                            )
                        )
                    }
                    items(diagnostics.entries, key = { "${it.lorebookId}:${it.entryId}" }) { entry ->
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = "${entry.lorebookName} · ${entry.entryName}",
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                text = stringResource(promptDiagnosticStatus(entry.status)),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (entry.status == LorebookEntryStatus.USED ||
                                    entry.status == LorebookEntryStatus.ACTIVE_FROM_PREVIOUS_TURN
                                ) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (entry.matchedTerms.isNotEmpty()) {
                                Text(
                                    stringResource(
                                        R.string.prompt_diagnostics_matched,
                                        entry.matchedTerms.joinToString(", "),
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            entry.detail?.let { Text(if (it == "truncated") "内容已按预算截断" else it, color = MaterialTheme.colorScheme.tertiary) }
                            if (entry.remainingActiveTurns > 0 || entry.remainingCooldownTurns > 0) Text("后续持续 ${entry.remainingActiveTurns} 轮 · 冷却 ${entry.remainingCooldownTurns} 轮", style = MaterialTheme.typography.bodySmall)
                            var showContent by remember(entry.entryId, entry.injectedContent) { mutableStateOf(false) }
                            if (entry.injectedContent != null) {
                                TextButton(onClick = { showContent = !showContent }) { Text(if (showContent) "收起实际注入正文" else "查看实际注入正文") }
                                if (showContent) Text(entry.injectedContent)
                            }
                            Text(
                                stringResource(
                                    R.string.prompt_diagnostics_injection,
                                    promptInjectionPositionLabel(entry.position),
                                    entry.estimatedTokens,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.confirm)) }
        },
    )
}

private fun promptDiagnosticStatus(status: LorebookEntryStatus): Int = when (status) {
    LorebookEntryStatus.USED -> R.string.prompt_diagnostics_used
    LorebookEntryStatus.ACTIVE_FROM_PREVIOUS_TURN -> R.string.prompt_diagnostics_sticky
    LorebookEntryStatus.NOT_MATCHED -> R.string.prompt_diagnostics_not_matched
    LorebookEntryStatus.PROBABILITY_MISSED -> R.string.prompt_diagnostics_probability_missed
    LorebookEntryStatus.COOLDOWN -> R.string.prompt_diagnostics_cooldown
    LorebookEntryStatus.BUDGET_EXCEEDED -> R.string.prompt_diagnostics_budget_exceeded
    LorebookEntryStatus.INVALID_EXPRESSION -> R.string.prompt_diagnostics_invalid_expression
}

@Composable
private fun promptInjectionPositionLabel(position: InjectionPosition): String = when (position) {
    InjectionPosition.BEFORE_SYSTEM_PROMPT -> stringResource(R.string.prompt_page_position_before_system)
    InjectionPosition.AFTER_SYSTEM_PROMPT -> stringResource(R.string.prompt_page_position_after_system)
    InjectionPosition.TOP_OF_CHAT -> stringResource(R.string.prompt_page_position_top_of_chat)
    InjectionPosition.BOTTOM_OF_CHAT -> stringResource(R.string.prompt_page_position_bottom_of_chat)
    InjectionPosition.AT_DEPTH -> stringResource(R.string.prompt_page_position_at_depth)
}

private fun suggestionActionInput(
    context: Context,
    suggestion: ChatSuggestionItem,
): String {
    val payload = suggestion.payload.ifBlank { suggestion.text }.trim()
    val resource = when (suggestion.action) {
        ChatSuggestionAction.SEARCH_WEB -> R.string.chat_page_suggestion_request_web
        ChatSuggestionAction.SEARCH_KNOWLEDGE -> R.string.chat_page_suggestion_request_knowledge
        ChatSuggestionAction.SEARCH_MEMORY -> R.string.chat_page_suggestion_request_memory
        ChatSuggestionAction.SEARCH_CONVERSATIONS -> R.string.chat_page_suggestion_request_conversations
        ChatSuggestionAction.ASK_USER -> R.string.chat_page_suggestion_request_ask
        ChatSuggestionAction.WORKSPACE -> R.string.chat_page_suggestion_request_workspace
        ChatSuggestionAction.USE_SKILL -> R.string.chat_page_suggestion_request_skill
        ChatSuggestionAction.MCP -> R.string.chat_page_suggestion_request_mcp
        ChatSuggestionAction.INSERT_TEXT,
        ChatSuggestionAction.IMAGE_DRAFT,
        ChatSuggestionAction.COPY_TEXT,
        ChatSuggestionAction.SAVE_QUICK_MESSAGE,
        ChatSuggestionAction.CREATE_BRANCH -> return payload
    }
    return context.getString(resource, payload)
}

@Composable
private fun ChatFilesPickerSheet(
    inputState: ChatInputState,
    setting: Settings,
    conversation: Conversation,
    assistant: Assistant,
    vm: ChatVM,
    contextUsage: ChatContextUsage,
    promptInjectionDiagnostics: PromptInjectionDiagnostics?,
    memoryExtractionStatus: MemoryExtractionStatus,
    recentMemoryCount: Int,
    onShowRecentMemories: () -> Unit,
    onShowSuggestions: () -> Unit,
    onShowPromptDiagnostics: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val filesManager: FilesManager = koinInject()
    val scope = rememberCoroutineScope()
    var showInjectionSheet by remember { mutableStateOf(false) }
    var showCompressDialog by remember { mutableStateOf(false) }

    fun dismissAll() {
        showInjectionSheet = false
        showCompressDialog = false
        onDismiss()
    }

    val cameraPermission = rememberPermissionState(PermissionCamera)
    PermissionManager(permissionState = cameraPermission)

    var cameraOutputUri by remember { mutableStateOf<Uri?>(null) }
    var cameraOutputFile by remember { mutableStateOf<File?>(null) }
    val (_, launchCameraCrop) = useCropLauncher(
        onCroppedImageReady = { croppedUri ->
            inputState.addImages(filesManager.createChatFilesByContents(listOf(croppedUri)))
            dismissAll()
        },
        onCleanup = {
            cameraOutputFile?.delete()
            cameraOutputFile = null
            cameraOutputUri = null
        }
    )
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { captureSuccessful ->
        if (captureSuccessful && cameraOutputUri != null) {
            if (setting.displaySetting.skipCropImage) {
                val sourceUri = cameraOutputUri!!
                scope.launch {
                    inputState.addImages(filesManager.createChatFilesByContents(listOf(sourceUri)))
                    cameraOutputFile?.delete()
                    cameraOutputFile = null
                    cameraOutputUri = null
                    dismissAll()
                }
            } else {
                launchCameraCrop(cameraOutputUri!!)
            }
        } else {
            cameraOutputFile?.delete()
            cameraOutputFile = null
            cameraOutputUri = null
        }
    }
    val onLaunchCamera: () -> Unit = {
        if (cameraPermission.allRequiredPermissionsGranted) {
            cameraOutputFile = context.cacheDir.resolve("camera_${Uuid.random()}.jpg")
            cameraOutputUri = FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", cameraOutputFile!!
            )
            cameraLauncher.launch(cameraOutputUri!!)
        } else {
            cameraPermission.requestPermissions()
        }
    }

    var preCropTempFile by remember { mutableStateOf<File?>(null) }
    val (_, launchImageCrop) = useCropLauncher(
        onCroppedImageReady = { croppedUri ->
            inputState.addImages(filesManager.createChatFilesByContents(listOf(croppedUri)))
            dismissAll()
        },
        onCleanup = {
            preCropTempFile?.delete()
            preCropTempFile = null
        }
    )
    val imagePickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { selectedUris ->
            if (selectedUris.isNotEmpty()) {
                Log.d("ImagePickButton", "Selected URIs: $selectedUris")
                if (setting.displaySetting.skipCropImage) {
                    scope.launch {
                        inputState.addImages(filesManager.createChatFilesByContents(selectedUris))
                        dismissAll()
                    }
                } else if (selectedUris.size == 1) {
                    val tempFile = File(context.appTempFolder, "pick_temp_${System.currentTimeMillis()}.jpg")
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            runCatching {
                                val source = selectedUris.first()
                                // HEIF/HEIC（尤其 HDR HEIF）交给 UCrop 前先解码转为 JPEG，规避裁剪解码失败
                                val converted = ImageUtils.isHeifImage(context, source) &&
                                    ImageUtils.convertHeifToJpeg(context, source, tempFile)
                                if (!converted) {
                                    context.contentResolver.openInputStream(source)?.use { input ->
                                        tempFile.outputStream().use { output ->
                                            input.copyTo(output, bufferSize = 256 * 1024)
                                        }
                                    }
                                }
                            }.onFailure {
                                Log.e("ImagePickButton", "Failed to copy image to temp, falling back", it)
                            }
                        }
                        if (tempFile.isFile) {
                            preCropTempFile = tempFile
                            launchImageCrop(tempFile.toUri())
                        } else {
                            launchImageCrop(selectedUris.first())
                        }
                    }
                } else {
                    scope.launch {
                        inputState.addImages(filesManager.createChatFilesByContents(selectedUris))
                        dismissAll()
                    }
                }
            } else {
                Log.d("ImagePickButton", "No images selected")
            }
        }

    val videoPickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { selectedUris ->
            if (selectedUris.isNotEmpty()) {
                scope.launch {
                    inputState.addVideos(filesManager.createChatFilesByContents(selectedUris))
                    dismissAll()
                }
            }
        }

    val audioPickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { selectedUris ->
            if (selectedUris.isNotEmpty()) {
                scope.launch {
                    val localFiles = filesManager.createChatFilesByContents(selectedUris)
                    val midiFiles = mutableListOf<UIMessagePart.Document>()
                    val audioFiles = mutableListOf<Uri>()
                    localFiles.forEachIndexed { index, localFile ->
                        val sourceUri = selectedUris.getOrNull(index) ?: return@forEachIndexed
                        val fileName = filesManager.getFileNameFromUri(sourceUri) ?: "file"
                        val mime = filesManager.getFileMimeType(sourceUri) ?: "audio/*"
                        if (isMidiFileType(fileName, mime)) {
                            midiFiles += UIMessagePart.Document(localFile.toString(), fileName, mime)
                        } else {
                            audioFiles += localFile
                        }
                    }
                    inputState.addAudios(audioFiles)
                    inputState.addFiles(midiFiles)
                    dismissAll()
                }
            }
        }

    val filePickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) {
                scope.launch {
                    val documents = buildList {
                        uris.forEach { uri ->
                            val fileName = filesManager.getFileNameFromUri(uri) ?: "file"
                            val mime = filesManager.getFileMimeType(uri) ?: "text/plain"
                            if (isAllowedFileType(fileName, mime)) {
                                val localUri = filesManager.createChatFilesByContents(listOf(uri)).firstOrNull()
                                if (localUri == null) {
                                    toaster.show(
                                        context.getString(R.string.chat_input_file_read_failed, fileName),
                                        type = ToastType.Error,
                                    )
                                } else {
                                    add(UIMessagePart.Document(localUri.toString(), fileName, mime))
                                }
                            } else {
                                toaster.show(
                                    context.getString(R.string.chat_input_unsupported_file_type, fileName),
                                    type = ToastType.Error,
                                )
                            }
                        }
                    }
                    if (documents.isNotEmpty()) {
                        inputState.addFiles(documents)
                        dismissAll()
                    }
                }
            }
        }

    val filesSheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
    )
    AppearanceModalBottomSheet(
        sheetState = filesSheetState,
        onDismissRequest = { dismissAll() },
        modifier = Modifier.fillMaxHeight(0.88f),
    ) {
        Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState())) {
        ContextUsageSummary(usage = contextUsage)
        run {
            RecentMemoryMenuButton(
                status = memoryExtractionStatus,
                memoryCount = recentMemoryCount,
                onClick = onShowRecentMemories,
            )
        }
        TextButton(onClick = onShowSuggestions) { Text("聊天建议") }
        FilesPicker(
            conversation = conversation,
            state = inputState,
            assistant = assistant,
            mcpManager = vm.mcpManager,
            promptInjectionDiagnostics = if (
                setting.extensionManagementMode == ExtensionManagementMode.ENTERTAINMENT
            ) {
                promptInjectionDiagnostics
            } else {
                null
            },
            onShowPromptDiagnostics = if (
                setting.extensionManagementMode == ExtensionManagementMode.ENTERTAINMENT
            ) {
                onShowPromptDiagnostics
            } else {
                null
            },
            onRefreshRollingContext = { additionalPrompt, targetTokens ->
                vm.handleRefreshRollingContext(additionalPrompt, targetTokens)
            },
            onUpdateAssistant = {
                vm.updateSettings(
                    setting.copy(
                        assistants = setting.assistants.map { assistant ->
                            if (assistant.id == it.id) {
                                it
                            } else {
                                assistant
                            }
                        }
                    )
                )
            },
            onUpdateConversation = {
                vm.updateConversation(it)
                vm.saveConversationAsync()
            },
            showInjectionSheet = showInjectionSheet,
            onShowInjectionSheetChange = { showInjectionSheet = it },
            showCompressDialog = showCompressDialog,
            onShowCompressDialogChange = { showCompressDialog = it },
            onDismiss = { dismissAll() },
            onTakePic = onLaunchCamera,
            onPickImage = { imagePickerLauncher.launch("image/*") },
            onPickVideo = { videoPickerLauncher.launch("video/*") },
            onPickAudio = { audioPickerLauncher.launch("audio/*") },
            onPickFile = { filePickerLauncher.launch(arrayOf("*/*")) },
        )
        }
    }
}

@Composable
private fun BranchSourceBar(
    sourceTitle: String,
    sourceAvailable: Boolean?,
    settings: Settings,
    onOpenSource: () -> Unit,
) {
    val contentColor = if (settings.hasActiveChatBackground()) {
        LocalChatBackgroundForeground.current
            .takeUnless { it == Color.Unspecified }
            ?: MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = HugeIcons.Link01,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(14.dp),
        )
        androidx.compose.foundation.layout.Spacer(Modifier.width(6.dp))
        Text(
            text = stringResource(R.string.branch_source_label, sourceTitle),
            modifier = Modifier.weight(1f),
            color = contentColor,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        TextButton(
            onClick = onOpenSource,
            enabled = sourceAvailable == true,
        ) {
            Icon(
                imageVector = HugeIcons.ArrowTurnBackward,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
            )
            androidx.compose.foundation.layout.Spacer(Modifier.width(4.dp))
            Text(
                text = stringResource(
                    if (sourceAvailable == false) {
                        R.string.branch_source_unavailable
                    } else {
                        R.string.branch_source_open
                    }
                ),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun TopBar(
    settings: Settings,
    conversation: Conversation,
    titleEditable: Boolean,
    hazeState: HazeState,
    drawerState: DrawerState,
    bigScreen: Boolean,
    previewMode: Boolean,
    onClickMenu: () -> Unit,
    onNewChat: () -> Unit,
    onUpdateTitle: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val titleState = key(conversation.id) {
        useEditState<String> {
            onUpdateTitle(it)
        }
    }
    val displaySetting = settings.displaySetting
    val appearanceCapabilities = LocalAdvancedAppearanceCapabilities.current
    val currentAssistant = settings.getCurrentAssistant()
    val backgroundForeground = if (settings.hasActiveChatBackground()) {
        LocalChatBackgroundForeground.current
            .takeUnless { it == Color.Unspecified }
            ?: MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val useTopBarBlur = displaySetting.enableTopBarBlur &&
        settings.advancedAppearanceSetting.enableTopBarPerformanceEffects &&
        appearanceCapabilities.supportsRealtimeBlur &&
        settings.hasActiveChatBackground()
    val topBarEffectsDisabled = !settings.advancedAppearanceSetting.enableTopBarPerformanceEffects
    val topBarBlurRadius = appearanceCapabilities.limitLiveBlur(displaySetting.topBarBlurRadius)
    val topBarHazeStyle = me.rerere.rikkahub.ui.components.ui.backgroundOnlyBlurStyle(topBarBlurRadius)
    val topBarColor = when {
        !topBarEffectsDisabled && settings.hasActiveChatBackground() ->
            MaterialTheme.colorScheme.surface.copy(
            alpha = LocalAppearanceSurfaceOpacityPolicy.current.topBar
        )
        topBarEffectsDisabled && settings.hasActiveChatBackground() ->
            MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 1f)
        else -> Color.Transparent
    }

    val topBarContentColor = me.rerere.rikkahub.ui.components.ui.rememberTintedSurfaceForeground(
        topBarColor, topBarColor.alpha, backgroundForeground,
        LocalAppearanceBackground.current?.readability?.backgrounds,
        me.rerere.rikkahub.ui.theme.currentTextPaletteSeed(),
    )

    TopAppBar(
        modifier = if (useTopBarBlur && topBarBlurRadius > 0f) {
            Modifier.hazeBlur(
                input = HazeInput.Sources(hazeState),
                style = topBarHazeStyle,
            )
        } else {
            Modifier
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = topBarColor,
            scrolledContainerColor = topBarColor,
            navigationIconContentColor = topBarContentColor,
            titleContentColor = topBarContentColor,
            actionIconContentColor = topBarContentColor,
        ),
        navigationIcon = {
            if (!bigScreen) {
                IconButton(
                    onClick = {
                        scope.launch { drawerState.open() }
                    }
                ) {
                    Icon(HugeIcons.Menu03, "Messages")
                }
            }
        },
        title = {
            val editTitleWarning = stringResource(R.string.chat_page_edit_title_warning)
            Surface(
                onClick = {
                    if (conversation.messageNodes.isNotEmpty()) {
                        titleState.open(conversation.title)
                    } else {
                        toaster.show(editTitleWarning, type = ToastType.Warning)
                    }
                },
                enabled = titleEditable,
                color = Color.Transparent,
            ) {
                Column {
                    val model = settings.getCurrentChatModel()
                    val provider = model?.findProvider(providers = settings.providers, checkOverwrite = false)
                    Text(
                        text = conversation.title.ifBlank { stringResource(R.string.chat_page_new_chat) },
                        maxLines = 1,
                        style = MaterialTheme.typography.bodyMedium,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (model != null && provider != null) {
                        Text(
                            text = "${currentAssistant.name.ifBlank { stringResource(R.string.assistant_page_default_assistant) }} / ${model.displayName} (${provider.name})",
                            overflow = TextOverflow.Ellipsis,
                            maxLines = 1,
                            color = topBarContentColor,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        },
        actions = {
            IconButton(
                onClick = {
                    onClickMenu()
                }
            ) {
                Icon(if (previewMode) HugeIcons.Cancel01 else HugeIcons.LeftToRightListBullet, "Chat Options")
            }

            IconButton(
                onClick = {
                    onNewChat()
                }
            ) {
                Icon(HugeIcons.MessageAdd01, "New Message")
            }
        },
    )
    titleState.EditStateContent { title, onUpdate ->
        AlertDialog(
            onDismissRequest = {
                titleState.dismiss()
            },
            title = {
                Text(stringResource(R.string.chat_page_edit_title))
            },
            text = {
                OutlinedTextField(
                    value = title,
                    onValueChange = onUpdate,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        titleState.confirm()
                    }
                ) {
                    Text(stringResource(R.string.chat_page_save))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        titleState.dismiss()
                    }
                ) {
                    Text(stringResource(R.string.chat_page_cancel))
                }
            }
        )
    }
}
