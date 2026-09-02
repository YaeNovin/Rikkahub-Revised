package me.rerere.rikkahub.service

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderRetryController
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.inferContextWindowTokens
import me.rerere.ai.provider.retryProviderRequest
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.canResumeToolExecution
import me.rerere.ai.ui.finishReasoning
import me.rerere.ai.ui.isEmptyInputMessage
import me.rerere.common.android.Logging
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.GenerationChunk
import me.rerere.rikkahub.data.ai.GenerationHandler
import me.rerere.rikkahub.data.ai.MAX_GENERATION_RETRY_COUNT
import me.rerere.rikkahub.data.ai.MAX_GENERATION_RETRY_DURATION_SECONDS
import me.rerere.rikkahub.data.ai.MAX_GENERATION_RETRY_INTERVAL_SECONDS
import me.rerere.rikkahub.data.ai.MIN_GENERATION_RETRY_COUNT
import me.rerere.rikkahub.data.ai.MIN_GENERATION_RETRY_DURATION_SECONDS
import me.rerere.rikkahub.data.ai.MIN_GENERATION_RETRY_INTERVAL_SECONDS
import me.rerere.rikkahub.data.ai.NetworkRecoveryCoordinator
import me.rerere.rikkahub.data.ai.markInterruptedToolsForContinuation
import me.rerere.rikkahub.data.ai.shouldResumeInterruptedResponseAt
import me.rerere.rikkahub.data.ai.context.RollingContextPlan
import me.rerere.rikkahub.data.ai.context.RollingContextSummary
import me.rerere.rikkahub.data.ai.context.automaticRollingContextThreshold
import me.rerere.rikkahub.data.ai.context.coveredMessageCount
import me.rerere.rikkahub.data.ai.context.createRollingContextPlan
import me.rerere.rikkahub.data.ai.context.effectiveRollingContextThreshold
import me.rerere.rikkahub.data.ai.context.estimateTextTokens
import me.rerere.rikkahub.data.ai.context.isStillApplicableTo
import me.rerere.rikkahub.data.ai.context.rollingContextWindowStartIndex
import me.rerere.rikkahub.data.ai.context.splitTextForTokenBudget
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.tools.createConversationTools
import me.rerere.rikkahub.data.ai.tools.local.LocalTools
import me.rerere.rikkahub.data.ai.tools.createSearchTools
import me.rerere.rikkahub.data.ai.tools.createSkillTools
import me.rerere.rikkahub.data.ai.tools.createWorkspaceTools
import me.rerere.rikkahub.data.ai.tools.createWorkspaceLocalFileTools
import me.rerere.rikkahub.data.ai.tools.createWorkspaceLocalCommandTools
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.ai.transformers.Base64ImageToLocalFileTransformer
import me.rerere.rikkahub.data.ai.transformers.DocumentAsPromptTransformer
import me.rerere.rikkahub.data.ai.transformers.OcrTransformer
import me.rerere.rikkahub.data.ai.transformers.PlaceholderTransformer
import me.rerere.rikkahub.data.ai.transformers.PromptInjectionTransformer
import me.rerere.rikkahub.data.ai.transformers.PromptVariableResolutionContext
import me.rerere.rikkahub.data.ai.transformers.resolvePromptVariables
import me.rerere.rikkahub.data.ai.transformers.RegexOutputTransformer
import me.rerere.rikkahub.data.ai.transformers.TemplateTransformer
import me.rerere.rikkahub.data.ai.transformers.ThinkTagTransformer
import me.rerere.rikkahub.data.ai.transformers.TimeReminderTransformer
import me.rerere.rikkahub.data.ai.transformers.WorkspaceReminderTransformer
import me.rerere.rikkahub.data.ai.transforms.KnowledgeRetrievalTransformer
import me.rerere.rikkahub.data.ai.transforms.MemoryRetrievalTransformer
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.instantiatePresetMessages
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.WorkspaceFileOperationMode
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.PromptInjectionDiagnostics
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.replaceRegexes
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FolderRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.web.BadRequestException
import me.rerere.rikkahub.web.NotFoundException
import me.rerere.rikkahub.utils.applyPlaceholders
import me.rerere.workspace.WorkspaceShellStatus
import java.time.Instant
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

private const val TAG = "ChatService"
private const val STREAM_STATE_UPDATE_INTERVAL_MILLIS = 83L
private const val DEFAULT_COMPRESSION_INPUT_BUDGET_TOKENS = 24_000
private const val MIN_INTERMEDIATE_SUMMARY_TOKENS = 512
private const val MAX_INTERMEDIATE_SUMMARY_TOKENS = 2_048
private const val MAX_COMPRESSION_HIERARCHY_DEPTH = 4

private class InvalidRollingSummaryException(message: String) : Exception(message)

internal fun backgroundTextGenerationParams(
    model: Model,
    reasoningLevel: ReasoningLevel = ReasoningLevel.AUTO,
): TextGenerationParams = TextGenerationParams(
    model = model,
    reasoningLevel = reasoningLevel,
    customHeaders = model.customHeaders,
    customBody = model.customBodies,
)

internal fun shouldUseExternalWebSearch(assistant: Assistant, model: Model): Boolean {
    return assistant.enableWebSearch && BuiltInTools.Search !in model.tools
}

data class ChatError(
    val id: Uuid = Uuid.random(),
    val title: String? = null,
    val error: Throwable,
    val displayMessage: String,
    val diagnosticMessage: String,
    val conversationId: Uuid? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val solution: ChatErrorSolution? = null,
)

enum class ChatErrorSolution {
    CheckTitleModelSettings,
}

private val inputTransformers by lazy {
    listOf(
        TimeReminderTransformer,
        PromptInjectionTransformer,
        PlaceholderTransformer,
        DocumentAsPromptTransformer,
        OcrTransformer,
    )
}

private val outputTransformers by lazy {
    listOf(
        ThinkTagTransformer,
        Base64ImageToLocalFileTransformer,
        RegexOutputTransformer,
    )
}

internal suspend fun createForkConversationSnapshot(
    currentConversation: Conversation,
    messageId: Uuid,
    branchedAt: Instant = Instant.now(),
    copyPart: suspend (UIMessagePart) -> UIMessagePart,
): Conversation {
    val targetNodeIndex = currentConversation.messageNodes.indexOfFirst { node ->
        node.messages.any { it.id == messageId }
    }
    if (targetNodeIndex == -1) throw NotFoundException("Message not found")
    val targetMessageIndex = currentConversation.messageNodes[targetNodeIndex]
        .messages
        .indexOfFirst { it.id == messageId }

    val copiedNodes = currentConversation.messageNodes
        .subList(0, targetNodeIndex + 1)
        .mapIndexed { index, node ->
            val visibleMessage = if (index == targetNodeIndex) {
                node.messages[targetMessageIndex]
            } else {
                node.currentMessage
            }
            MessageNode(
                id = Uuid.random(),
                messages = listOf(
                    visibleMessage.copy(parts = visibleMessage.parts.map { copyPart(it) })
                ),
                selectIndex = 0,
                isFavorite = false,
            )
        }
    val currentUserTurn = currentConversation.currentMessages.count { it.role == MessageRole.USER }
    val forkUserTurn = copiedNodes.count { it.currentMessage.role == MessageRole.USER }
    val rebasedTemporaryModes = currentConversation.temporaryModeInjections.mapNotNull { (id, expiresAt) ->
        val remainingTurns = expiresAt - currentUserTurn
        if (remainingTurns > 0) id to (forkUserTurn + remainingTurns) else null
    }.toMap()

    return Conversation(
        id = Uuid.random(),
        assistantId = currentConversation.assistantId,
        messageNodes = copiedNodes,
        createAt = branchedAt,
        updateAt = branchedAt,
        customSystemPrompt = currentConversation.customSystemPrompt,
        modeInjectionIds = currentConversation.modeInjectionIds,
        lorebookIds = currentConversation.lorebookIds,
        temporaryModeInjections = rebasedTemporaryModes,
        // Sticky/cooldown state is derived from the active message history. Re-evaluate it for
        // the truncated branch instead of carrying state triggered after the fork point.
        lorebookRuntimeStates = emptyMap(),
        workspaceCwd = currentConversation.workspaceCwd,
        workspaceFileOperationMode = currentConversation.workspaceFileOperationMode,
        sourceConversationId = currentConversation.id,
        sourceMessageId = messageId,
        branchedAt = branchedAt,
        sourceConversationTitle = currentConversation.title,
    )
}

