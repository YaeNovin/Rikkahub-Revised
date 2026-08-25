package me.rerere.rikkahub.data.ai

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderRetryController
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.crossesRequestReplayBoundary
import me.rerere.ai.provider.retryProviderRequest
import me.rerere.ai.registry.ModelRegistry
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.StreamChunkHandler
import me.rerere.ai.ui.handleTextGenerationResult
import me.rerere.rikkahub.data.ai.transformers.InputMessageTransformer
import me.rerere.rikkahub.data.ai.transformers.MessageTransformer
import me.rerere.rikkahub.data.ai.transformers.OutputMessageTransformer
import me.rerere.rikkahub.data.ai.transforms.currentRequestKnowledgeCitations
import me.rerere.rikkahub.data.files.FileFolders
import java.io.File
import me.rerere.rikkahub.data.ai.transformers.onGenerationFinish
import me.rerere.rikkahub.data.ai.transformers.transforms
import me.rerere.rikkahub.data.ai.transformers.visualTransforms
import me.rerere.rikkahub.data.ai.tools.buildMemoryTools
import me.rerere.rikkahub.data.ai.tools.createKnowledgeBaseTools
import me.rerere.rikkahub.data.ai.tools.KnowledgeBaseCapabilities
import me.rerere.rikkahub.data.ai.tools.createSessionCapabilitiesTool
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.memory.MemoryEmbeddingService
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.KnowledgeBaseRepository
import me.rerere.rikkahub.utils.applyPlaceholders
import java.util.Locale
import kotlin.time.Clock
import kotlin.uuid.Uuid

private const val TAG = "GenerationHandler"
private const val MAX_TOOL_OUTPUT_CHARS = 32 * 1024
private const val TOOL_OUTPUT_PREVIEW_CHARS = 4 * 1024
private const val STREAM_UI_UPDATE_INTERVAL_NANOS = 40_000_000L
private const val ROLLING_CONTEXT_SYSTEM_PROMPT =
    "The following is a rolling summary of earlier conversation turns. Use it as context, " +
        "but follow the latest messages when they differ:\n<rolling_context_summary>"

@Serializable
sealed interface GenerationChunk {
    data class Messages(
        val messages: List<UIMessage>
    ) : GenerationChunk
}

