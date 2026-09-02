package me.rerere.rikkahub.ui.pages.chat

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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PermanentNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.adaptive.currentWindowDpSize
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.android.appTempFolder
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowTurnBackward
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.LeftToRightListBullet
import me.rerere.hugeicons.stroke.Link01
import me.rerere.hugeicons.stroke.Menu03
import me.rerere.hugeicons.stroke.MessageAdd01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.ExtensionManagementMode
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.data.datastore.hasActiveChatBackground
import me.rerere.rikkahub.data.datastore.resolveChatBackground
import me.rerere.rikkahub.data.ai.transformers.DocumentAsPromptTransformer
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.ActiveMode
import me.rerere.rikkahub.data.model.LorebookEntryStatus
import me.rerere.rikkahub.data.model.InjectionPosition
import me.rerere.rikkahub.data.model.PromptInjectionDiagnostics
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
import me.rerere.rikkahub.ui.theme.LocalChatBackgroundForeground
import me.rerere.rikkahub.ui.theme.BackgroundReadabilityTheme
import me.rerere.rikkahub.ui.theme.rememberChatBackgroundForeground
import me.rerere.rikkahub.ui.hooks.ChatInputState
import me.rerere.rikkahub.ui.hooks.EditStateContent
import me.rerere.rikkahub.ui.hooks.useEditState
import me.rerere.rikkahub.utils.ImageUtils
import me.rerere.rikkahub.utils.base64Decode
import me.rerere.rikkahub.utils.isAllowedFileType
import me.rerere.rikkahub.utils.isMidiFileType
import me.rerere.rikkahub.utils.navigateToChatPage
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf
import java.io.File
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

    // 初始化输入状态（处理传入的 files 和 text 参数）
    LaunchedEffect(files, text) {
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
        text?.base64Decode()?.let { decodedText ->
            if (decodedText.isNotEmpty()) {
                inputState.setMessageText(decodedText)
            }
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
    LaunchedEffect(id, nodeId, conversationLoadState, conversation.messageNodes.size) {
        if (conversationLoadState == ConversationLoadState.Ready &&
            !vm.chatListInitialized &&
            conversation.messageNodes.isNotEmpty()
        ) {
            if (nodeId != null) {
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
    val appearanceForeground = rememberChatBackgroundForeground(
        background = resolvedChatBackground.background,
        backgroundOpacity = resolvedChatBackground.opacity,
        useGradientBackground = resolvedChatBackground.useGradientBackground,
    )
    val assistantBackgroundSpec = AppearanceBackgroundSpec(
        background = resolvedChatBackground.background,
        opacity = resolvedChatBackground.opacity,
        blurRadius = resolvedChatBackground.blurRadius,
        useGradientBackground = resolvedChatBackground.useGradientBackground,
        foreground = appearanceForeground,
    )

    CompositionLocalProvider(
        LocalAppearanceBackground provides assistantBackgroundSpec,
        LocalChatBackgroundForeground provides appearanceForeground,
    ) {
        BackgroundReadabilityTheme(
            active = setting.hasActiveChatBackground(),
            foreground = appearanceForeground,
        ) {
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
                                drawerState.close()
                                navController.navigate(destination)
                            }
                        },
                    )
                }
            ) {
                val drawerOccludesContent = drawerState.currentValue != DrawerValue.Closed ||
                    drawerState.targetValue != DrawerValue.Closed
                if (drawerOccludesContent) {
                    AssistantBackground(
                        setting = setting,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
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
    errors: List<ChatError>,
    branchSourceAvailable: Boolean?,
    onDismissError: (Uuid) -> Unit,
    onClearAllErrors: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val selectModelRequired = stringResource(R.string.chat_page_select_model_required)
    val createForkFailed = stringResource(R.string.create_fork_failed)
    val workspaceRepository: WorkspaceRepository = koinInject()
    var previewMode by rememberSaveable(conversation.id) { mutableStateOf(false) }
    val assistant = setting.getCurrentAssistant()
    val contextCapacityTokens = currentChatModel?.contextWindowTokens?.takeIf { it > 0 }
    val contextUsage by produceState(
        initialValue = ChatContextUsage(
            usedTokens = 0,
            capacityTokens = contextCapacityTokens,
            isEstimated = true,
        ),
        conversation.id,
        conversation.currentMessages,
        conversation.rollingContextSummary,
        contextCapacityTokens,
    ) {
        val contextMessages = DocumentAsPromptTransformer.transformDocumentContents(
            conversation.currentMessages,
        )
        value = withContext(Dispatchers.Default) {
            calculateChatContextUsage(
                messages = contextMessages,
                rollingContextSummary = conversation.rollingContextSummary,
                capacityTokens = contextCapacityTokens,
            )
        }
    }
    var showFilesSheet by remember(conversation.id) { mutableStateOf(false) }
    var showPromptDiagnostics by remember(conversation.id) { mutableStateOf(false) }
    var forkingMessageId by remember(conversation.id) { mutableStateOf<Uuid?>(null) }
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
        ) {
            AssistantBackground(
                setting = setting,
                modifier = Modifier
                    .hazeSource(chatChromeHazeState)
                    .hazeSource(navigationHazeState),
            )
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
                    onLongSendClick = {
                        if (inputState.isEditing()) {
                            vm.handleMessageEdit(
                                parts = inputState.getContents(),
                                messageId = inputState.editingMessage!!,
                            )
                        } else {
                            vm.handleMessageSend(content = inputState.getContents(), answer = false)
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
                    )
                }
            },
            containerColor = Color.Transparent,
        ) { innerPadding ->
            AnimatedContent(
                targetState = ConversationContentKey(
                    conversationId = conversation.id,
                    stage = conversationLoadState.contentStage(),
                ),
                transitionSpec = {
                    fadeIn(animationSpec = tween(durationMillis = 180, delayMillis = 40)) togetherWith
                        fadeOut(animationSpec = tween(durationMillis = 120))
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
                onClickSuggestion = { suggestion ->
                    inputState.editingMessage = null
                    inputState.setMessageText(suggestion)
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
        }
    }
}

@Composable
private fun ConversationLoadContent(
    state: ConversationLoadState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state == ConversationLoadState.Ready) return
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
                ConversationLoadState.Loading -> {
                    CircularProgressIndicator()
                    Text(
                        text = stringResource(R.string.chat_page_loading_conversation),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

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

@Composable
private fun ChatFilesPickerSheet(
    inputState: ChatInputState,
    setting: Settings,
    conversation: Conversation,
    assistant: Assistant,
    vm: ChatVM,
    contextUsage: ChatContextUsage,
    promptInjectionDiagnostics: PromptInjectionDiagnostics?,
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
        ContextUsageSummary(usage = contextUsage)
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
    val topBarContentColor = if (settings.hasActiveChatBackground()) {
        LocalChatBackgroundForeground.current
            .takeUnless { it == Color.Unspecified }
            ?: MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val useTopBarBlur = displaySetting.enableTopBarBlur &&
        appearanceCapabilities.supportsRealtimeBlur &&
        settings.hasActiveChatBackground()
    val topBarHazeStyle = HazeBlurStyle.Material3 {
        blurRadius(
            appearanceCapabilities.limitLiveBlur(
                displaySetting.topBarBlurRadius.coerceIn(0f, 40f)
            ).dp
        )
    }
    val topBarColor = if (useTopBarBlur) {
        MaterialTheme.colorScheme.surface.copy(
            alpha = displaySetting.topBarSurfaceOpacity.coerceIn(0f, 1f)
        )
    } else {
        Color.Transparent
    }

    TopAppBar(
        modifier = if (useTopBarBlur) {
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