internal suspend fun UIMessagePart.copyForFork(
    copyFileUrl: suspend (String) -> String,
): UIMessagePart = when (this) {
    is UIMessagePart.Image -> copy(url = copyFileUrl(url))
    is UIMessagePart.Document -> copy(url = copyFileUrl(url))
    is UIMessagePart.Video -> copy(url = copyFileUrl(url))
    is UIMessagePart.Audio -> copy(url = copyFileUrl(url))
    is UIMessagePart.Tool -> copy(
        output = output.map { part -> part.copyForFork(copyFileUrl) },
    )
    else -> this
}

internal suspend fun copyForkAttachmentUrl(
    url: String,
    copyLocalFile: suspend (String) -> String?,
): String {
    if (!url.startsWith("file:")) return url
    return try {
        copyLocalFile(url) ?: url
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        url
    }
}

class ChatService(
    private val context: Application,
    private val appScope: AppScope,
    private val appEventBus: AppEventBus,
    private val settingsStore: SettingsStore,
    private val conversationRepo: ConversationRepository,
    private val memoryRepository: MemoryRepository,
    private val generationHandler: GenerationHandler,
    private val templateTransformer: TemplateTransformer,
    private val providerManager: ProviderManager,
    private val localTools: LocalTools,
    val mcpManager: McpManager,
    private val filesManager: FilesManager,
    private val skillManager: SkillManager,
    private val workspaceRepository: WorkspaceRepository,
    private val folderRepository: FolderRepository,
    private val knowledgeRetrievalTransformer: KnowledgeRetrievalTransformer,
    private val memoryRetrievalTransformer: MemoryRetrievalTransformer,
) {
    // workspace 系统提示注入 (依赖 workspaceRepository, 故在类内构造)
    private val workspaceReminderTransformer = WorkspaceReminderTransformer(workspaceRepository)

    // 统一会话管理
    private val sessions = ConcurrentHashMap<Uuid, ConversationSession>()
    private val _sessionsVersion = MutableStateFlow(0L)

    // 错误状态
    private val _errors = MutableStateFlow<List<ChatError>>(emptyList())
    val errors: StateFlow<List<ChatError>> = _errors.asStateFlow()

    fun addError(
        error: Throwable,
        conversationId: Uuid? = null,
        title: String? = null,
        solution: ChatErrorSolution? = null,
    ) {
        if (error is CancellationException) return
        val errorName = title ?: context.getString(R.string.error_title_operation)
        val displayMessage = context.formatChatError(error)
        val diagnosticMessage = error.toDiagnosticMessage()
        val chatError = ChatError(
            title = title,
            error = error,
            displayMessage = displayMessage,
            diagnosticMessage = diagnosticMessage,
            conversationId = conversationId,
            solution = solution,
        )
        _errors.update {
            it + chatError
        }
        Logging.logError(
            name = errorName,
            summary = displayMessage,
            details = diagnosticMessage,
            tag = TAG,
        )
    }

    fun dismissError(id: Uuid) {
        _errors.update { list -> list.filter { it.id != id } }
    }

    fun clearAllErrors() {
        _errors.value = emptyList()
    }

    // 生成完成流
    private val _generationDoneFlow = MutableSharedFlow<Uuid>()
    val generationDoneFlow: SharedFlow<Uuid> = _generationDoneFlow.asSharedFlow()

    fun cleanup() = runCatching {
        sessions.values.forEach { it.cleanup() }
        sessions.clear()
    }

    // ---- Session 管理 ----

    private fun getOrCreateSession(conversationId: Uuid): ConversationSession {
        return sessions.computeIfAbsent(conversationId) { id ->
            val settings = settingsStore.settingsFlow.value
            ConversationSession(
                id = id,
                initial = Conversation.ofId(
                    id = id,
                    assistantId = settings.getCurrentAssistant().id
                ),
                scope = appScope,
                onIdle = { removeSession(it) }
            ).also {
                _sessionsVersion.value++
                Log.i(TAG, "createSession: $id (total: ${sessions.size + 1})")
            }
        }
    }

    private fun removeSession(conversationId: Uuid) {
        val session = sessions[conversationId] ?: return
        if (session.isInUse) {
            Log.d(TAG, "removeSession: skipped $conversationId (still in use)")
            return
        }
        if (sessions.remove(conversationId, session)) {
            session.cleanup()
            _sessionsVersion.value++
            Log.i(TAG, "removeSession: $conversationId (remaining: ${sessions.size})")
        }
    }

    // ---- 引用管理 ----

    fun addConversationReference(conversationId: Uuid) {
        getOrCreateSession(conversationId).acquire()
    }

    fun removeConversationReference(conversationId: Uuid) {
        sessions[conversationId]?.release()
    }

    private fun launchWithConversationReference(
        conversationId: Uuid,
        block: suspend () -> Unit
    ): Job = appScope.launch {
        addConversationReference(conversationId)
        try {
            block()
        } finally {
            removeConversationReference(conversationId)
        }
    }

    // ---- 对话状态访问 ----

    fun getConversationFlow(conversationId: Uuid): StateFlow<Conversation> {
        return getOrCreateSession(conversationId).state
    }

    fun getGenerationJobStateFlow(conversationId: Uuid): Flow<Job?> {
        val session = sessions[conversationId] ?: return flowOf(null)
        return session.generationJob
    }

    fun getProcessingStatusFlow(conversationId: Uuid): StateFlow<String?> {
        val session = sessions[conversationId] ?: return MutableStateFlow(null)
        return session.processingStatus
    }

    fun getPromptInjectionDiagnosticsFlow(conversationId: Uuid): StateFlow<PromptInjectionDiagnostics?> {
        val session = sessions[conversationId] ?: return MutableStateFlow(null)
        return session.promptInjectionDiagnostics
    }

    fun getConversationJobs(): Flow<Map<Uuid, Job?>> {
        return _sessionsVersion.flatMapLatest {
            val currentSessions = sessions.values.toList()
            if (currentSessions.isEmpty()) {
                flowOf(emptyMap())
            } else {
                combine(currentSessions.map { s ->
                    s.generationJob.map { job -> s.id to job }
                }) { pairs ->
                    pairs.filter { it.second != null }.toMap()
                }
            }
        }
    }

    // ---- 初始化对话 ----

    suspend fun initializeConversation(conversationId: Uuid) {
        val session = getOrCreateSession(conversationId)
        val conversation = session.initializeOnce {
            conversationRepo.getConversationById(conversationId) ?: run {
                // 新建对话, 并添加预设消息
                val currentSettings = settingsStore.settingsFlowRaw.first()
                val assistant = currentSettings.getCurrentAssistant()
                Conversation.ofId(
                    id = conversationId,
                    assistantId = assistant.id,
                    newConversation = true
                ).updateCurrentMessages(instantiatePresetMessages(assistant.presetMessages))
            }
        }
        settingsStore.updateAssistant(conversation.assistantId)
    }

    // ---- 发送消息 ----

    fun sendMessage(conversationId: Uuid, content: List<UIMessagePart>, answer: Boolean = true) {
        if (content.isEmptyInputMessage()) return

        val session = getOrCreateSession(conversationId)
        val previousJob = session.getJob()
        previousJob?.cancel()

        val job = appScope.launch {
            try {
                runCatching { previousJob?.join() }
                initializeConversation(conversationId)
                finishInterruptedPendingTools(conversationId)

                val currentConversation = session.state.value
                val settings = settingsStore.settingsFlow.first()
                val assistant = settings.getAssistantById(currentConversation.assistantId)
                    ?: settings.getCurrentAssistant()
                val processedContent = preprocessUserInputParts(content, assistant)

                // 添加消息到列表
                val newConversation = currentConversation.copy(
                    messageNodes = currentConversation.messageNodes + UIMessage(
                        role = MessageRole.USER,
                        parts = processedContent,
                    ).toMessageNode(),
                )
                saveConversation(conversationId, newConversation)

                // 开始补全
                if (answer) {
                    handleMessageComplete(conversationId)
                }

                _generationDoneFlow.emit(conversationId)
            } catch (e: Exception) {
                e.printStackTrace()
                addError(e, conversationId, title = context.getString(R.string.error_title_send_message))
            }
        }
        session.setJob(job)
    }

    private fun preprocessUserInputParts(parts: List<UIMessagePart>, assistant: Assistant): List<UIMessagePart> {
        return parts.map { part ->
            when (part) {
                is UIMessagePart.Text -> {
                    part.copy(
                        text = part.text.replaceRegexes(
                            assistant = assistant,
                            scope = AssistantAffectScope.USER,
                            visual = false
                        )
                    )
                }

                else -> part
            }
        }
    }

    // ---- 重新生成消息 ----

    fun regenerateAtMessage(
        conversationId: Uuid,
        message: UIMessage,
        regenerateAssistantMsg: Boolean = true
    ) {
        val session = getOrCreateSession(conversationId)
        session.getJob()?.cancel()

        val job = appScope.launch {
            try {
                initializeConversation(conversationId)
                val conversation = session.state.value

                if (message.role == MessageRole.USER) {
                    // 如果是用户消息，则截止到当前消息
                    val node = conversation.getMessageNodeByMessage(message)
                    val indexAt = conversation.messageNodes.indexOf(node)
                    val newConversation = conversation.copy(
                        messageNodes = conversation.messageNodes.subList(0, indexAt + 1)
                    )
                    saveConversation(conversationId, newConversation)
                    handleMessageComplete(conversationId)
                } else {
                    if (regenerateAssistantMsg) {
                        val node = conversation.getMessageNodeByMessage(message)
                        val nodeIndex = conversation.messageNodes.indexOf(node)
                        handleMessageComplete(
                            conversationId,
                            messageRange = 0..<nodeIndex,
                        )
                    } else {
                        saveConversation(conversationId, conversation)
                    }
                }

                _generationDoneFlow.emit(conversationId)
            } catch (e: Exception) {
                addError(e, conversationId, title = context.getString(R.string.error_title_regenerate_message))
            }
        }

        session.setJob(job)
    }

    fun continueAtMessage(conversationId: Uuid, message: UIMessage) {
        val session = getOrCreateSession(conversationId)
        session.getJob()?.cancel()

        val job = appScope.launch {
            try {
                initializeConversation(conversationId)
                val conversation = session.state.value
                if (!conversation.shouldResumeInterruptedResponseAt(message)) return@launch
                val node = conversation.getMessageNodeByMessage(message) ?: return@launch
                val nodeIndex = conversation.messageNodes.indexOf(node)
                markInterruptedToolsForContinuation(conversationId)
                handleMessageComplete(
                    conversationId = conversationId,
                    messageRange = 0..nodeIndex,
                    resumeInterruptedResponse = true,
                )
                _generationDoneFlow.emit(conversationId)
            } catch (error: Exception) {
                addError(
                    error,
                    conversationId,
                    title = context.getString(R.string.error_title_continue_generation),
                )
            }
        }
        session.setJob(job)
    }

    // ---- 处理工具调用审批 ----

    fun handleToolApproval(
        conversationId: Uuid,
        toolCallId: String,
        approved: Boolean,
        reason: String = "",
        answer: String? = null,
    ) {
        val session = getOrCreateSession(conversationId)
        session.getJob()?.cancel()

        val job = appScope.launch {
            try {
                initializeConversation(conversationId)
                val newApprovalState = when {
                    answer != null -> ToolApprovalState.Answered(answer)
                    approved -> ToolApprovalState.Approved
                    else -> ToolApprovalState.Denied(reason)
                }
                var decisionApplied = false

                // Apply the decision to the latest in-memory snapshot under the same persistence
                // lock used by generation cleanup. This prevents an old cancelled Job from
                // restoring a stale Pending state after the user has already approved it.
                saveConversationUpdate(
                    conversationId = conversationId,
                    loadedConversation = session.state.value,
                ) { latestConversation ->
                    latestConversation.copy(
                        messageNodes = latestConversation.messageNodes.map { node ->
                            node.copy(
                                messages = node.messages.map { msg ->
                                    msg.copy(
                                        parts = msg.parts.map { part ->
                                            if (part is UIMessagePart.Tool &&
                                                part.toolCallId == toolCallId &&
                                                part.approvalState is ToolApprovalState.Pending
                                            ) {
                                                decisionApplied = true
                                                part.copy(approvalState = newApprovalState)
                                            } else {
                                                part
                                            }
                                        }
                                    )
                                }
                            )
                        }
                    )
                }

                // Ignore stale or duplicate UI callbacks once the tool has already been handled.
                if (!decisionApplied) return@launch

                // Check if there are still pending tools
                val hasPendingTools = session.state.value.messageNodes.any { node ->
                    node.currentMessage.parts.any { part ->
                        part is UIMessagePart.Tool && part.isPending
                    }
                }

                // Only continue generation when all pending tools are handled
                if (!hasPendingTools) {
                    handleMessageComplete(conversationId)
                }

                _generationDoneFlow.emit(conversationId)
            } catch (e: Exception) {
                addError(e, conversationId, title = context.getString(R.string.error_title_tool_approval))
            }
        }

        session.setJob(job)
    }

    // ---- 处理消息补全 ----

    private suspend fun handleMessageComplete(
        conversationId: Uuid,
        messageRange: ClosedRange<Int>? = null,
        resumeInterruptedResponse: Boolean = false,
    ) {
        val settings = settingsStore.settingsFlow.first()
        val initialConversation = getConversationFlow(conversationId).value
        val assistant = settings.getAssistantById(initialConversation.assistantId)
            ?: settings.getCurrentAssistant()
        val model = settings.findModelById(assistant.chatModelId ?: settings.chatModelId) ?: return

        val senderName = if (assistant.useAssistantAvatar) {
            assistant.name.ifEmpty { context.getString(R.string.assistant_page_default_assistant) }
        } else {
            model.displayName
        }
        val useExternalWebSearch = shouldUseExternalWebSearch(assistant, model)

        runCatching {
            var pendingStreamMessages: List<UIMessage>? = null
            var lastStreamStateUpdateAt = 0L

            fun publishPendingStreamMessages(force: Boolean = false): List<UIMessage>? {
                val messages = pendingStreamMessages ?: return null
                val now = SystemClock.uptimeMillis()
                if (!force && now - lastStreamStateUpdateAt < STREAM_STATE_UPDATE_INTERVAL_MILLIS) {
                    return null
                }

                val updatedConversation = getConversationFlow(conversationId).value
                    .updateCurrentMessages(messages)
                updateConversation(conversationId, updatedConversation)
                pendingStreamMessages = null
                lastStreamStateUpdateAt = now
                return messages
            }

            // reset suggestions
            updateConversation(conversationId, initialConversation.copy(chatSuggestions = emptyList()))

            // memory tool
            if (!model.abilities.contains(ModelAbility.TOOL)) {
                if (useExternalWebSearch || mcpManager.getAllAvailableTools().isNotEmpty()) {
                    addError(
                        IllegalStateException(context.getString(R.string.tools_warning)),
                        conversationId,
                        title = context.getString(R.string.error_title_tool_unavailable)
                    )
                }
            }

            // check invalid messages
            checkInvalidMessages(conversationId)
            val conversation = getConversationFlow(conversationId).value

            // start generating
            val session = getOrCreateSession(conversationId)
            val generationMessages = conversation.currentMessages.let {
                if (messageRange != null) {
                    it.subList(messageRange.start, messageRange.endInclusive + 1)
                } else {
                    it
                }
            }
            val preparedConversation = if (messageRange == null) {
                prepareRollingContextForGeneration(
                    conversationId = conversationId,
                    conversation = conversation,
                    assistant = assistant,
                    model = model,
                    settings = settings,
                    processingStatus = session.processingStatus,
                )
            } else {
                conversation
            }
            val contextGenerationMessages = DocumentAsPromptTransformer.transformDocumentContents(
                generationMessages,
            )
            val rollingSummary = preparedConversation.rollingContextSummary
                ?.takeIf { assistant.enableRollingContextCompression }
                ?.takeIf { it.coveredMessageCount(generationMessages) > 0 }
            val rollingSummaryMessageCount = rollingSummary?.coveredMessageCount(generationMessages) ?: 0
            val rollingThresholdTokens = automaticRollingContextThreshold(
                enabled = assistant.enableRollingContextCompression,
                configuredThresholdTokens = assistant.rollingContextCompressionThresholdTokens,
                modelContextWindowTokens = model.contextWindowTokens
                    ?: inferContextWindowTokens(model.modelId),
                maxOutputTokens = assistant.maxTokens,
            )
            val fallbackWindowStartIndex = rollingThresholdTokens?.takeIf { threshold ->
                messageRange == null &&
                createRollingContextPlan(
                    messages = contextGenerationMessages,
                    storedSummary = preparedConversation.rollingContextSummary,
                    thresholdTokens = threshold,
                ) != null
            }?.let { threshold ->
                rollingContextWindowStartIndex(contextGenerationMessages, threshold)
            } ?: 0
            val requestMessageStartIndex = maxOf(
                rollingSummaryMessageCount,
                fallbackWindowStartIndex,
            )
            generationHandler.generateText(
                settings = settings,
                model = model,
                processingStatus = session.processingStatus,
                conversationId = conversationId,
                messages = generationMessages,
                assistant = assistant,
                conversationSystemPrompt = conversation.customSystemPrompt,
                conversationModeInjectionIds = conversation.modeInjectionIds,
                conversationLorebookIds = conversation.lorebookIds,
                temporaryModeInjections = conversation.temporaryModeInjections,
                lorebookRuntimeStates = conversation.lorebookRuntimeStates,
                conversationUserTurn = conversation.currentMessages.count { it.role == MessageRole.USER },
                onPromptInjectionEvaluation = { evaluation ->
                    session.promptInjectionDiagnostics.value = evaluation.diagnostics
                    updateConversationState(conversationId) { current ->
                        val validModeIds = settings.modeInjections.mapTo(hashSetOf()) { it.id }
                        val activeTemporaryModes = current.temporaryModeInjections.filter { (id, expiresAt) ->
                            id in validModeIds && expiresAt > evaluation.diagnostics.userTurn
                        }
                        if (current.lorebookRuntimeStates == evaluation.runtimeStates &&
                            current.temporaryModeInjections == activeTemporaryModes
                        ) current else current.copy(
                            lorebookRuntimeStates = evaluation.runtimeStates,
                            temporaryModeInjections = activeTemporaryModes,
                        )
                    }
                },
                workspaceCwd = conversation.workspaceCwd,
                workspaceFileOperationMode = conversation.workspaceFileOperationMode,
                rollingContextSummary = rollingSummary?.content,
                requestMessageStartIndex = requestMessageStartIndex,
                resumeInterruptedResponse = resumeInterruptedResponse,
                memories = when {
                    !assistant.enableMemory -> emptyList()
                    assistant.useGlobalMemory -> memoryRepository.getGlobalMemories()
                    else -> memoryRepository.getMemoriesOfAssistant(assistant.id.toString())
                },
                inputTransformers = buildList {
                    addAll(inputTransformers)
                    add(templateTransformer)
                    add(workspaceReminderTransformer)
                    add(knowledgeRetrievalTransformer)
                    add(memoryRetrievalTransformer)
                },
                outputTransformers = outputTransformers,
                tools = buildList {
                    if (useExternalWebSearch) {
                        addAll(createSearchTools(settings))
                    }
                    addAll(localTools.getTools(assistant.localTools))
                    if (assistant.enableRecentChatsReference) {
                        addAll(createConversationTools(conversationRepo, assistant.id))
                    }
                    addAll(createWorkspaceToolsIfReady(assistant.workspaceId?.toString(), conversation.workspaceCwd))
                    val localCommandTools = if (
                        conversation.workspaceFileOperationMode == WorkspaceFileOperationMode.COMMANDS
                    ) {
                        createWorkspaceLocalCommandTools(
                            workspaceId = assistant.workspaceId?.toString(),
                            workspaceRepository = workspaceRepository,
                            cwd = conversation.workspaceCwd,
                        )
                    } else {
                        emptyList()
                    }
                    if (localCommandTools.isNotEmpty()) {
                        addAll(localCommandTools)
                    } else {
                        addAll(createWorkspaceLocalFileTools(assistant.workspaceId?.toString(), workspaceRepository))
                    }
                    if (assistant.enabledSkills.isNotEmpty()) {
                        addAll(
                            createSkillTools(
                                enabledSkills = assistant.enabledSkills,
                                allSkills = skillManager.listSkills(),
                            )
                        )
                    }
                    mcpManager.getAllAvailableTools().also { allTools ->
                        val invalidNames = allTools
                            .map { it.second }
                            .distinct()
                            .filter { name -> name.isEmpty() || !name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' } }
                        if (invalidNames.isNotEmpty()) {
                            addError(
                                error = IllegalStateException(
                                    context.getString(
                                        R.string.error_mcp_invalid_server_name,
                                        invalidNames.joinToString(", ")
                                    )
                                ),
                                conversationId = conversationId,
                            )
                            return
                        }
                    }.forEach { (serverId, serverName, tool) ->
                        add(
                            Tool(
                                name = "mcp__${serverName}__${tool.name}",
                                description = tool.description ?: "",
                                parameters = { tool.inputSchema },
                                needsApproval = { tool.needsApproval },
                                execute = {
                                    mcpManager.callTool(serverId, tool.name, it.jsonObject)
                                },
                            )
                        )
                    }
                },
            ).onCompletion { cause ->
                // The last chunk can arrive inside the throttle window. Serialize the final
                // flush with approval updates so a cancelled generation cannot overwrite a newer
                // user decision with an older message snapshot.
                val updatedConversation = withContext(NonCancellable) {
                    session.withPersistenceLock {
                        publishPendingStreamMessages(force = true)

                        // 可能被取消了，或者意外结束，兜底更新
                        val latestConversation = getConversationFlow(conversationId).value
                        // Provider/JSON failures are reported through the normal error path. Only
                        // a coroutine cancellation represents an interrupted generation; treating
                        // every exception as an interruption makes malformed tool JSON look like
                        // a user denial.
                        val interruptedMessageId = if (cause is CancellationException) {
                            latestConversation.currentMessages.lastOrNull()
                                ?.takeIf { it.role == MessageRole.ASSISTANT && it.finishedAt == null }
                                ?.id
                        } else {
                            null
                        }
                        val updated = latestConversation.copy(
                            messageNodes = latestConversation.messageNodes.map { node ->
                                node.copy(messages = node.messages.map { message ->
                                    if (message.id == interruptedMessageId) {
                                        message.markInterruptedToolsForContinuation()
                                    } else {
                                        message.finishReasoning()
                                    }
                                })
                            },
                            updateAt = Instant.now()
                        )
                        updateConversation(conversationId, updated)
                        updated
                    }
                }

                // 生成结束：取消 Live Update 通知，后台时发送完成通知
                appEventBus.emit(
                    AppEvent.ChatGenerationEnded(
                        conversationId = conversationId,
                        senderName = senderName,
                        contentPreview = updatedConversation.currentMessages.lastOrNull()
                            ?.toText()?.take(50)?.trim() ?: "",
                    )
                )
            }.collect { chunk ->
                when (chunk) {
                    is GenerationChunk.Messages -> {
                        // GenerationHandler emits the complete current message list. Retain only
                        // the newest list and publish at most once per UI interval.
                        pendingStreamMessages = chunk.messages
                        val publishedMessages = publishPendingStreamMessages()

                        // 通知等边缘副作用由 ChatNotificationManager 消费；
                        // tryEmit 不挂起，事件丢失只影响单次通知更新，不能反压生成链
                        publishedMessages?.lastOrNull()?.let { lastMessage ->
                            appEventBus.tryEmit(
                                AppEvent.ChatGenerationUpdate(conversationId, lastMessage, senderName)
                            )
                        }
                    }
                }
            }
        }.onFailure {
            saveConversation(conversationId, getConversationFlow(conversationId).value)
            // 兜底取消 Live Update 通知（生成开始前失败时 onCompletion 不会执行）
            appEventBus.tryEmit(AppEvent.ChatGenerationEnded(conversationId, senderName, null))

            it.printStackTrace()
            addError(it, conversationId, title = context.getString(R.string.error_title_generation))
            Logging.log(TAG, "handleMessageComplete: $it")
            Logging.log(TAG, it.stackTraceToString())
        }.onSuccess {
            val finalConversation = getConversationFlow(conversationId).value
            saveConversation(conversationId, finalConversation)

            launchWithConversationReference(conversationId) {
                generateTitle(conversationId, finalConversation)
            }
            launchWithConversationReference(conversationId) {
                generateSuggestion(conversationId, finalConversation)
            }
        }
    }

    private suspend fun resolvePromptVariables(
        settings: me.rerere.rikkahub.data.datastore.Settings,
        model: Model?,
        assistant: Assistant?,
        workspaceCwd: String? = null,
    ): Array<Pair<String, String>> {
        val workspace = assistant?.workspaceId?.toString()?.let { id ->
            workspaceRepository.getById(id)
        }
        return PromptVariableResolutionContext(
            settings = settings,
            model = model,
            assistant = assistant,
            workspace = workspace,
            workspaceCwd = workspaceCwd,
            context = context,
        ).resolvePromptVariables().toList().toTypedArray()
    }

    private suspend fun createWorkspaceToolsIfReady(workspaceId: String?, cwd: String? = null): List<Tool> {
        if (workspaceId.isNullOrBlank()) return emptyList()
        val workspace = workspaceRepository.getById(workspaceId) ?: return emptyList()
        if (workspace.shellStatus != WorkspaceShellStatus.READY.name) {
            Log.d(
                TAG,
                "createWorkspaceToolsIfReady: skip workspace tools, workspace=$workspaceId, status=${workspace.shellStatus}"
            )
            return emptyList()
        }
        return createWorkspaceTools(workspaceId, workspaceRepository, cwd)
    }

    // ---- 检查无效消息 ----

    private fun checkInvalidMessages(conversationId: Uuid) {
        val conversation = getConversationFlow(conversationId).value
        var messagesNodes = conversation.messageNodes

        // 移除无效 tool (未执行的 Tool)
        messagesNodes = messagesNodes.mapIndexed { _, node ->
            // Check for Tool type with non-executed tools
            val hasPendingTools = node.currentMessage.getTools().any { !it.isExecuted }

            if (hasPendingTools) {
                // Keep messages that are ready to resume, such as approved/denied/answered tools.
                val hasResumableTool = node.currentMessage.getTools().any {
                    !it.isExecuted && it.approvalState.canResumeToolExecution()
                }
                if (hasResumableTool) {
                    return@mapIndexed node
                }

                // If all tools are executed, it's valid
                val allToolsExecuted = node.currentMessage.getTools().all { it.isExecuted }
                if (allToolsExecuted && node.currentMessage.getTools().isNotEmpty()) {
                    return@mapIndexed node
                }

                // Remove messages that still have unresolved tool approvals.
                return@mapIndexed node.copy(
                    messages = node.messages.filter { it.id != node.currentMessage.id },
                    selectIndex = node.selectIndex - 1
                )
            }
            node
        }

        // 更新index
        messagesNodes = messagesNodes.map { node ->
            if (node.messages.isNotEmpty() && node.selectIndex !in node.messages.indices) {
                node.copy(selectIndex = 0)
            } else {
                node
            }
        }

        // 移除无效消息
        messagesNodes = messagesNodes.filter { it.messages.isNotEmpty() }

        updateConversation(conversationId, conversation.copy(messageNodes = messagesNodes))
    }

    private suspend fun finishInterruptedPendingTools(conversationId: Uuid) {
        val currentConversation = getConversationFlow(conversationId).value
        val lastNode = currentConversation.messageNodes.lastOrNull() ?: return
        val lastMessage = lastNode.currentMessage
        val updatedMessage = lastMessage.markInterruptedToolsForContinuation(
            forcePendingApprovals = true,
        )
        if (updatedMessage == lastMessage) {
            return
        }

        val updatedConversation = currentConversation.copy(
            messageNodes = currentConversation.messageNodes.dropLast(1) + lastNode.copy(
                messages = lastNode.messages.map { message ->
                    if (message.id == lastMessage.id) updatedMessage else message
                }
            )
        )
        saveConversation(conversationId, updatedConversation)
    }

    private suspend fun markInterruptedToolsForContinuation(conversationId: Uuid) {
        val currentConversation = getConversationFlow(conversationId).value
        val lastNode = currentConversation.messageNodes.lastOrNull() ?: return
        val lastMessage = lastNode.currentMessage
        val updatedMessage = lastMessage.markInterruptedToolsForContinuation()
        if (updatedMessage == lastMessage) return
        saveConversation(
            conversationId,
            currentConversation.copy(
                messageNodes = currentConversation.messageNodes.dropLast(1) + lastNode.copy(
                    messages = lastNode.messages.map { message ->
                        if (message.id == lastMessage.id) updatedMessage else message
                    },
                ),
            ),
        )
    }

    // ---- 生成标题 ----

    suspend fun generateTitle(
        conversationId: Uuid,
        conversation: Conversation,
        force: Boolean = false
    ) = withContext(Dispatchers.IO) {
        val shouldGenerate = when {
            force -> true
            conversation.title.isBlank() -> true
            else -> false
        }
        if (!shouldGenerate) return@withContext

        runCatching {
            val settings = settingsStore.settingsFlow.first()
            val model = settings.findModelById(settings.titleModelId, fallback = settings.fastModelId)
                ?: return@runCatching
            val assistant = settings.getAssistantById(conversation.assistantId)
                ?: settings.getCurrentAssistant()
            val provider = model.findProvider(settings.providers) ?: return@runCatching
            val promptVariables = resolvePromptVariables(
                settings = settings,
                model = model,
                assistant = assistant,
                workspaceCwd = conversation.workspaceCwd,
            )

            val providerHandler = providerManager.getProviderByType(provider)
            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(
                    UIMessage.user(
                        prompt = settings.titlePrompt.applyPlaceholders(
                            *promptVariables,
                            "content" to conversation.currentMessages
                                .takeLast(4).joinToString("\n\n") { it.summaryAsText(maxLength = 500) })
                    ),
                ),
                params = backgroundTextGenerationParams(model),
            )

            // The request can finish after messages or metadata have changed. Apply only the
            // generated title to the latest session state instead of saving the stale prompt input.
            updateConversationTitle(
                conversationId = conversationId,
                loadedConversation = conversation,
                title = result.message.toText().trim(),
            )
        }.onFailure {
            it.printStackTrace()
            addError(
                error = it,
                conversationId = conversationId,
                title = context.getString(R.string.error_title_generate_title),
                solution = ChatErrorSolution.CheckTitleModelSettings,
            )
        }
    }

    // ---- 生成建议 ----

    suspend fun generateSuggestion(
        conversationId: Uuid,
        conversation: Conversation,
    ) = withContext(Dispatchers.IO) {
        runCatching {
            val settings = settingsStore.settingsFlow.first()
            if (!settings.enableSuggestion) return@runCatching
            val model = settings.findModelById(settings.suggestionModelId, fallback = settings.fastModelId)
                ?: return@runCatching
            val assistant = settings.getAssistantById(conversation.assistantId)
                ?: settings.getCurrentAssistant()
            val provider = model.findProvider(settings.providers) ?: return@runCatching
            val promptVariables = resolvePromptVariables(
                settings = settings,
                model = model,
                assistant = assistant,
                workspaceCwd = conversation.workspaceCwd,
            )

            sessions[conversationId]?.let { session ->
                updateConversation(
                    conversationId,
                    session.state.value.copy(chatSuggestions = emptyList())
                )
            }

            val providerHandler = providerManager.getProviderByType(provider)
            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(
                    UIMessage.user(
                        settings.suggestionPrompt.applyPlaceholders(
                            *promptVariables,
                            "content" to conversation.currentMessages
                                .takeLast(8).joinToString("\n\n") { it.summaryAsText(maxLength = 500) }),
                    )
                ),
                params = backgroundTextGenerationParams(model),
            )
            val suggestions =
                result.message.toText().split("\n").map { it.trim() }
                    .filter { it.isNotBlank() }

            saveConversationUpdate(
                conversationId = conversationId,
                loadedConversation = conversation,
            ) { latestConversation ->
                latestConversation.copy(
                    chatSuggestions = suggestions.take(
                        10
                    )
                )
            }
        }.onFailure {
            it.printStackTrace()
        }
    }

    // ---- 压缩对话历史 ----

    /**
     * Rebuild the compact summary without modifying the user-visible message nodes.
     * This is the manual counterpart of the automatic threshold trigger.
     */
    suspend fun refreshRollingContext(
        conversationId: Uuid,
        conversation: Conversation,
        additionalPrompt: String,
        targetTokens: Int,
    ): Result<Unit> = runCatching {
        val settings = settingsStore.settingsFlow.first()
        val assistant = settings.getAssistantById(conversation.assistantId)
            ?: settings.getCurrentAssistant()
        val model = settings.findModelById(assistant.chatModelId ?: settings.chatModelId)
        val thresholdTokens = effectiveRollingContextThreshold(
            assistant.rollingContextCompressionThresholdTokens,
            model?.let { it.contextWindowTokens ?: inferContextWindowTokens(it.modelId) },
            assistant.maxTokens,
        )
        refreshRollingContextSummary(
            conversationId = conversationId,
            conversation = conversation,
            settings = settings,
            thresholdTokens = thresholdTokens,
            force = true,
            targetTokensOverride = targetTokens,
            additionalPrompt = additionalPrompt,
        ) ?: throw IllegalStateException(context.getString(R.string.chat_page_compress_not_enough_messages))
    }

    private suspend fun prepareRollingContextForGeneration(
        conversationId: Uuid,
        conversation: Conversation,
        assistant: Assistant,
        model: Model,
        settings: me.rerere.rikkahub.data.datastore.Settings,
        processingStatus: MutableStateFlow<String?>,
    ): Conversation {
        val thresholdTokens = automaticRollingContextThreshold(
            enabled = assistant.enableRollingContextCompression,
            configuredThresholdTokens = assistant.rollingContextCompressionThresholdTokens,
            modelContextWindowTokens = model.contextWindowTokens
                ?: inferContextWindowTokens(model.modelId),
            maxOutputTokens = assistant.maxTokens,
        ) ?: return conversation
        val contextMessages = DocumentAsPromptTransformer.transformDocumentContents(
            conversation.currentMessages,
        )
        if (
            createRollingContextPlan(
                messages = contextMessages,
                storedSummary = conversation.rollingContextSummary,
                thresholdTokens = thresholdTokens,
            ) == null
        ) {
            return conversation
        }

        val previousStatus = processingStatus.value
        return try {
            processingStatus.value = context.getString(R.string.chat_page_rolling_context_compressing)
            refreshRollingContextSummary(
                conversationId = conversationId,
                conversation = conversation,
                settings = settings,
                thresholdTokens = thresholdTokens,
                force = false,
                planningMessages = contextMessages,
            ) ?: conversation
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            addError(
                error = error,
                conversationId = conversationId,
                title = context.getString(R.string.error_title_compress_conversation),
            )
            conversation
        } finally {
            processingStatus.value = previousStatus
        }
    }

    private suspend fun refreshRollingContextSummary(
        conversationId: Uuid,
        conversation: Conversation,
        settings: me.rerere.rikkahub.data.datastore.Settings,
        thresholdTokens: Int,
        force: Boolean,
        targetTokensOverride: Int? = null,
        additionalPrompt: String = "",
        planningMessages: List<UIMessage>? = null,
    ): Conversation? {
        val messagesForPlanning = planningMessages
            ?: DocumentAsPromptTransformer.transformDocumentContents(conversation.currentMessages)
        val plan = createRollingContextPlan(
            messages = messagesForPlanning,
            storedSummary = conversation.rollingContextSummary,
            thresholdTokens = thresholdTokens,
            force = force,
            targetTokensOverride = targetTokensOverride,
        ) ?: return null
        val assistant = settings.getAssistantById(conversation.assistantId)
            ?: settings.getCurrentAssistant()
        val summary = generateCompressedSummary(
            settings = settings,
            assistant = assistant,
            workspaceCwd = conversation.workspaceCwd,
            content = plan.toCompressionContent(),
            targetTokens = plan.targetTokens,
            additionalPrompt = additionalPrompt,
        )
        val latestConversation = getConversationFlow(conversationId).value
        val latestPlanningMessages = DocumentAsPromptTransformer.transformDocumentContents(
            latestConversation.currentMessages,
        )
        if (
            latestConversation.rollingContextSummary != conversation.rollingContextSummary ||
            !plan.isStillApplicableTo(latestPlanningMessages)
        ) {
            throw InvalidRollingSummaryException(
                "Conversation changed while compression was running",
            )
        }
        val updatedConversation = latestConversation.copy(
            rollingContextSummary = RollingContextSummary(
                content = summary,
                sourceMessageIds = plan.sourceMessageIds,
                updatedAtMillis = System.currentTimeMillis(),
            ),
        )
        updateConversation(conversationId, updatedConversation)
        conversationRepo.updateRollingContextSummary(
            conversationId = conversationId,
            summary = updatedConversation.rollingContextSummary,
        )
        return updatedConversation
    }

    private suspend fun generateCompressedSummary(
        settings: me.rerere.rikkahub.data.datastore.Settings,
        assistant: Assistant,
        workspaceCwd: String?,
        content: String,
        targetTokens: Int,
        additionalPrompt: String,
    ): String {
        val model = settings.findModelById(settings.compressModelId)
            ?: settings.getCurrentChatModel()
            ?: throw IllegalStateException("No model available for compression")
        val provider = model.findProvider(settings.providers)
            ?: throw IllegalStateException("Provider not found")
        val contextWindow = model.contextWindowTokens ?: inferContextWindowTokens(model.modelId)
        val compressionInputBudget = effectiveRollingContextThreshold(
            configuredThresholdTokens = contextWindow ?: DEFAULT_COMPRESSION_INPUT_BUDGET_TOKENS,
            modelContextWindowTokens = contextWindow,
            maxOutputTokens = targetTokens,
        )
        val retryController = ProviderRetryController(
            maxRetries = settings.generationRetryMaxRetries.coerceIn(
                MIN_GENERATION_RETRY_COUNT,
                MAX_GENERATION_RETRY_COUNT,
            ),
            initialDelayMillis = settings.generationRetryInitialIntervalSeconds.coerceIn(
                MIN_GENERATION_RETRY_INTERVAL_SECONDS,
                MAX_GENERATION_RETRY_INTERVAL_SECONDS,
            ) * 1_000L,
            maxDurationMillis = settings.generationRetryMaxDurationSeconds.coerceIn(
                MIN_GENERATION_RETRY_DURATION_SECONDS,
                MAX_GENERATION_RETRY_DURATION_SECONDS,
            ) * 1_000L,
        )
        val networkRecovery = NetworkRecoveryCoordinator(context)
        var attemptNetworkVersion = networkRecovery.snapshot()
        val promptVariables = resolvePromptVariables(
            settings = settings,
            model = model,
            assistant = assistant,
            workspaceCwd = workspaceCwd,
        )
        return try {
            suspend fun requestSummary(input: String, requestedTokens: Int): String {
                val prompt = settings.compressPrompt.applyPlaceholders(
                    *promptVariables,
                    "content" to input,
                    "target_tokens" to requestedTokens.toString(),
                    "additional_context" to additionalPrompt.takeIf(String::isNotBlank)
                        ?.let { "Additional instructions from user: $it" }
                        .orEmpty(),
                )
                return retryProviderRequest(
                    enabled = settings.enableGenerationRetry,
                    retryController = retryController,
                    shouldRetry = { error ->
                        error is InvalidRollingSummaryException ||
                            networkRecovery.shouldRetry(error, attemptNetworkVersion)
                    },
                    onRetry = { retryNumber, delayMillis ->
                        Log.w(TAG, "Rolling context retry #$retryNumber in ${delayMillis}ms")
                    },
                    delayBeforeRetry = { delayMillis ->
                        networkRecovery.awaitNetworkAndBackoff(
                            retryDelayMillis = delayMillis,
                            remainingDurationMillis = retryController.remainingDurationMillis(),
                        )
                    },
                ) {
                    attemptNetworkVersion = networkRecovery.snapshot()
                    val result = providerManager.getProviderByType(provider).generateText(
                        providerSetting = provider,
                        messages = listOf(UIMessage.user(prompt)),
                        params = backgroundTextGenerationParams(model),
                    )
                    val summary = result.message.toText().trim().takeIf(String::isNotBlank)
                        ?: throw InvalidRollingSummaryException("Compression returned an empty summary")
                    val acceptedTokens = maxOf(
                        requestedTokens.coerceAtLeast(1) * 2,
                        requestedTokens + MIN_INTERMEDIATE_SUMMARY_TOKENS,
                    )
                    summary.takeIf { estimateTextTokens(it) <= acceptedTokens }
                        ?: throw InvalidRollingSummaryException("Compression summary exceeded its target")
                }
            }

            var segments = splitTextForTokenBudget(content, compressionInputBudget)
            var depth = 0
            while (segments.size > 1 && depth < MAX_COMPRESSION_HIERARCHY_DEPTH) {
                val intermediateTarget = minOf(
                    targetTokens.coerceAtLeast(MIN_INTERMEDIATE_SUMMARY_TOKENS),
                    MAX_INTERMEDIATE_SUMMARY_TOKENS,
                    (compressionInputBudget / 4).coerceAtLeast(MIN_INTERMEDIATE_SUMMARY_TOKENS),
                )
                val combined = segments.mapIndexed { index, segment ->
                    "[Segment ${index + 1}/${segments.size}]\n" +
                        requestSummary(segment, intermediateTarget)
                }.joinToString("\n\n")
                segments = splitTextForTokenBudget(combined, compressionInputBudget)
                depth++
            }
            if (segments.size != 1) {
                throw InvalidRollingSummaryException("Compression hierarchy did not converge")
            }
            requestSummary(segments.single(), targetTokens)
        } finally {
            networkRecovery.close()
        }
    }

    private fun RollingContextPlan.toCompressionContent(): String = buildString {
        previousSummary?.let { summary ->
            appendLine("[Previous rolling summary]")
            appendLine(summary.content)
            appendLine()
        }
        append(messagesToSummarize.joinToString("\n\n") { it.summaryAsText() })
    }

    // ---- 对话状态更新 ----

    private fun updateConversation(conversationId: Uuid, conversation: Conversation) {
        if (conversation.id != conversationId) return
        val session = getOrCreateSession(conversationId)
        checkFilesDelete(conversation, session.state.value)
        session.state.value = conversation
    }

    fun updateConversationState(conversationId: Uuid, update: (Conversation) -> Conversation) {
        val current = getConversationFlow(conversationId).value
        updateConversation(conversationId, update(current))
    }

    /**
     * 移动会话到文件夹（folderId 为 null 表示移出到未归类）。
     *
     * 若该会话当前有活跃 session（正在查看或后台生成），先同步内存态再落库：
     * 否则仅改数据库 folder_id，而内存里那份 Conversation 仍是旧 folderId，
     * 后续任意 saveConversation(id, state.value) 会用整对象把 folder_id 覆盖回旧值，导致移动丢失。
     * 先改内存可确保这段窗口内的整对象保存也带上新 folderId。
     */
    suspend fun moveConversationToFolder(conversationId: Uuid, folderId: Uuid?) {
        if (sessions.containsKey(conversationId)) {
            updateConversationState(conversationId) { it.copy(folderId = folderId) }
        }
        conversationRepo.updateConversationFolderId(conversationId, folderId)
    }

    /**
     * 文件夹内是否存在正在生成回复的会话。
     * 仅活跃 session 可能在生成；内存态 folderId 为权威（移动会先同步内存态）。
     */
    fun hasGeneratingConversationInFolder(folderId: Uuid): Boolean {
        return sessions.values.any { it.isGenerating && it.state.value.folderId == folderId }
    }

    /**
     * 删除文件夹（folder_id 归属会被清空，会话本身保留）。
     *
     * 先把内存中归属该文件夹的活跃 session folderId 置空，再删库：
     * 否则 clearFolder 只改了数据库，而活跃 session 内存态仍指向该文件夹，
     * 后续整对象保存会写回一个已被删除的 folder_id，导致会话在列表中悬空。
     */
    suspend fun deleteFolder(folderId: Uuid) {
        sessions.values
            .filter { it.state.value.folderId == folderId }
            .forEach { updateConversationState(it.id) { c -> c.copy(folderId = null) } }
        folderRepository.deleteFolder(folderId)
    }

    private fun checkFilesDelete(newConversation: Conversation, oldConversation: Conversation) {
        val newFiles = newConversation.files
        val oldFiles = oldConversation.files
        val deletedFiles = oldFiles.filter { file ->
            newFiles.none { it == file }
        }
        if (deletedFiles.isNotEmpty()) {
            filesManager.deleteChatFiles(deletedFiles)
            Log.w(TAG, "checkFilesDelete: $deletedFiles")
        }
    }

    suspend fun saveConversation(conversationId: Uuid, conversation: Conversation) {
        if (conversation.id != conversationId) return
        val session = getOrCreateSession(conversationId)
        if (!session.isInitialized) {
            val exists = withContext(Dispatchers.IO) {
                conversationRepo.existsConversationById(conversationId)
            }
            check(!exists || session.isInitialized) {
                "Refusing to overwrite an existing conversation before it has loaded: $conversationId"
            }
            session.markInitialized()
        }
        // A send/stop/navigation cancellation must not interrupt the database transaction.
        // The lock also prevents an older whole-conversation write from finishing after a newer one.
        withContext(Dispatchers.IO + NonCancellable) {
            session.withPersistenceLock {
                persistConversationLocked(conversationId, conversation.copy(), session)
            }
        }
    }

    private suspend fun persistConversationLocked(
        conversationId: Uuid,
        conversation: Conversation,
        session: ConversationSession = getOrCreateSession(conversationId),
    ) {
        updateConversation(conversationId, conversation)
        val latestConversation = session.state.value
        val exists = conversationRepo.existsConversationById(conversationId)
        if (!exists && latestConversation.title.isBlank() && latestConversation.messageNodes.isEmpty()) {
            return
        }
        if (!exists) {
            conversationRepo.insertConversation(latestConversation)
        } else {
            conversationRepo.updateConversation(latestConversation)
        }
    }

    suspend fun updateConversationTitle(
        conversationId: Uuid,
        loadedConversation: Conversation,
        title: String,
    ) = updateConversationMetadata(
        conversationId = conversationId,
        loadedConversation = loadedConversation,
        update = { it.copy(title = title) },
        persist = { conversation ->
            conversationRepo.updateConversationTitle(conversationId, conversation.title)
        },
    )

    suspend fun toggleConversationPinned(
        conversationId: Uuid,
        loadedConversation: Conversation,
    ) = updateConversationMetadata(
        conversationId = conversationId,
        loadedConversation = loadedConversation,
        update = { it.copy(isPinned = !it.isPinned) },
        persist = { conversation ->
            conversationRepo.updatePinnedStatus(conversationId, conversation.isPinned)
        },
    )

    private suspend fun updateConversationMetadata(
        conversationId: Uuid,
        loadedConversation: Conversation,
        update: (Conversation) -> Conversation,
        persist: suspend (Conversation) -> Unit,
    ) {
        require(loadedConversation.id == conversationId)
        val session = getOrCreateSession(conversationId)
        session.withRefSuspend {
            session.initializeOnce { loadedConversation }
            withContext(Dispatchers.IO + NonCancellable) {
                session.withPersistenceLock {
                    val updatedConversation = update(session.state.value)
                    require(updatedConversation.id == conversationId)
                    session.state.value = updatedConversation
                    persist(updatedConversation)
                }
            }
        }
    }

    /**
     * Applies a narrow update to an existing conversation that may not have an active session yet.
     * The supplied snapshot must be a complete repository result, never a lightweight list item.
     */
    suspend fun saveConversationUpdate(
        conversationId: Uuid,
        loadedConversation: Conversation,
        update: (Conversation) -> Conversation,
    ) {
        require(loadedConversation.id == conversationId)
        val session = getOrCreateSession(conversationId)
        session.withRefSuspend {
            session.initializeOnce { loadedConversation }
            withContext(Dispatchers.IO + NonCancellable) {
                session.withPersistenceLock {
                    persistConversationLocked(
                        conversationId = conversationId,
                        conversation = update(session.state.value),
                        session = session,
                    )
                }
            }
        }
    }

    // ---- 翻译消息 ----

    fun translateMessage(
        conversationId: Uuid,
        message: UIMessage,
        targetLanguage: Locale
    ) {
        appScope.launch(Dispatchers.IO) {
            try {
                val settings = settingsStore.settingsFlow.first()

                val messageText = message.parts.filterIsInstance<UIMessagePart.Text>()
                    .joinToString("\n\n") { it.text }
                    .trim()

                if (messageText.isBlank()) return@launch

                // Set loading state for translation
                val loadingText = context.getString(R.string.translating)
                updateTranslationField(conversationId, message.id, loadingText)

                generationHandler.translateText(
                    settings = settings,
                    sourceText = messageText,
                    targetLanguage = targetLanguage
                ) { translatedText ->
                    // Update translation field in real-time
                    updateTranslationField(conversationId, message.id, translatedText)
                }.collect { /* Final translation already handled in onStreamUpdate */ }

                // Save the conversation after translation is complete
                saveConversation(conversationId, getConversationFlow(conversationId).value)
            } catch (e: Exception) {
                // Clear translation field on error
                clearTranslationField(conversationId, message.id)
                addError(e, conversationId, title = context.getString(R.string.error_title_translate_message))
            }
        }
    }

    private fun updateTranslationField(
        conversationId: Uuid,
        messageId: Uuid,
        translationText: String
    ) {
        val currentConversation = getConversationFlow(conversationId).value
        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (node.messages.any { it.id == messageId }) {
                val updatedMessages = node.messages.map { msg ->
                    if (msg.id == messageId) {
                        msg.copy(translation = translationText)
                    } else {
                        msg
                    }
                }
                node.copy(messages = updatedMessages)
            } else {
                node
            }
        }

        updateConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    // ---- 消息操作 ----

    suspend fun editMessage(
        conversationId: Uuid,
        messageId: Uuid,
        parts: List<UIMessagePart>
    ) {
        if (parts.isEmptyInputMessage()) return

        val currentConversation = getConversationFlow(conversationId).value
        val settings = settingsStore.settingsFlow.first()
        val assistant = settings.getAssistantById(currentConversation.assistantId)
            ?: settings.getCurrentAssistant()
        val processedParts = preprocessUserInputParts(parts, assistant)
        var edited = false

        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (!node.messages.any { it.id == messageId }) {
                return@map node
            }
            edited = true

            node.copy(
                messages = node.messages + UIMessage(
                    role = node.role,
                    parts = processedParts,
                ),
                selectIndex = node.messages.size
            )
        }

        if (!edited) return

        saveConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    suspend fun forkConversationAtMessage(
        conversationId: Uuid,
        messageId: Uuid
    ): Conversation {
        val currentConversation = getConversationFlow(conversationId).value
        val copiedFileUrls = mutableMapOf<String, String>()
        val createdFiles = mutableListOf<Uri>()
        var forkConversation: Conversation? = null
        try {
            forkConversation = createForkConversationSnapshot(
                currentConversation = currentConversation,
                messageId = messageId,
                copyPart = { part ->
                    part.copyWithForkedFileUrl(
                        copiedFileUrls = copiedFileUrls,
                        createdFiles = createdFiles,
                    )
                },
            )
            saveConversation(forkConversation.id, forkConversation)
            return forkConversation
        } catch (error: Exception) {
            val persisted = forkConversation?.let { conversation ->
                runCatching {
                    withContext(Dispatchers.IO + NonCancellable) {
                        conversationRepo.existsConversationById(conversation.id)
                    }
                }.getOrDefault(false)
            } == true
            if (persisted) {
                // The conversation transaction committed; a secondary index failure must not
                // turn a valid fork into a broken one by deleting its copied attachments.
                return requireNotNull(forkConversation)
            }
            withContext(NonCancellable) {
                filesManager.deleteChatFiles(createdFiles)
                forkConversation?.let { removeSession(it.id) }
            }
            throw error
        }
    }

    suspend fun selectMessageNode(
        conversationId: Uuid,
        nodeId: Uuid,
        selectIndex: Int
    ) {
        val currentConversation = getConversationFlow(conversationId).value
        val targetNode = currentConversation.messageNodes.firstOrNull { it.id == nodeId }
            ?: throw NotFoundException("Message node not found")

        if (selectIndex !in targetNode.messages.indices) {
            throw BadRequestException("Invalid selectIndex")
        }

        if (targetNode.selectIndex == selectIndex) {
            return
        }

        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (node.id == nodeId) {
                node.copy(selectIndex = selectIndex)
            } else {
                node
            }
        }

        saveConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    suspend fun deleteMessage(
        conversationId: Uuid,
        messageId: Uuid,
        failIfMissing: Boolean = true,
    ) {
        val currentConversation = getConversationFlow(conversationId).value
        val updatedConversation = buildConversationAfterMessageDelete(currentConversation, messageId)

        if (updatedConversation == null) {
            if (failIfMissing) {
                throw NotFoundException("Message not found")
            }
            return
        }

        saveConversation(conversationId, updatedConversation)
    }

    suspend fun deleteMessage(
        conversationId: Uuid,
        message: UIMessage,
    ) {
        deleteMessage(conversationId, message.id, failIfMissing = false)
    }

    private fun buildConversationAfterMessageDelete(
        conversation: Conversation,
        messageId: Uuid,
    ): Conversation? {
        val targetNodeIndex = conversation.messageNodes.indexOfFirst { node ->
            node.messages.any { it.id == messageId }
        }
        if (targetNodeIndex == -1) {
            return null
        }

        val updatedNodes = conversation.messageNodes.mapIndexedNotNull { index, node ->
            if (index != targetNodeIndex) {
                return@mapIndexedNotNull node
            }

            val nextMessages = node.messages.filterNot { it.id == messageId }
            if (nextMessages.isEmpty()) {
                return@mapIndexedNotNull null
            }

            val nextSelectIndex = node.selectIndex.coerceAtMost(nextMessages.lastIndex)
            node.copy(
                messages = nextMessages,
                selectIndex = nextSelectIndex,
            )
        }

        return conversation.copy(messageNodes = updatedNodes)
    }

    private suspend fun UIMessagePart.copyWithForkedFileUrl(
        copiedFileUrls: MutableMap<String, String>,
        createdFiles: MutableList<Uri>,
    ): UIMessagePart {
        suspend fun copyLocalFileIfNeeded(url: String): String {
            copiedFileUrls[url]?.let { return it }
            val copiedUrl = copyForkAttachmentUrl(url) { sourceUrl ->
                filesManager.createChatFilesByContents(listOf(sourceUrl.toUri()))
                    .singleOrNull()
                    ?.also { createdFiles += it }
                    ?.toString()
            }
            if (url.startsWith("file:") && copiedUrl == url) {
                Logging.log(
                    TAG,
                    "forkConversationAtMessage: attachment unavailable; keeping its original reference: $url",
                )
            }
            copiedFileUrls[url] = copiedUrl
            return copiedUrl
        }

        return copyForFork(::copyLocalFileIfNeeded)
    }

    fun clearTranslationField(conversationId: Uuid, messageId: Uuid) {
        val currentConversation = getConversationFlow(conversationId).value
        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (node.messages.any { it.id == messageId }) {
                val updatedMessages = node.messages.map { msg ->
                    if (msg.id == messageId) {
                        msg.copy(translation = null)
                    } else {
                        msg
                    }
                }
                node.copy(messages = updatedMessages)
            } else {
                node
            }
        }

        updateConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    // 停止当前会话生成任务（不清理会话缓存）
    suspend fun stopGeneration(conversationId: Uuid) {
        val job = sessions[conversationId]?.getJob() ?: return
        job.cancel()
        runCatching { job.join() }
        finishInterruptedPendingTools(conversationId)
        saveConversation(conversationId, getConversationFlow(conversationId).value)
    }
}
