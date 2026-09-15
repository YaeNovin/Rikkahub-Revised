package me.rerere.rikkahub.ui.pages.chat

import android.app.Application
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.analytics.FirebaseAnalytics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ImageGenerationConstraints
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.isEmptyInputMessage
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.ChatSuggestionItem
import me.rerere.rikkahub.data.model.*
import me.rerere.rikkahub.data.model.QuickMessage
import me.rerere.rikkahub.data.model.resolveChatImageModel
import me.rerere.rikkahub.data.ai.ChatImageGenerationService
import me.rerere.rikkahub.data.model.NodeFavoriteTarget
import me.rerere.rikkahub.data.memory.MemoryExtractionStatus
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FavoriteRepository
import me.rerere.rikkahub.service.ChatError
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.ui.hooks.writeStringPreference
import me.rerere.rikkahub.ui.hooks.ChatInputState
import me.rerere.rikkahub.utils.UiState
import me.rerere.rikkahub.utils.UpdateChecker
import java.util.Locale
import kotlin.uuid.Uuid

private const val TAG = "ChatVM"

sealed interface ConversationLoadState {
    data object Loading : ConversationLoadState
    data object Ready : ConversationLoadState
    data class Failed(val error: Throwable) : ConversationLoadState
}

class ChatVM(
    id: String,
    private val context: Application,
    private val settingsStore: SettingsStore,
    private val conversationRepo: ConversationRepository,
    private val chatService: ChatService,
    val updateChecker: UpdateChecker,
    private val analytics: FirebaseAnalytics?,
    private val filesManager: FilesManager,
    private val favoriteRepository: FavoriteRepository,
    private val memoryRepository: MemoryRepository,
    private val chatImageGenerationService: ChatImageGenerationService,
    private val providerManager: ProviderManager,
    sharedInputState: ChatInputState,
) : ViewModel() {
    sealed interface ChatImageState {
        data object Idle : ChatImageState
        data object Running : ChatImageState
        data class Failed(val message: String) : ChatImageState
    }
    private val _chatImageState = MutableStateFlow<ChatImageState>(ChatImageState.Idle)
    val chatImageState: StateFlow<ChatImageState> = _chatImageState.asStateFlow()
    private var chatImageJob: Job? = null
    private var lastChatImagePrompt = ""
    private var chatImageRequestVersion = 0L
    private val _conversationId: Uuid = Uuid.parse(id)
    val conversation: StateFlow<Conversation> = chatService.getConversationFlow(_conversationId)
    val branchSourceAvailable: StateFlow<Boolean?> = conversation
        .map { it.sourceConversationId }
        .distinctUntilChanged()
        .flatMapLatest { sourceConversationId ->
            if (sourceConversationId == null) {
                flowOf<Boolean?>(null)
            } else {
                conversationRepo.observeConversationExists(sourceConversationId)
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    var chatListInitialized by mutableStateOf(false) // 聊天列表是否已经滚动到底部

    // 聊天输入状态 - 保存在 ViewModel 中避免 TransactionTooLargeException
    // Keep the draft outside the conversation ViewModel so navigation does not
    // discard text or attachments when a different chat/assistant is selected.
    val inputState = sharedInputState

    // 异步任务 (从ChatService获取，响应式)
    val conversationJob: StateFlow<Job?> =
        chatService
            .getGenerationJobStateFlow(_conversationId)
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val processingStatus: StateFlow<String?> =
        chatService
            .getProcessingStatusFlow(_conversationId)

    val promptInjectionDiagnostics =
        chatService.getPromptInjectionDiagnosticsFlow(_conversationId)

    val memoryExtractionStatus: StateFlow<MemoryExtractionStatus> =
        chatService.getMemoryExtractionStatusFlow(_conversationId)

    val suggestionGenerationState = chatService.getSuggestionGenerationStateFlow(_conversationId)

    fun retryMemoryExtraction() {
        chatService.retryMemoryExtraction(_conversationId)
    }

    private val _conversationLoadState =
        MutableStateFlow<ConversationLoadState>(ConversationLoadState.Loading)
    val conversationLoadState: StateFlow<ConversationLoadState> =
        _conversationLoadState.asStateFlow()
    private var conversationInitializationJob: Job? = null

    val conversationJobs = chatService
        .getConversationJobs()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    init {
        // Editing metadata belongs to the previous conversation, but the draft
        // itself is intentionally retained across navigation.
        inputState.cancelEditing()

        // 添加对话引用
        chatService.addConversationReference(_conversationId)

        initializeConversation()

        // 记住对话ID, 方便下次启动恢复
        context.writeStringPreference("lastConversationId", _conversationId.toString())
    }

    override fun onCleared() {
        conversationInitializationJob?.cancel()
        // 移除对话引用
        chatService.removeConversationReference(_conversationId)
        super.onCleared()
    }

    fun retryConversationLoad() = initializeConversation()

    private fun initializeConversation() {
        if (conversationInitializationJob?.isActive == true) return
        _conversationLoadState.value = ConversationLoadState.Loading
        conversationInitializationJob = viewModelScope.launch {
            try {
                chatService.initializeConversation(_conversationId)
                _conversationLoadState.value = ConversationLoadState.Ready
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                _conversationLoadState.value = ConversationLoadState.Failed(error)
                chatService.addError(
                    error = error,
                    conversationId = _conversationId,
                    title = context.getString(R.string.error_title_load_conversation),
                )
            }
        }
    }

    // 用户设置
    val settings: StateFlow<Settings> =
        settingsStore.settingsFlow.stateIn(viewModelScope, SharingStarted.Eagerly, Settings.dummy())

    /** True only when the configured global image model and its provider are usable. */
    val chatImageModel = settings.map { it.resolveChatImageModel() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Keep the compact chat panel in sync with the full image page's capability rules. */
    val chatImageConstraints: StateFlow<ImageGenerationConstraints?> = settings
        .map { currentSettings ->
            currentSettings.resolveChatImageModel()?.let { model ->
                model.findProvider(currentSettings.providers)?.let { provider ->
                    providerManager.imageGenerationConstraints(provider, model)
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val recentConversationMemories = conversation.map { it.id }.distinctUntilChanged()
        .flatMapLatest { chatService.observeConversationMemories(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    suspend fun deleteConversationMemory(id: String) = chatService.deleteConversationMemory(_conversationId, id)

    suspend fun loadMemoryPreview(id: Int): me.rerere.rikkahub.data.repository.MemorySearchRecord? {
        val assistant = settings.value.getAssistantById(conversation.value.assistantId) ?: return null
        val owner = if (assistant.useGlobalMemory) MemoryRepository.GLOBAL_MEMORY_ID else assistant.id.toString()
        return memoryRepository.getRecordsForConversation(owner, _conversationId.toString()).firstOrNull { it.memory.id == id }?.let { record ->
            record.copy(sources = memoryRepository.getSources(record.memory.uid),
                createdByRun = record.memory.createdByRunId?.let { memoryRepository.getRun(it) })
        }
    }

    suspend fun resolveMemorySource(conversationId: String, messageId: String): Pair<Uuid, Uuid?>? {
        val id = runCatching { Uuid.parse(conversationId) }.getOrNull() ?: return null
        val source = conversationRepo.getConversationById(id) ?: return null
        val node = source.messageNodes.firstOrNull { it.currentMessage.id.toString() == messageId }
        return source.id to node?.id
    }

    suspend fun rebuildMemoryIndex(id: Int) {
        val record = loadMemoryPreview(id) ?: error("记忆已不存在或不属于当前对话")
        me.rerere.rikkahub.data.memory.MemoryEmbeddingService(memoryRepository, providerManager).rebuildIndex(record.memory, settings.value)
    }

    val recentMemories = conversation
        .map { it.assistantId }
        .distinctUntilChanged()
        .flatMapLatest { assistantId ->
            settings.map { currentSettings ->
                currentSettings.getAssistantById(assistantId)
            }.distinctUntilChanged().flatMapLatest { assistant ->
                when {
                    assistant == null -> flowOf(emptyList())
                    assistant.useGlobalMemory -> memoryRepository.getActiveGlobalMemoriesFlow()
                    else -> memoryRepository.getActiveMemoriesOfAssistantFlow(assistantId.toString())
                }
            }
        }
        .map { memories -> memories.filter { it.isVisibleInConversation(_conversationId.toString()) }.sortedByDescending { it.createdAt }.take(12) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val updateState = settingsStore.settingsFlow
        .map { settings ->
            !settings.init &&
                settings.displaySetting.updateCheckDisabledUntilEpochMillis <= System.currentTimeMillis()
        }
        .distinctUntilChanged()
        .flatMapLatest { enabled ->
            if (enabled) updateChecker.checkUpdate() else flowOf(UiState.Success(null))
        }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
            UiState.Loading,
        )

    // 网络搜索(每个助手独立)
    val enableWebSearch = settings.map {
        it.getCurrentAssistant().enableWebSearch
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // 当前模型
    val currentChatModel = settings.map { settings ->
        settings.getCurrentChatModel()
    }.stateIn(viewModelScope, SharingStarted.Lazily, null)

    // 错误状态
    val errors: StateFlow<List<ChatError>> = chatService.errors

    fun dismissError(id: Uuid) = chatService.dismissError(id)

    fun clearAllErrors() = chatService.clearAllErrors()

    // 生成完成
    val generationDoneFlow: SharedFlow<Uuid> = chatService.generationDoneFlow

    // MCP管理器
    val mcpManager = chatService.mcpManager

    // 更新设置
    fun updateSettings(newSettings: Settings): Job {
        return viewModelScope.launch {
            val oldSettings = settings.value
            // 检查用户头像是否有变化，如果有则删除旧头像
            checkUserAvatarDelete(oldSettings, newSettings)
            settingsStore.update(newSettings)
        }
    }

    suspend fun saveSuggestionAsQuickMessage(suggestion: ChatSuggestionItem, assistantId: Uuid) {
        val content = suggestion.payload.ifBlank { suggestion.text }.trim()
        require(content.isNotBlank())
        settingsStore.update { current ->
            require(current.assistants.any { it.id == assistantId }) { "Assistant no longer exists" }
            val existing = current.quickMessages.firstOrNull { it.content == content }
            val quickMessage = existing ?: QuickMessage(
                title = suggestion.text.trim().take(48),
                content = content,
                category = context.getString(R.string.chat_page_suggestion_quick_category),
                tags = listOf(context.getString(R.string.chat_page_suggestion_quick_tag)),
            )
            current.copy(
                quickMessages = if (existing == null) {
                    current.quickMessages + quickMessage
                } else {
                    current.quickMessages
                },
                assistants = current.assistants.map { assistant ->
                    if (assistant.id == assistantId) {
                        assistant.copy(quickMessageIds = assistant.quickMessageIds + quickMessage.id)
                    } else {
                        assistant
                    }
                },
            )
        }
    }

    // 检查用户头像删除
    private fun checkUserAvatarDelete(oldSettings: Settings, newSettings: Settings) {
        val oldAvatar = oldSettings.displaySetting.userAvatar
        val newAvatar = newSettings.displaySetting.userAvatar

        if (oldAvatar is Avatar.Image && oldAvatar != newAvatar) {
            filesManager.deleteChatFiles(listOf(oldAvatar.url.toUri()))
        }
    }

    // 设置聊天模型
    fun setChatModel(assistant: Assistant, model: Model) {
        viewModelScope.launch {
            settingsStore.update { settings ->
                settings.copy(
                    assistants = settings.assistants.map {
                        if (it.id == assistant.id) {
                            it.copy(
                                chatModelId = model.id
                            )
                        } else {
                            it
                        }
                    })
            }
        }
    }

    /**
     * 处理消息发送
     *
     * @param content 消息内容
     * @param answer 是否触发消息生成，如果为false，则仅添加消息到消息列表中
     */
    fun handleMessageSend(content: List<UIMessagePart>,answer: Boolean = true) {
        if (content.isEmptyInputMessage()) return
        analytics?.logEvent("ai_send_message", null)

        chatService.sendMessage(_conversationId, content, answer)
    }

    fun generateChatImage(prompt: String, recordPrompt: Boolean = true): Job {
        lastChatImagePrompt = prompt
        chatImageJob?.cancel()
        val requestVersion = ++chatImageRequestVersion
        _chatImageState.value = ChatImageState.Running
        chatImageJob = viewModelScope.launch {
        runCatching {
            if (recordPrompt) {
                chatService.updateAndSaveConversationState(_conversationId) { current ->
                    current.copy(
                        messageNodes = current.messageNodes + MessageNode.of(UIMessage.user(prompt))
                    )
                }
            }
            val result = chatImageGenerationService.generate(settings.value, prompt, inputState.imageGenerationSettings)
            chatService.updateAndSaveConversationState(_conversationId) { current ->
                current.copy(messageNodes = current.messageNodes + MessageNode.of(
                    UIMessage(
                        role = me.rerere.ai.core.MessageRole.ASSISTANT,
                        parts = result.images,
                        modelId = result.modelId,
                    )
                ))
            }
            if (requestVersion == chatImageRequestVersion) {
                _chatImageState.value = ChatImageState.Idle
            }
        }.onFailure {
            if (it is CancellationException || requestVersion != chatImageRequestVersion) return@onFailure
            _chatImageState.value = ChatImageState.Failed(it.message ?: "Image generation failed")
            chatService.addError(it, conversationId = _conversationId, title = context.getString(R.string.error_title_operation))
        }
        }
        return chatImageJob!!
    }

    fun cancelChatImageGeneration() {
        chatImageRequestVersion++
        chatImageJob?.cancel()
        _chatImageState.value = ChatImageState.Idle
    }
    fun retryChatImageGeneration(): Job? = lastChatImagePrompt
        .takeIf(String::isNotBlank)
        ?.let { generateChatImage(prompt = it, recordPrompt = false) }

    fun handleMessageEdit(parts: List<UIMessagePart>, messageId: Uuid) {
        if (parts.isEmptyInputMessage()) return
        analytics?.logEvent("ai_edit_message", null)

        viewModelScope.launch {
            chatService.editMessage(_conversationId, messageId, parts)
        }
    }

    fun handleRefreshRollingContext(additionalPrompt: String, targetTokens: Int): Job {
        return viewModelScope.launch {
            chatService.refreshRollingContext(
                conversationId = _conversationId,
                conversation = conversation.value,
                additionalPrompt = additionalPrompt,
                targetTokens = targetTokens,
            ).onFailure {
                chatService.addError(it, title = context.getString(R.string.error_title_compress_conversation))
            }
        }
    }

    suspend fun forkMessage(message: UIMessage): Conversation {
        analytics?.logEvent("ai_fork_conversation", null)
        return try {
            chatService.forkConversationAtMessage(_conversationId, message.id)
        } catch (error: Exception) {
            chatService.addError(
                error = error,
                conversationId = _conversationId,
                title = context.getString(R.string.create_fork),
            )
            throw error
        }
    }

    fun deleteMessage(message: UIMessage) {
        viewModelScope.launch {
            chatService.deleteMessage(_conversationId, message)
        }
    }

    fun showDeleteBlockedWhileGeneratingError() {
        chatService.addError(
            error = IllegalStateException("请先停止生成再删除消息"),
            conversationId = _conversationId,
            title = context.getString(R.string.error_title_operation)
        )
    }

    fun regenerateAtMessage(
        message: UIMessage,
        regenerateAssistantMsg: Boolean = true
    ) {
        analytics?.logEvent("ai_regenerate_at_message", null)
        chatService.regenerateAtMessage(_conversationId, message, regenerateAssistantMsg)
    }

    fun generateAlternativeResponses(message: UIMessage, count: Int) {
        analytics?.logEvent("ai_generate_alternatives", null)
        chatService.regenerateAtMessage(
            conversationId = _conversationId,
            message = message,
            responseCount = count.coerceIn(2, 5),
            generationTrigger = me.rerere.rikkahub.data.model.LorebookGenerationTrigger.SWIPE,
        )
    }

    fun continueAtMessage(message: UIMessage) {
        analytics?.logEvent("ai_continue_at_message", null)
        chatService.continueAtMessage(_conversationId, message)
    }

    fun impersonate() {
        analytics?.logEvent("ai_impersonate", null)
        chatService.impersonate(_conversationId)
    }

    fun handleToolApproval(
        toolCallId: String,
        approved: Boolean,
        reason: String = ""
    ) {
        analytics?.logEvent("ai_tool_approval", null)
        chatService.handleToolApproval(_conversationId, toolCallId, approved, reason)
    }

    fun handleToolAnswer(
        toolCallId: String,
        answer: String,
    ) {
        analytics?.logEvent("ai_tool_answer", null)
        chatService.handleToolApproval(_conversationId, toolCallId, approved = true, answer = answer)
    }

    fun saveAskUserDraft(conversationId: Uuid, toolCallId: String, input: String, answers: kotlinx.serialization.json.JsonObject, displayed: Set<String>) {
        chatService.saveAskUserDraft(conversationId, toolCallId, input, answers, displayed)
    }

    fun handleToolCancellation(
        toolCallId: String,
        reason: String = "Cancelled by user",
    ) {
        analytics?.logEvent("ai_tool_cancel", null)
        chatService.handleToolApproval(
            _conversationId,
            toolCallId,
            approved = false,
            reason = reason,
            cancelled = true,
        )
    }

    fun stopGeneration() {
        viewModelScope.launch {
            chatService.stopGeneration(_conversationId)
        }
    }

    fun saveConversationAsync() {
        viewModelScope.launch {
            chatService.saveConversation(_conversationId, conversation.value)
        }
    }

    fun updateTitle(title: String) {
        viewModelScope.launch {
            chatService.updateConversationTitle(
                conversationId = _conversationId,
                loadedConversation = conversation.value,
                title = title,
            )
        }
    }

    fun deleteConversation(conversation: Conversation): Job =
        viewModelScope.launch {
            conversationRepo.deleteConversation(conversation)
        }

    fun updatePinnedStatus(conversation: Conversation) {
        viewModelScope.launch {
            val conversationFull = conversationRepo.getConversationById(conversation.id) ?: return@launch
            chatService.toggleConversationPinned(conversation.id, conversationFull)
        }
    }

    fun moveConversationToAssistant(conversation: Conversation, targetAssistantId: Uuid) {
        viewModelScope.launch {
            val conversationFull = conversationRepo.getConversationById(conversation.id) ?: return@launch
            // 文件夹是助手内分组，切换助手后原文件夹在新助手下不可见，需清空归属避免会话丢失
            chatService.saveConversationUpdate(conversation.id, conversationFull) {
                it.copy(
                    assistantId = targetAssistantId,
                    folderId = null,
                )
            }
            if (conversation.id == _conversationId) {
                settingsStore.updateAssistant(targetAssistantId)
            }
        }
    }

    fun translateMessage(message: UIMessage, targetLanguage: Locale) {
        chatService.translateMessage(_conversationId, message, targetLanguage)
    }

    fun generateTitle(conversation: Conversation, force: Boolean = false) {
        viewModelScope.launch {
            val conversationFull = conversationRepo.getConversationById(conversation.id) ?: return@launch
            chatService.generateTitle(conversation.id, conversationFull, force)
        }
    }

    fun generateSuggestion(conversation: Conversation, replaceId: String? = null) {
        viewModelScope.launch {
            chatService.generateSuggestion(conversation.id, conversation, manual = true, replaceId = replaceId)
        }
    }

    fun generateSuggestionFor(message: UIMessage, selection: String = "") = viewModelScope.launch {
        chatService.setSuggestionTarget(_conversationId, SuggestionTarget(message.id, selection))
        val snapshot = conversation.value
        chatService.generateSuggestion(_conversationId, snapshot, manual = true)
    }

    fun resetSuggestionTarget() = viewModelScope.launch { chatService.setSuggestionTarget(_conversationId, null) }
    fun updateSuggestionSession(update: (SuggestionSession) -> SuggestionSession) = viewModelScope.launch {
        chatService.updateSuggestionSession(_conversationId, update)
    }
    fun toggleSuggestionPin(id: String) = viewModelScope.launch { chatService.toggleSuggestionPin(_conversationId, id) }
    fun undoSuggestionRefresh() = viewModelScope.launch { chatService.undoSuggestionRefresh(_conversationId) }
    fun feedbackSuggestion(item: ChatSuggestionItem, reason: SuggestionFeedbackReason) {
        val assistantId = conversation.value.assistantId
        chatService.stopSuggestionGeneration(_conversationId)
        viewModelScope.launch {
            settingsStore.update { current -> current.copy(assistants = current.assistants.map { assistant ->
                if (assistant.id != assistantId) assistant else assistant.copy(suggestionFeedback =
                    (assistant.suggestionFeedback + SuggestionFeedback(item.payload.ifBlank { item.text }.take(500), reason, item.category)).takeLast(50))
            }) }
            chatService.updateAndSaveConversationState(_conversationId) { current ->
                val items = current.visibleChatSuggestions().filterNot { it.id == item.id }
                current.copy(chatSuggestions = items.map { it.text }, chatSuggestionItems = items,
                    suggestionSession = current.suggestionSession.copy(pinnedIds = current.suggestionSession.pinnedIds - item.id))
            }
        }
    }
    fun clearSuggestionFeedback() {
        val assistantId = conversation.value.assistantId
        viewModelScope.launch { settingsStore.update { current -> current.copy(assistants = current.assistants.map {
            if (it.id == assistantId) it.copy(suggestionFeedback = emptyList()) else it
        }) } }
    }

    fun dismissSuggestions(conversation: Conversation) {
        viewModelScope.launch {
            val loadedConversation = conversationRepo.getConversationById(conversation.id)
                ?: conversation
            chatService.dismissSuggestions(conversation.id, loadedConversation)
        }
    }

    fun clearTranslationField(messageId: Uuid) {
        chatService.clearTranslationField(_conversationId, messageId)
    }

    fun updateConversation(newConversation: Conversation) {
        chatService.updateConversationState(_conversationId) {
            newConversation
        }
    }

    fun setMemoryMode(mode: me.rerere.rikkahub.data.model.ConversationMemoryMode) {
        val id = _conversationId
        viewModelScope.launch { chatService.setConversationMemoryMode(id, mode) }
    }

    fun toggleMessageFavorite(node: MessageNode) {
        viewModelScope.launch {
            val currentlyFavorited = favoriteRepository.isNodeFavorited(_conversationId, node.id)
            if (currentlyFavorited) {
                favoriteRepository.removeNodeFavorite(_conversationId, node.id)
            } else {
                favoriteRepository.addNodeFavorite(
                    NodeFavoriteTarget(
                        conversationId = _conversationId,
                        conversationTitle = conversation.value.title,
                        nodeId = node.id,
                        node = node
                    )
                )
            }

            chatService.updateConversationState(_conversationId) { currentConversation ->
                currentConversation.copy(
                    messageNodes = currentConversation.messageNodes.map { existingNode ->
                        if (existingNode.id == node.id) {
                            existingNode.copy(isFavorite = !currentlyFavorited)
                        } else {
                            existingNode
                        }
                    }
                )
            }
        }
    }

}