class GenerationHandler(
    private val context: Context,
    private val providerManager: ProviderManager,
    private val json: Json,
    private val memoryRepo: MemoryRepository,
    private val memoryEmbeddingService: MemoryEmbeddingService,
    private val knowledgeBaseRepository: KnowledgeBaseRepository,
) {
    fun generateText(
        settings: Settings,
        model: Model,
        messages: List<UIMessage>,
        inputTransformers: List<InputMessageTransformer> = emptyList(),
        outputTransformers: List<OutputMessageTransformer> = emptyList(),
        assistant: Assistant,
        conversationId: Uuid? = null,
        memories: List<AssistantMemory>? = null,
        tools: List<Tool> = emptyList(),
        maxSteps: Int = 256,
        processingStatus: MutableStateFlow<String?> = MutableStateFlow(null),
        conversationSystemPrompt: String? = null,
        conversationModeInjectionIds: Set<Uuid> = emptySet(),
        conversationLorebookIds: Set<Uuid> = emptySet(),
        workspaceCwd: String? = null,
        rollingContextSummary: String? = null,
        requestMessageStartIndex: Int = 0,
        resumeInterruptedResponse: Boolean = false,
    ): Flow<GenerationChunk> = flow {
        val provider = model.findProvider(settings.providers) ?: error("Provider not found")
        val providerImpl = providerManager.getProviderByType(provider)

        var messages: List<UIMessage> = messages
        val requestStartIndex = requestMessageStartIndex.coerceIn(0, messages.size)
        fun requestMessages(): List<UIMessage> = if (requestStartIndex > 0) {
            messages.drop(requestStartIndex)
        } else {
            messages
        }
        val boundKnowledgeBaseIds = assistant.knowledgeBaseIds.map(Uuid::toString).toSet()
        val enabledKnowledgeBaseIds = knowledgeBaseRepository.getEnabledBaseIds(boundKnowledgeBaseIds)
        val ragEnabledKnowledgeBaseIds = knowledgeBaseRepository.getRagEnabledBaseIds(boundKnowledgeBaseIds)
        val knowledgeBaseCapabilities = KnowledgeBaseCapabilities(
            boundCount = boundKnowledgeBaseIds.size,
            enabledCount = enabledKnowledgeBaseIds.size,
            ragEnabledCount = ragEnabledKnowledgeBaseIds.size,
        )

        var needsInterruptedResponseContinuation = resumeInterruptedResponse
        for (stepIndex in 0 until maxSteps) {
            Log.i(TAG, "streamText: start step #$stepIndex (${model.id})")

            val registeredTools = buildList {
                Log.i(TAG, "generateInternal: build tools($assistant)")
                if (assistant.enableMemory) {
                    val memoryAssistantId = if (assistant.useGlobalMemory) {
                        MemoryRepository.GLOBAL_MEMORY_ID
                    } else {
                        assistant.id.toString()
                    }
                    buildMemoryTools(
                        json = json,
                        allowEpisodicMemory = assistant.enableEpisodicMemory,
                        onCreation = { content, type ->
                            memoryEmbeddingService.addMemory(
                                assistantId = memoryAssistantId,
                                content = content,
                                settings = settings,
                                type = type,
                                sourceConversationId = conversationId?.toString(),
                            )
                        },
                        onUpdate = { id, content ->
                            memoryEmbeddingService.updateMemory(
                                assistantId = memoryAssistantId,
                                id = id,
                                content = content,
                                settings = settings,
                            )
                        },
                        onDelete = { id ->
                            memoryRepo.deleteMemory(memoryAssistantId, id)
                        },
                        onList = {
                            memoryRepo.getMemoriesOfAssistant(memoryAssistantId)
                        },
                    ).let(this::addAll)
                }
                enabledKnowledgeBaseIds.takeIf { it.isNotEmpty() }?.let { knowledgeBaseIds ->
                    addAll(
                        createKnowledgeBaseTools(
                            knowledgeBaseIds = knowledgeBaseIds,
                            repository = knowledgeBaseRepository,
                        )
                    )
                }
                addAll(tools)
            }
            val toolCallsAvailable = model.abilities.contains(ModelAbility.TOOL)
            val toolsInternal = if (toolCallsAvailable) {
                registeredTools + createSessionCapabilitiesTool(
                    assistant = assistant,
                    toolCallsAvailable = true,
                    availableToolNames = { registeredTools.map(Tool::name) + "get_session_capabilities" },
                    knowledgeBaseCapabilities = knowledgeBaseCapabilities,
                )
            } else {
                emptyList()
            }
            val nonToolCapabilityPrompt = if (toolCallsAvailable) "" else {
                buildNonToolCapabilityPrompt(assistant, registeredTools, knowledgeBaseCapabilities)
            }

            // Check if we have tool calls ready to continue after user interaction.
            val pendingTools = messages.lastOrNull()?.getTools()?.filter {
                it.canResumeExecution
            } ?: emptyList()

            val toolsToProcess: List<UIMessagePart.Tool>

            // Skip generation if we have approved/denied tool calls to handle
            if (pendingTools.isEmpty()) {
                if (messages.lastOrNull()?.role == MessageRole.ASSISTANT &&
                    messages.last().finishedAt != null
                ) {
                    messages = messages.dropLast(1) + messages.last().copy(finishedAt = null)
                    emit(GenerationChunk.Messages(messages))
                }
                val requestCitations = generateInternal(
                    assistant = assistant,
                    settings = settings,
                    messages = requestMessages(),
                    onUpdateMessages = {
                        val transformedMessages = it.transforms(
                            transformers = outputTransformers,
                            context = context,
                            model = model,
                            assistant = assistant,
                            settings = settings
                        )
                        messages = if (requestStartIndex > 0) {
                            messages.take(requestStartIndex) + transformedMessages
                        } else {
                            transformedMessages
                        }
                        emit(
                            GenerationChunk.Messages(
                                messages.visualTransforms(
                                    transformers = outputTransformers,
                                    context = context,
                                    model = model,
                                    assistant = assistant,
                                    settings = settings
                                )
                            )
                        )
                    },
                    transformers = inputTransformers,
                    model = model,
                    providerImpl = providerImpl,
                    provider = provider,
                    tools = toolsInternal,
                    memories = memories ?: emptyList(),
                    stream = assistant.streamOutput,
                    processingStatus = processingStatus,
                    conversationSystemPrompt = conversationSystemPrompt,
                    conversationModeInjectionIds = conversationModeInjectionIds,
                    conversationLorebookIds = conversationLorebookIds,
                    workspaceCwd = workspaceCwd,
                    nonToolCapabilityPrompt = nonToolCapabilityPrompt,
                    rollingContextSummary = rollingContextSummary,
                    resumeInterruptedResponse = needsInterruptedResponseContinuation,
                )
                needsInterruptedResponseContinuation = false
                messages = messages.withKnowledgeCitations(requestCitations)
                val generatedTools = messages.last().getTools().filter { !it.isExecuted }
                messages = messages.visualTransforms(
                    transformers = outputTransformers,
                    context = context,
                    model = model,
                    assistant = assistant,
                    settings = settings
                )
                messages = messages.onGenerationFinish(
                    transformers = outputTransformers,
                    context = context,
                    model = model,
                    assistant = assistant,
                    settings = settings
                )
                messages = messages.slice(0 until messages.lastIndex) + messages.last().copy(
                    finishedAt = Clock.System.now()
                        .toLocalDateTime(TimeZone.currentSystemDefault()),
                    interrupted = false,
                )
                conversationId?.let { id ->
                    messages.lastOrNull { it.role == MessageRole.ASSISTANT }?.let { assistantMessage ->
                        knowledgeBaseRepository.replaceCitations(
                            conversationId = id.toString(),
                            messageId = assistantMessage.id.toString(),
                            citations = assistantMessage.annotations.filterIsInstance<UIMessageAnnotation.KnowledgeCitation>(),
                        )
                    }
                }
                emit(GenerationChunk.Messages(messages))

                if (generatedTools.isEmpty()) {
                    // no tool calls, break
                    break
                }

                // Check for tools that need approval
                var hasPendingApproval = false
                val updatedTools = generatedTools.map { tool ->
                    val toolDef = toolsInternal.find { it.name == tool.toolName }
                    when {
                        // Tool needs approval and state is Auto -> set to Pending
                        toolDef?.needsApproval(tool.inputAsJson()) == true &&
                            tool.approvalState is ToolApprovalState.Auto -> {
                            hasPendingApproval = true
                            tool.copy(approvalState = ToolApprovalState.Pending)
                        }
                        // State is Pending -> keep waiting
                        tool.approvalState is ToolApprovalState.Pending -> {
                            hasPendingApproval = true
                            tool
                        }

                        else -> tool
                    }
                }

                // If any tools were updated to Pending, update the message and break
                if (updatedTools != generatedTools) {
                    val lastMessage = messages.last()
                    val updatedParts = lastMessage.parts.map { part ->
                        if (part is UIMessagePart.Tool) {
                            updatedTools.find { it.toolCallId == part.toolCallId } ?: part
                        } else {
                            part
                        }
                    }
                    messages = messages.dropLast(1) + lastMessage.copy(parts = updatedParts)
                    emit(GenerationChunk.Messages(messages))
                }

                // If there are pending approvals, break and wait for user
                if (hasPendingApproval) {
                    Log.i(TAG, "generateText: waiting for tool approval")
                    break
                }

                toolsToProcess = updatedTools
            } else {
                // Resuming after user interaction - use the resumable tools directly.
                Log.i(TAG, "generateText: resuming with ${pendingTools.size} resumable tools")
                toolsToProcess = messages.last().getTools().filter { it.canResumeExecution }
            }

            // Handle tools (execute approved tools, handle denied tools)
            val executedTools = arrayListOf<UIMessagePart.Tool>()
            toolsToProcess.forEach { tool ->
                when (tool.approvalState) {
                    is ToolApprovalState.Denied -> {
                        // Tool was denied by user
                        val reason = (tool.approvalState as ToolApprovalState.Denied).reason
                        executedTools += tool.copy(
                            output = listOf(
                                UIMessagePart.Text(
                                    json.encodeToString(
                                        buildJsonObject {
                                            put(
                                                "error",
                                                JsonPrimitive("Tool execution denied by user. Reason: ${reason.ifBlank { "No reason provided" }}")
                                            )
                                        }
                                    )
                                )
                            )
                        )
                    }

                    is ToolApprovalState.Answered -> {
                        // Tool was answered by user (e.g., ask_user tool)
                        val answer = (tool.approvalState as ToolApprovalState.Answered).answer
                        executedTools += tool.copy(
                            output = listOf(
                                UIMessagePart.Text(answer)
                            )
                        )
                    }

                    is ToolApprovalState.Pending -> {
                        // Should not reach here, but just in case
                    }

                    else -> {
                        // Auto or Approved - execute the tool
                        runCatching {
                            val toolDef = toolsInternal.find { toolDef -> toolDef.name == tool.toolName }
                                ?: error("Tool ${tool.toolName} not found")
                            val args = runCatching {
                                json.parseToJsonElement(tool.input.ifBlank { "{}" })
                            }.getOrElse {
                                error("Invalid tool arguments JSON for ${tool.toolName}: ${it.message}")
                            }
                            Log.i(TAG, "generateText: executing tool ${toolDef.name} with args: $args")
                            val result = toolDef.execute(args)
                            val hasShellAccess = toolsInternal.any { it.name == "workspace_shell" }
                            executedTools += tool.copy(
                                output = maybeTruncateToolOutput(tool.toolCallId, result, hasShellAccess)
                            )
                        }.onFailure {
                            // 取消必须向上传播，否则停止生成会被误报为工具执行错误
                            if (it is CancellationException) throw it
                            it.printStackTrace()
                            executedTools += tool.copy(
                                output = listOf(
                                    UIMessagePart.Text(
                                        json.encodeToString(
                                            buildJsonObject {
                                                put(
                                                    "error",
                                                    JsonPrimitive(buildString {
                                                        append("[${it.javaClass.name}] ${it.message}")
                                                        append("\n${it.stackTraceToString()}")
                                                    })
                                                )
                                            }
                                        )
                                    )
                                )
                            )
                        }
                    }
                }
            }

            if (executedTools.isEmpty()) {
                // No results to add (all tools were pending)
                break
            }

            // Update last message with executed tools (NOT create TOOL message)
            val lastMessage = messages.last()
            val updatedParts = lastMessage.parts.map { part ->
                if (part is UIMessagePart.Tool) {
                    executedTools.find { it.toolCallId == part.toolCallId } ?: part
                } else part
            }
            messages = messages.dropLast(1) + lastMessage.copy(parts = updatedParts)
            messages = messages.withKnowledgeCitationsFromToolResults()
            emit(
                GenerationChunk.Messages(
                    messages.transforms(
                        transformers = outputTransformers,
                        context = context,
                        model = model,
                        assistant = assistant,
                        settings = settings
                    )
                )
            )
        }

    }.flowOn(Dispatchers.IO)

    private suspend fun generateInternal(
        assistant: Assistant,
        settings: Settings,
        messages: List<UIMessage>,
        onUpdateMessages: suspend (List<UIMessage>) -> Unit,
        transformers: List<MessageTransformer>,
        model: Model,
        providerImpl: Provider<ProviderSetting>,
        provider: ProviderSetting,
        tools: List<Tool>,
        memories: List<AssistantMemory>,
        stream: Boolean,
        processingStatus: MutableStateFlow<String?> = MutableStateFlow(null),
        conversationSystemPrompt: String? = null,
        conversationModeInjectionIds: Set<Uuid> = emptySet(),
        conversationLorebookIds: Set<Uuid> = emptySet(),
        workspaceCwd: String? = null,
        nonToolCapabilityPrompt: String = "",
        rollingContextSummary: String? = null,
        resumeInterruptedResponse: Boolean = false,
    ): List<UIMessageAnnotation.KnowledgeCitation> {
        val internalMessages = buildList {
            val system = buildString {
                val effectiveSystemPrompt =
                    if (assistant.allowConversationSystemPrompt && !conversationSystemPrompt.isNullOrBlank()) {
                        conversationSystemPrompt
                    } else {
                        assistant.systemPrompt
                    }
                if (effectiveSystemPrompt.isNotBlank()) {
                    append(effectiveSystemPrompt)
                }
                if (nonToolCapabilityPrompt.isNotBlank()) {
                    appendLine()
                    append(nonToolCapabilityPrompt)
                }
                if (!rollingContextSummary.isNullOrBlank()) {
                    appendLine()
                    append(ROLLING_CONTEXT_SYSTEM_PROMPT)
                    appendLine()
                    append(rollingContextSummary)
                    appendLine()
                    append("</rolling_context_summary>")
                }

                // 记忆
                if (assistant.enableMemory && !assistant.enableMemoryRag) {
                    appendLine()
                    append(buildMemoryPrompt(memories = memories))
                }
                // 工具prompt
                tools.forEach { tool ->
                    appendLine()
                    append(tool.systemPrompt(model, messages))
                }
            }
            if (system.isNotBlank()) add(UIMessage.system(prompt = system))
            addAll(messages)
        }.transforms(
            transformers = transformers,
            context = context,
            model = model,
            assistant = assistant,
            settings = settings,
            conversationModeInjectionIds = conversationModeInjectionIds,
            conversationLorebookIds = conversationLorebookIds,
            processingStatus = processingStatus,
            workspaceCwd = workspaceCwd,
        ).compactHistoricalMediaForRequest(
            mediaSizeBytes = ::resolveContentMediaSize,
        )
        val initialProviderMessages = if (resumeInterruptedResponse) {
            buildInterruptedStreamContinuation(
                requestMessages = internalMessages,
                currentMessages = messages,
                hasClientTools = tools.isNotEmpty(),
                hasServerTools = model.tools.any { it != BuiltInTools.ImageGeneration },
            ) ?: internalMessages
        } else {
            internalMessages
        }
        val requestCitations = initialProviderMessages.currentRequestKnowledgeCitations()
            .plus(
                messages.lastOrNull()
                    ?.getTools()
                    .orEmpty()
                    .flatMap { tool -> tool.knowledgeCitationsFromOutput() }
            )
            .distinctBy { it.chunkId }

        var messages: List<UIMessage> = messages
        val params = TextGenerationParams(
            model = model,
            temperature = assistant.temperature,
            topP = assistant.topP,
            maxTokens = assistant.maxTokens,
            tools = tools,
            reasoningLevel = assistant.reasoningLevel,
            openAIOptions = assistant.openAIOptions,
            grokOptions = assistant.grokOptions,
            qwenOptions = assistant.qwenOptions,
            deepSeekOptions = assistant.deepSeekOptions,
            geminiOptions = assistant.geminiOptions,
            claudeOptions = assistant.claudeOptions,
            customHeaders = buildList {
                addAll(assistant.customHeaders)
                addAll(model.customHeaders)
            },
            customBody = buildList {
                addAll(assistant.customBodies)
                addAll(model.customBodies)
            }
        )
        val previousProcessingStatus = processingStatus.value
        var receivedEffectiveOutput = false
        var providerMessages = initialProviderMessages
        var streamRecoveryCount = 0
        val nativeImageOutputEnabled = BuiltInTools.ImageGeneration in model.tools ||
            Modality.IMAGE in model.outputModalities
        val hasReplayUnsafeBuiltInTools = model.tools.any { it != BuiltInTools.ImageGeneration }
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
        try {
            while (true) {
                try {
                    retryProviderRequest(
                        enabled = settings.enableGenerationRetry,
                        retryController = retryController,
                        canRetry = { !receivedEffectiveOutput },
                        shouldRetry = { error ->
                            networkRecovery.shouldRetry(error, attemptNetworkVersion)
                        },
                        onRetry = { retryNumber, delayMillis ->
                            Log.w(
                                TAG,
                                "generateInternal: retry #$retryNumber in ${delayMillis}ms (${model.id})"
                            )
                            processingStatus.value = context.getString(
                                R.string.chat_page_generation_retrying,
                                retryNumber,
                            )
                        },
                        delayBeforeRetry = { delayMillis ->
                            networkRecovery.awaitNetworkAndBackoff(
                                retryDelayMillis = delayMillis,
                                remainingDurationMillis = retryController.remainingDurationMillis(),
                            )
                        },
                    ) {
                        attemptNetworkVersion = networkRecovery.snapshot()
                        receivedEffectiveOutput = false
                        if (stream) {
                            val streamChunkHandler = StreamChunkHandler(model)
                            val preBoundaryChunks = mutableListOf<StreamChunk>()
                            var lastUiUpdateNanos = 0L
                            var hasPendingUiUpdate = false

                            suspend fun flushStreamUpdate() {
                                messages = messages.withKnowledgeCitations(requestCitations)
                                onUpdateMessages(messages)
                                lastUiUpdateNanos = System.nanoTime()
                                hasPendingUiUpdate = false
                            }

                            suspend fun handleChunk(chunk: StreamChunk) {
                                messages = streamChunkHandler.handle(messages, chunk)
                                val now = System.nanoTime()
                                if (chunk.requiresImmediateUiUpdate() ||
                                    now - lastUiUpdateNanos >= STREAM_UI_UPDATE_INTERVAL_NANOS
                                ) {
                                    flushStreamUpdate()
                                } else {
                                    hasPendingUiUpdate = true
                                }
                            }

                            providerImpl.streamText(
                                providerSetting = provider,
                                messages = providerMessages,
                                params = params
                            ).collect { chunk ->
                                if (!receivedEffectiveOutput) {
                                    preBoundaryChunks += chunk
                                    if (chunk.crossesRequestReplayBoundary(
                                            reasoningIsReplaySafe = nativeImageOutputEnabled,
                                        )
                                    ) {
                                        receivedEffectiveOutput = true
                                        processingStatus.value = previousProcessingStatus
                                        for (pendingChunk in preBoundaryChunks) {
                                            messages = streamChunkHandler.handle(messages, pendingChunk)
                                        }
                                        preBoundaryChunks.clear()
                                        flushStreamUpdate()
                                    }
                                } else {
                                    handleChunk(chunk)
                                }
                            }

                            if (!receivedEffectiveOutput && preBoundaryChunks.isNotEmpty()) {
                                for (pendingChunk in preBoundaryChunks) {
                                    messages = streamChunkHandler.handle(messages, pendingChunk)
                                }
                                flushStreamUpdate()
                            } else if (hasPendingUiUpdate) {
                                flushStreamUpdate()
                            }
                        } else {
                            val result = providerImpl.generateText(
                                providerSetting = provider,
                                messages = providerMessages,
                                params = params,
                            )
                            receivedEffectiveOutput = true
                            messages = messages.handleTextGenerationResult(result = result, model = model)
                                .withKnowledgeCitations(requestCitations)
                            onUpdateMessages(messages)
                        }
                    }
                    if (streamRecoveryCount > 0 || resumeInterruptedResponse) {
                        val mergedMessages = messages.mergeLastAssistantTextParts()
                        if (mergedMessages !== messages) {
                            messages = mergedMessages
                            onUpdateMessages(messages)
                        }
                    }
                    break
                } catch (error: Throwable) {
                    if (error is CancellationException) currentCoroutineContext().ensureActive()
                    val canContinueInterruptedStream =
                        stream &&
                        settings.enableGenerationRetry &&
                        receivedEffectiveOutput &&
                        networkRecovery.shouldRetry(error, attemptNetworkVersion)
                    val continuationMessages = if (canContinueInterruptedStream) {
                        val inertMessages = messages.mapLastAssistant(
                            UIMessage::markInterruptedToolsForContinuation,
                        )
                        if (inertMessages != messages) {
                            messages = inertMessages
                            onUpdateMessages(messages)
                        }
                        buildInterruptedStreamContinuation(
                            requestMessages = internalMessages,
                            currentMessages = messages,
                            hasClientTools = tools.isNotEmpty(),
                            hasServerTools = hasReplayUnsafeBuiltInTools,
                        )
                    } else {
                        null
                    }
                    if (continuationMessages == null) throw error

                    val reconnectScheduled = retryController.waitBeforeRetry(
                        error = error,
                        onRetry = { retryNumber, delayMillis ->
                            Log.w(
                                TAG,
                                "generateInternal: reconnect #$retryNumber in ${delayMillis}ms (${model.id})",
                                error,
                            )
                            processingStatus.value = context.getString(
                                R.string.chat_page_generation_resuming,
                            )
                        },
                        delayBeforeRetry = { delayMillis ->
                            networkRecovery.awaitNetworkAndBackoff(
                                retryDelayMillis = delayMillis,
                                remainingDurationMillis = retryController.remainingDurationMillis(),
                            )
                        },
                    )
                    if (!reconnectScheduled) throw error

                    streamRecoveryCount++
                    providerMessages = continuationMessages
                }
            }
        } finally {
            networkRecovery.close()
            processingStatus.value = previousProcessingStatus
        }
        return requestCitations
    }

    private fun List<UIMessage>.mapLastAssistant(
        transform: (UIMessage) -> UIMessage,
    ): List<UIMessage> {
        val lastMessage = lastOrNull()?.takeIf { it.role == MessageRole.ASSISTANT } ?: return this
        val updated = transform(lastMessage)
        return if (updated == lastMessage) this else dropLast(1) + updated
    }

    private fun resolveContentMediaSize(url: String): Long? {
        if (!url.startsWith("content://", ignoreCase = true)) return null
        val uri = Uri.parse(url)
        val descriptorLength = runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
                descriptor.length.takeIf { it >= 0L }
            }
        }.getOrNull()
        if (descriptorLength != null) return descriptorLength

        return runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.SIZE),
                null,
                null,
                null,
            )?.use { cursor ->
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0 && cursor.moveToFirst() && !cursor.isNull(sizeIndex)) {
                    cursor.getLong(sizeIndex).takeIf { it >= 0L }
                } else {
                    null
                }
            }
        }.getOrNull()
    }

    private fun List<UIMessage>.withKnowledgeCitations(
        citations: List<UIMessageAnnotation.KnowledgeCitation>
    ): List<UIMessage> {
        if (citations.isEmpty()) return this
        val index = indexOfLast { it.role == MessageRole.ASSISTANT }
        if (index < 0) return this
        val newCitations = citations.filterNot { citation ->
            this[index].annotations.any { it == citation }
        }
        if (newCitations.isEmpty()) return this
        return toMutableList().apply {
            this[index] = this[index].copy(
                annotations = this[index].annotations + newCitations
            )
        }
    }

    /**
     * kb_search outputs structured excerpts. Carry their provenance forward so a
     * subsequent model response receives the same source cards as automatic
     * background retrieval.
     */
    private fun List<UIMessage>.withKnowledgeCitationsFromToolResults(): List<UIMessage> {
        val index = indexOfLast { it.role == MessageRole.ASSISTANT }
        if (index < 0) return this
        val citations = this[index].getTools()
            .flatMap { tool -> tool.knowledgeCitationsFromOutput() }
            .distinctBy(UIMessageAnnotation.KnowledgeCitation::chunkId)
        if (citations.isEmpty()) return this
        return toMutableList().apply {
            this[index] = this[index].copy(
                annotations = this[index].annotations + citations.filterNot { citation ->
                    this[index].annotations.any { it == citation }
                }
            )
        }
    }

    /** Text and argument deltas can arrive dozens of times per second. Structural state must stay immediate. */
    private fun StreamChunk.requiresImmediateUiUpdate(): Boolean = when (this) {
        is StreamChunk.TextDelta,
        is StreamChunk.ReasoningDelta,
        is StreamChunk.ToolCallDelta,
        is StreamChunk.ServerToolInputDelta,
        is StreamChunk.ImageDelta -> false
        else -> true
    }

    private fun UIMessagePart.Tool.knowledgeCitationsFromOutput(): List<UIMessageAnnotation.KnowledgeCitation> {
        if (toolName != "kb_search") return emptyList()
        return output.filterIsInstance<UIMessagePart.Text>().flatMap { part ->
            runCatching {
                val results = json.parseToJsonElement(part.text)
                    .jsonObject["results"]
                    ?.jsonArray
                    .orEmpty()
                results.mapNotNull { element ->
                    val result = element.jsonObject
                    val chunkId = result.string("chunkId") ?: return@mapNotNull null
                    val knowledgeBaseId = result.string("knowledgeBaseId") ?: return@mapNotNull null
                    val documentId = result.string("documentId") ?: return@mapNotNull null
                    UIMessageAnnotation.KnowledgeCitation(
                        chunkId = chunkId,
                        knowledgeBaseId = knowledgeBaseId,
                        documentId = documentId,
                        title = result.string("title").orEmpty(),
                        sourceUri = result.string("sourceUri").orEmpty(),
                        excerpt = result.string("excerpt").orEmpty(),
                        score = result["score"]?.jsonPrimitive?.floatOrNull ?: 0f,
                        pageStart = result["pageStart"]?.jsonPrimitive?.contentOrNull?.toIntOrNull(),
                        pageEnd = result["pageEnd"]?.jsonPrimitive?.contentOrNull?.toIntOrNull(),
                        sectionPath = result.string("sectionPath").orEmpty(),
                    )
                }
            }.getOrDefault(emptyList())
        }
    }

    private fun kotlinx.serialization.json.JsonObject.string(name: String): String? =
        this[name]?.jsonPrimitive?.contentOrNull

    private fun buildNonToolCapabilityPrompt(
        assistant: Assistant,
        registeredTools: List<Tool>,
        knowledgeBaseCapabilities: KnowledgeBaseCapabilities,
    ): String {
        val memoryMode = when {
            !assistant.enableMemory -> "disabled"
            assistant.enableMemoryRag -> "rag_background"
            else -> "basic_prompt"
        }
        return buildString {
            appendLine("<session_capabilities>")
            appendLine("Function tool calls are unavailable because the active chat model does not have tool calling enabled. Do not claim that a tool was called.")
            appendLine("Memory mode: $memoryMode")
            val knowledgeBaseMode = when {
                knowledgeBaseCapabilities.boundCount == 0 -> "ui_management_only"
                knowledgeBaseCapabilities.enabledCount == 0 -> "disabled_by_user"
                knowledgeBaseCapabilities.ragEnabledCount > 0 -> "background_silent_retrieval"
                else -> "tool_only_but_unavailable_to_this_model"
            }
            appendLine("Knowledge base mode: $knowledgeBaseMode.")
            if (registeredTools.isNotEmpty()) {
                appendLine("Configured app tools (${registeredTools.joinToString { it.name }}) are not sent in this request.")
            }
            append("</session_capabilities>")
        }
    }

    private fun maybeTruncateToolOutput(
        toolCallId: String,
        output: List<UIMessagePart>,
        hasShellAccess: Boolean,
    ): List<UIMessagePart> {
        val textParts = output.filterIsInstance<UIMessagePart.Text>()
        val nonTextParts = output.filter { it !is UIMessagePart.Text }
        val totalChars = textParts.sumOf { it.text.length }

        if (totalChars <= MAX_TOOL_OUTPUT_CHARS || !hasShellAccess) return output

        Log.i(TAG, "maybeTruncateToolOutput: truncating tool $toolCallId output ($totalChars chars)")

        val fullText = textParts.joinToString("\n") { it.text }
        val preview = fullText.take(TOOL_OUTPUT_PREVIEW_CHARS)

        val fileName = "${toolCallId}.txt"
        val outputDir = File(context.filesDir, FileFolders.TOOL_OUTPUTS).apply { mkdirs() }
        File(outputDir, fileName).writeText(fullText)

        return listOf(
            UIMessagePart.Text(
                buildString {
                    appendLine("[Tool output truncated: $totalChars characters total]")
                    appendLine("Full output saved to: /tool_outputs/$fileName")
                    appendLine("Use shell to read: `cat /tool_outputs/$fileName`")
                    appendLine("Use shell to search: `grep \"pattern\" /tool_outputs/$fileName`")
                    appendLine()
                    append(preview)
                }
            )
        ) + nonTextParts
    }

    fun translateText(
        settings: Settings,
        sourceText: String,
        targetLanguage: Locale,
        onStreamUpdate: ((String) -> Unit)? = null
    ): Flow<String> = flow {
        val model = settings.providers.findModelById(settings.translateModeId)
            ?: error("Translation model not found")
        val provider = model.findProvider(settings.providers)
            ?: error("Translation provider not found")

        val providerHandler = providerManager.getProviderByType(provider)

        if (!ModelRegistry.QWEN_MT.match(model.modelId)) {
            // Use regular translation with prompt
            val prompt = settings.translatePrompt.applyPlaceholders(
                "source_text" to sourceText,
                "target_lang" to targetLanguage.toString(),
            )

            var messages = listOf(UIMessage.user(prompt))
            var translatedText = ""
            val streamChunkHandler = StreamChunkHandler(model)

            providerHandler.streamText(
                providerSetting = provider,
                messages = messages,
                params = TextGenerationParams(
                    model = model,
                    reasoningLevel = ReasoningLevel.fromBudgetTokens(settings.translateThinkingBudget),
                ),
            ).collect { chunk ->
                messages = streamChunkHandler.handle(messages, chunk)
                translatedText = messages.lastOrNull()?.toText() ?: ""

                if (translatedText.isNotBlank()) {
                    onStreamUpdate?.invoke(translatedText)
                    emit(translatedText)
                }
            }
        } else {
            // Use Qwen MT model with special translation options
            val messages = listOf(UIMessage.user(sourceText))
            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = messages,
                params = TextGenerationParams(
                    model = model,
                    temperature = 0.3f,
                    topP = 0.95f,
                    customBody = listOf(
                        CustomBody(
                            key = "translation_options",
                            value = buildJsonObject {
                                put("source_lang", JsonPrimitive("auto"))
                                put(
                                    "target_lang",
                                    JsonPrimitive(targetLanguage.getDisplayLanguage(Locale.ENGLISH))
                                )
                            }
                        )
                    )
                ),
            )
            val translatedText = result.message.toText()

            if (translatedText.isNotBlank()) {
                onStreamUpdate?.invoke(translatedText)
                emit(translatedText)
            }
        }
    }.flowOn(Dispatchers.IO)
}
