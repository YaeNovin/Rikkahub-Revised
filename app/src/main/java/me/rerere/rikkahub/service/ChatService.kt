package me.rerere.rikkahub.service

import me.rerere.rikkahub.data.datastore.resolveMemoryExtractionModel

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
import kotlinx.coroutines.delay
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
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
import me.rerere.ai.ui.AskUserProtocol
import me.rerere.ai.ui.AskUserInteraction
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
import me.rerere.rikkahub.data.memory.MemoryExtractionService
import me.rerere.rikkahub.data.memory.MemoryExtractionOutcome
import me.rerere.rikkahub.data.memory.MemoryExtractionProcessResult
import me.rerere.rikkahub.data.memory.MemoryExtractionStatus
import me.rerere.rikkahub.data.memory.ConversationMemoryIndexService
import me.rerere.rikkahub.data.memory.MEMORY_EXTRACTION_IDLE_DELAY_MS
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
import me.rerere.rikkahub.data.datastore.ChatSuggestionStyle
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.data.datastore.resolveBackgroundChatModel
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.memoryAssistant
import me.rerere.rikkahub.data.model.ChatSuggestionAction
import me.rerere.rikkahub.data.model.ChatSuggestionCategory
import me.rerere.rikkahub.data.model.ChatSuggestionItem
import me.rerere.rikkahub.data.model.availableSuggestionActions
import me.rerere.rikkahub.data.model.suggestionSourceMessage
import me.rerere.rikkahub.data.model.suggestionContextKey
import me.rerere.rikkahub.data.model.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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

private const val MAX_ALTERNATIVE_RESPONSES = 5

private const val TAG = "ChatService"
private const val STREAM_STATE_UPDATE_INTERVAL_MILLIS = 83L
private const val DEFAULT_COMPRESSION_INPUT_BUDGET_TOKENS = 24_000
private const val MIN_INTERMEDIATE_SUMMARY_TOKENS = 512
private const val MAX_INTERMEDIATE_SUMMARY_TOKENS = 2_048
private const val MAX_COMPRESSION_HIERARCHY_DEPTH = 4
private const val DEFAULT_TITLE_MAX_LENGTH = 24
private const val DEFAULT_SUGGESTION_MAX_LENGTH = 80
private const val DEFAULT_SUGGESTION_COUNT = 5

private fun String.withoutMarkdownFence(): String {
    val trimmed = trim()
    if (!trimmed.startsWith("```")) return trimmed
    val withoutOpeningFence = trimmed.removePrefix("```")
    val content = if ('\n' in withoutOpeningFence) {
        withoutOpeningFence.substringAfter('\n')
    } else {
        withoutOpeningFence
    }
    return content.removeSuffix("```").trim()
}

private fun String.takeCodePoints(maxLength: Int): String {
    if (maxLength <= 0 || isEmpty()) return ""
    val count = codePointCount(0, length)
    if (count <= maxLength) return this
    return substring(0, offsetByCodePoints(0, maxLength)).trimEnd()
}

internal fun normalizeGeneratedTitle(
    raw: String,
    maxLength: Int = DEFAULT_TITLE_MAX_LENGTH,
): String? {
    val candidate = raw.withoutMarkdownFence()
        .lineSequence()
        .map(String::trim)
        .firstOrNull(String::isNotBlank)
        .orEmpty()
        .replace(Regex("^(?:#{1,6}|[-*>])\\s*"), "")
        .replace(
            Regex(
                "^(?:(?:conversation\\s*)?title|对话标题|标题)\\s*[:：-]\\s*",
                RegexOption.IGNORE_CASE,
            ),
            "",
        )
        .trim()
        .trim('"', '\'', '`', '“', '”', '‘', '’')
        .trim()
        .takeCodePoints(maxLength.coerceAtLeast(1))
    return candidate.takeIf(String::isNotBlank)
}

private fun JsonObject.suggestionValues(): List<JsonElement>? {
    val value = this["suggestions"] ?: this["replies"] ?: this["items"] ?: return null
    return (value as? JsonArray)?.toList()
}

private fun String.toSuggestionCategory(): ChatSuggestionCategory = when (
    trim().lowercase(Locale.ROOT).replace('-', '_').replace(' ', '_')
) {
    "action", "tool" -> ChatSuggestionCategory.ACTION
    "direction", "creative_direction" -> ChatSuggestionCategory.DIRECTION
    "dialogue" -> ChatSuggestionCategory.DIALOGUE
    "role_action" -> ChatSuggestionCategory.ROLE_ACTION
    "inner_thought" -> ChatSuggestionCategory.INNER_THOUGHT
    "plot" -> ChatSuggestionCategory.PLOT
    else -> ChatSuggestionCategory.FOLLOW_UP
}

private fun String.toSuggestionAction(): ChatSuggestionAction = when (
    trim().lowercase(Locale.ROOT).replace('-', '_').replace(' ', '_')
) {
    "copy", "copy_text" -> ChatSuggestionAction.COPY_TEXT
    "save", "save_quick_message" -> ChatSuggestionAction.SAVE_QUICK_MESSAGE
    "branch", "create_branch" -> ChatSuggestionAction.CREATE_BRANCH
    "search", "search_web" -> ChatSuggestionAction.SEARCH_WEB
    "knowledge", "knowledge_base", "search_knowledge" -> ChatSuggestionAction.SEARCH_KNOWLEDGE
    "memory", "search_memory" -> ChatSuggestionAction.SEARCH_MEMORY
    "conversation", "recent_chats", "search_conversations" -> ChatSuggestionAction.SEARCH_CONVERSATIONS
    "ask", "ask_user" -> ChatSuggestionAction.ASK_USER
    "workspace" -> ChatSuggestionAction.WORKSPACE
    "skill", "use_skill" -> ChatSuggestionAction.USE_SKILL
    "mcp" -> ChatSuggestionAction.MCP
    "image_draft" -> ChatSuggestionAction.IMAGE_DRAFT
    else -> ChatSuggestionAction.INSERT_TEXT
}

private fun JsonElement.toSuggestionItem(maxLength: Int): ChatSuggestionItem? = when (this) {
    is JsonPrimitive -> takeIf { isString }?.contentOrNull?.trim()?.takeIf(String::isNotBlank)?.let { text ->
        ChatSuggestionItem(text = text.takeCodePoints(maxLength.coerceAtLeast(1)))
    }

    is JsonObject -> {
        val text = listOf("text", "title", "label", "content")
            .firstNotNullOfOrNull { key -> (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf(String::isNotBlank) }
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: return null
        val action = (this["action"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull.orEmpty().toSuggestionAction()
        ChatSuggestionItem(
            text = text.takeCodePoints(maxLength.coerceAtLeast(1)),
            description = (this["description"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                ?.trim()
                ?.takeCodePoints(ChatSuggestionContract.MAX_DESCRIPTION)
                .orEmpty(),
            category = (this["category"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                .orEmpty()
                .toSuggestionCategory(),
            action = action,
            payload = listOf("payload", "query", "value")
                .firstNotNullOfOrNull { key -> (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf(String::isNotBlank) }
                ?.trim()
                ?.takeCodePoints(ChatSuggestionContract.MAX_PAYLOAD)
                .orEmpty(),
            parameterForm = (this["parameters"] as? JsonObject)?.takeIf { form ->
                AskUserProtocol.parseRequest(form).getOrNull()?.let { request ->
                    action in ChatSuggestionContract.formActions &&
                        request.questions.size <= ChatSuggestionContract.MAX_QUESTIONS &&
                        request.questions.all { it.selectionType.wireValue in ChatSuggestionContract.fieldTypes }
                } == true
            },
        ).takeIf { item ->
            if (this["parameters"] == null || this["parameters"] == kotlinx.serialization.json.JsonNull) true else {
                val form = item.parameterForm ?: return@takeIf false
                val request = AskUserProtocol.parseRequest(form).getOrNull() ?: return@takeIf false
                val placeholders = QuickMessage(title = item.text, content = item.payload.ifBlank { item.text }).placeholderNames()
                placeholders.isNotEmpty() && placeholders.all { name -> request.questions.any { it.id == name } }
            }
        }
    }

    else -> null
}

internal fun normalizeGeneratedSuggestionItems(
    raw: String,
    maxCount: Int = DEFAULT_SUGGESTION_COUNT,
    maxLength: Int = DEFAULT_SUGGESTION_MAX_LENGTH,
): List<ChatSuggestionItem> {
    if (raw.length > 256 * 1024) return emptyList()
    val cleaned = raw.withoutMarkdownFence()
    val structured = runCatching { Json.parseToJsonElement(cleaned) }.getOrNull()
    val candidates = when (structured) {
        is JsonArray -> structured.toList()
        is JsonObject -> structured.suggestionValues()
        else -> null
    }
    // Invalid structured output must not become chips containing raw JSON or error fields.
    if (candidates == null && (structured != null || cleaned.trimStart().startsWith('{') || cleaned.trimStart().startsWith('['))) {
        return emptyList()
    }

    return (candidates?.mapNotNull { it.toSuggestionItem(maxLength) }
        ?: cleaned.lineSequence().mapNotNull { line ->
            line.trim()
                .replace(Regex("^(?:[-*•]|\\d+[.)、])\\s*"), "")
                .trim('"', '\'', '`', '“', '”', '‘', '’')
                .trim()
                .takeIf(String::isNotBlank)
                ?.takeUnless {
                    it.matches(
                        Regex(
                            "^(?:suggestions?|suggested replies|回复建议|建议)\\s*[:：]?$",
                            RegexOption.IGNORE_CASE,
                        )
                    )
                }
                ?.let { ChatSuggestionItem(text = it.takeCodePoints(maxLength.coerceAtLeast(1))) }
        }.toList())
        .asSequence()
        .filter { it.text.isNotBlank() }
        .distinctBy { it.action to suggestionTextKey(it.payload.ifBlank { it.text }) }
        .take(maxCount.coerceAtLeast(0))
        .toList()
}

internal fun normalizeGeneratedSuggestions(
    raw: String,
    maxCount: Int = DEFAULT_SUGGESTION_COUNT,
    maxLength: Int = DEFAULT_SUGGESTION_MAX_LENGTH,
): List<String> = normalizeGeneratedSuggestionItems(
    raw = raw,
    maxCount = maxCount,
    maxLength = maxLength,
).map(ChatSuggestionItem::text)

/** Keep the latest conclusions as well as the opening, without uploading attachment bytes. */
internal fun buildSuggestionContext(messages: List<UIMessage>): String = messages.takeLast(8).joinToString("\n\n") { message ->
    val text = message.toText()
    val count = text.codePointCount(0, text.length)
    val excerpt = if (count <= 1200) text else {
        text.takeCodePoints(800) + "\n[…]\n" + text.substring(text.offsetByCodePoints(0, count - 400))
    }
    val attachments = message.parts.mapNotNull { part ->
        when (part) {
            is UIMessagePart.Image -> "image"
            is UIMessagePart.Video -> "video"
            is UIMessagePart.Audio -> "audio"
            is UIMessagePart.Document -> "document"
            else -> null
        }
    }.groupingBy { it }.eachCount().entries.joinToString(", ") { (type, amount) -> "$type=$amount" }
    "[${message.role.name}]: $excerpt" + if (attachments.isEmpty()) "" else "\n[Attachments: $attachments; content not included]"
}

private fun ChatSuggestionStyle.instruction(): String = when (this) {
    ChatSuggestionStyle.BALANCED -> "Use a balanced mix of natural follow-ups and useful next actions."
    ChatSuggestionStyle.FOLLOW_UP -> "Prefer curious follow-up questions that deepen the conversation."
    ChatSuggestionStyle.ACTIONABLE -> "Prefer concrete next actions or requests the user can take."
    ChatSuggestionStyle.CONCISE -> "Keep every suggestion especially concise and direct."
    ChatSuggestionStyle.ROLEPLAY -> "Write immersive in-character replies that fit the current scene."
}

internal fun Conversation.normalizeAskUserPendingStates(
    nowMillis: Long,
    clock: AskUserInteraction.Clock = AskUserInteraction.Clock(nowMillis, 0, -1),
): Conversation {
    var changed = false
    val normalizedNodes = messageNodes.map nodeMap@{ node ->
        node.copy(
            messages = node.messages.map messageMap@{ message ->
                message.copy(
                    parts = message.parts.map partMap@{ part ->
                        if (part !is UIMessagePart.Tool ||
                            part.toolName != AskUserProtocol.TOOL_NAME ||
                            part.isExecuted ||
                            part.approvalState !is ToolApprovalState.Pending
                        ) {
                            return@partMap part
                        }

                        val pendingAt = part.metadata
                            ?.get(AskUserProtocol.PENDING_AT_METADATA_KEY)
                            ?.let { (it as? JsonPrimitive)?.longOrNull }
                        val request = AskUserProtocol.parseRequest(part.input).getOrNull()
                        val currentInteraction = AskUserInteraction.isCurrent(part.input, part.metadata)
                        val refreshedMetadata = if (currentInteraction && request != null) AskUserInteraction.update(
                            part.input, request, part.metadata, AskUserInteraction.answers(part.input, part.metadata), emptySet(), clock)
                            else part.metadata
                        val confirmTimeoutSeconds = request?.confirmationTimeoutSeconds().takeUnless { currentInteraction }
                        val effectivePendingAt = pendingAt ?: nowMillis
                        val deadline = part.metadata.takeUnless { currentInteraction }
                            ?.get(AskUserProtocol.CONFIRM_DEADLINE_METADATA_KEY)
                            ?.let { (it as? JsonPrimitive)?.longOrNull }
                            ?: confirmTimeoutSeconds?.let { effectivePendingAt + it * 1000L }
                        val needsMetadataUpdate = refreshedMetadata != part.metadata || pendingAt == null ||
                            (confirmTimeoutSeconds != null && part.metadata
                                ?.containsKey(AskUserProtocol.CONFIRM_DEADLINE_METADATA_KEY) != true)
                        val updatedMetadata = if (needsMetadataUpdate) {
                            changed = true
                            buildJsonObject {
                                refreshedMetadata?.forEach { (key, value) -> put(key, value) }
                                put(AskUserProtocol.PENDING_AT_METADATA_KEY, effectivePendingAt)
                                deadline?.let {
                                    put(AskUserProtocol.CONFIRM_DEADLINE_METADATA_KEY, it)
                                }
                            }
                        } else {
                            part.metadata
                        }
                        val confirmationExpired = if (currentInteraction && request != null) {
                            val draft = AskUserInteraction.answers(part.input, updatedMetadata)
                            val visible = request.questions.filter { AskUserProtocol.isQuestionVisible(it, draft) }
                            visible.isNotEmpty() && visible.all { it.hasConfirmationCountdown() && AskUserInteraction.timedOut(part.input, updatedMetadata, it.id, clock) }
                        } else deadline != null && nowMillis >= deadline
                        val approvalExpired = nowMillis >= effectivePendingAt &&
                            nowMillis - effectivePendingAt >= AskUserProtocol.APPROVAL_TIMEOUT_MILLIS
                        if (confirmationExpired || approvalExpired) {
                            changed = true
                            val reason = if (confirmationExpired) {
                                "Confirmation timed out and was rejected"
                            } else {
                                "Tool approval expired after ${AskUserProtocol.APPROVAL_TIMEOUT_MILLIS / 60_000} minutes"
                            }
                            val status = if (confirmationExpired) "denied" else "expired"
                            return@partMap part.copy(
                                output = listOf(
                                    UIMessagePart.Text(
                                        buildJsonObject {
                                            put("status", status)
                                            put("error", reason)
                                        }.toString()
                                    )
                                ),
                                approvalState = if (confirmationExpired) {
                                    ToolApprovalState.Denied(reason)
                                } else {
                                    ToolApprovalState.Expired(reason)
                                },
                                metadata = JsonObject(updatedMetadata.orEmpty() - AskUserInteraction.KEY),
                            )
                        }
                        if (needsMetadataUpdate) {
                            return@partMap part.copy(metadata = updatedMetadata)
                        }
                        part
                    }
                )
            }
        )
    }
    return if (changed) copy(messageNodes = normalizedNodes) else this
}

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

enum class ToolApprovalResult {
    APPLIED,
    NOT_FOUND,
    ALREADY_HANDLED,
    INVALID_REQUEST,
    EXPIRED,
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
        title = "分支•${currentConversation.title}",
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
        memoryMode = currentConversation.memoryMode,
        suggestionSession = SuggestionSession(settings = currentConversation.suggestionSession.settings, paused = currentConversation.suggestionSession.paused),
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
    private val memoryMaintenance: me.rerere.rikkahub.data.memory.MemoryMaintenanceService,
    private val knowledgeBaseRepository: me.rerere.rikkahub.data.repository.KnowledgeBaseRepository,
    private val memoryExtractionService: MemoryExtractionService,
    private val conversationMemoryIndexService: ConversationMemoryIndexService,
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
    private val generationKeepAlive: GenerationKeepAlive? = null,
) {
    // workspace 系统提示注入 (依赖 workspaceRepository, 故在类内构造)
    private val workspaceReminderTransformer = WorkspaceReminderTransformer(workspaceRepository)

    // 统一会话管理
    private val sessions = ConcurrentHashMap<Uuid, ConversationSession>()
    private val askUserDraftWrites = mutableMapOf<Uuid, Job>()
    private val _sessionsVersion = MutableStateFlow(0L)

    private val suggestionSettingsWatch = appScope.launch {
        settingsStore.settingsFlow.collect { settings ->
            sessions.values.forEach { session ->
                if (session.suggestionGeneration.state.value == SuggestionGenerationState.GENERATING &&
                    session.state.value.suggestionConfig(settings).options.trigger == SuggestionTrigger.DISABLED) {
                    session.nextSuggestionGeneration()
                }
            }
        }
    }

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
                onIdle = { removeSession(it) },
                onJobStarted = { generationKeepAlive?.track(it) },
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
        acquireSessionReference(conversationId)
    }

    fun removeConversationReference(conversationId: Uuid) {
        sessions[conversationId]?.release()
    }

    /**
     * Acquires a reference that is guaranteed to belong to the currently registered session.
     * Idle cleanup can run concurrently with a map lookup; retrying after a replacement keeps
     * callers from retaining a detached session whose state is only the empty constructor value.
     */
    private fun acquireSessionReference(conversationId: Uuid): ConversationSession {
        while (true) {
            val session = getOrCreateSession(conversationId)
            session.acquire()
            if (sessions[conversationId] === session) return session
            session.release()
        }
    }

    private fun launchWithConversationReference(
        conversationId: Uuid,
        block: suspend () -> Unit
    ): Job {
        // Acquire before launching. Acquiring inside the coroutine races the five-second
        // idle cleanup: a generation can finish, the session can be removed, and the new
        // coroutine would then observe a freshly-created empty session. This is especially
        // visible when a user leaves a conversation immediately after a reply completes.
        val session = acquireSessionReference(conversationId)
        return appScope.launch {
            try {
                block()
            } finally {
                session.release()
            }
        }
    }

    // ---- 对话状态访问 ----

    fun getConversationFlow(conversationId: Uuid): StateFlow<Conversation> {
        return getOrCreateSession(conversationId).state
    }

    fun observeConversationMemories(conversationId: Uuid) =
        conversationMemoryIndexService.observeRecentRecords(conversationId.toString())

    suspend fun deleteConversationMemory(conversationId: Uuid, id: String): Boolean =
        conversationMemoryIndexService.deleteRecord(conversationId.toString(), id)

    suspend fun memoryDataCounts() = memoryMaintenance.counts()

    suspend fun clearAllMemoryData(): me.rerere.rikkahub.data.memory.MemoryDataCounts {
        sessions.values.forEach { it.nextMemoryExtractionSchedule(); it.memoryExtractionStatus.value = MemoryExtractionStatus.Idle }
        return memoryMaintenance.clearAll()
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

    fun getMemoryExtractionStatusFlow(conversationId: Uuid): StateFlow<MemoryExtractionStatus> =
        getOrCreateSession(conversationId).memoryExtractionStatus

    fun getSuggestionGenerationStateFlow(conversationId: Uuid) =
        getOrCreateSession(conversationId).suggestionGeneration.state

    /**
     * Retry the most recent failed background extraction once, on explicit user request.
     * A new schedule version invalidates an older waiting task and keeps its checkpoint so
     * successful extractions remain idempotent.
     */
    fun retryMemoryExtraction(conversationId: Uuid) {
        val session = getOrCreateSession(conversationId)
        if (session.memoryExtractionStatus.value !is MemoryExtractionStatus.Failed) return
        val scheduleVersion = session
            .nextMemoryExtractionSchedule()
        launchWithConversationReference(conversationId) {
            runScheduledMemoryExtraction(
                conversationId = conversationId,
                scheduleVersion = scheduleVersion,
                forceRetry = true,
            )
        }
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
        // Backfill the approval timestamp for sessions created by older builds and mark
        // abandoned ask_user requests as expired when a conversation is opened. This keeps a
        // pending form from surviving forever without requiring another click from the user.
        withContext(Dispatchers.IO + NonCancellable) {
            session.withPersistenceLock {
                // Re-read under the lock. A generation/approval update may have published a
                // newer snapshot while the initial repository load was finishing; writing the
                // earlier normalized snapshot would otherwise lose that update.
                val latestConversation = session.state.value
                val normalized = latestConversation.normalizeAskUserPendingStates(
                    System.currentTimeMillis(), me.rerere.rikkahub.ui.components.message.askUserClock(context)
                )
                if (normalized != latestConversation) {
                    persistConversationLocked(
                        conversationId = conversationId,
                        conversation = normalized,
                        session = session,
                    )
                }
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
        regenerateAssistantMsg: Boolean = true,
        responseCount: Int = 1,
    ) {
        val session = getOrCreateSession(conversationId)
        session.getJob()?.cancel()

        val job = appScope.launch {
            try {
                repeat(responseCount.coerceIn(1, MAX_ALTERNATIVE_RESPONSES)) {
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
        cancelled: Boolean = false,
    ) {
        val session = getOrCreateSession(conversationId)
        session.getJob()?.cancel()

        val job = appScope.launch {
            try {
                applyToolApproval(
                    conversationId = conversationId,
                    toolCallId = toolCallId,
                    approved = approved,
                    reason = reason,
                    answer = answer,
                    cancelled = cancelled,
                    continueGeneration = true,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                addError(error, conversationId, title = context.getString(R.string.error_title_tool_approval))
            }
        }

        session.setJob(job)
    }

    /** Synchronous variant used by the HTTP API so invalid/stale requests get a real status. */
    suspend fun handleToolApprovalAndAwait(
        conversationId: Uuid,
        toolCallId: String,
        approved: Boolean,
        reason: String = "",
        answer: String? = null,
        cancelled: Boolean = false,
    ): ToolApprovalResult {
        getOrCreateSession(conversationId).getJob()?.cancel()
        return applyToolApproval(
            conversationId = conversationId,
            toolCallId = toolCallId,
            approved = approved,
            reason = reason,
            answer = answer,
            cancelled = cancelled,
            continueGeneration = false,
        )
    }

    private suspend fun applyToolApproval(
        conversationId: Uuid,
        toolCallId: String,
        approved: Boolean,
        reason: String,
        answer: String?,
        cancelled: Boolean,
        continueGeneration: Boolean,
    ): ToolApprovalResult {
        if (toolCallId.isBlank() || toolCallId.length > 256) {
            return ToolApprovalResult.INVALID_REQUEST
        }
        if (cancelled && (approved || answer != null)) {
            return ToolApprovalResult.INVALID_REQUEST
        }
        if (answer != null && !approved) {
            return ToolApprovalResult.INVALID_REQUEST
        }

        val decisionClock = me.rerere.rikkahub.ui.components.message.askUserClock(context)
        // A confirmation click is flushed immediately, but Room persistence is asynchronous.
        // Wait for earlier edits so an on-time answer keeps its acknowledgement at submission.
        synchronized(askUserDraftWrites) { askUserDraftWrites[conversationId] }?.join()
        initializeConversation(conversationId)
        val session = getOrCreateSession(conversationId)
        val targetMessageId = session.state.value.currentMessages.lastOrNull()?.id
        val target = session.state.value.currentMessages
            .lastOrNull()
            ?.getTools()
            ?.firstOrNull { it.toolCallId == toolCallId }
            ?: return ToolApprovalResult.NOT_FOUND

        if (target.approvalState !is ToolApprovalState.Pending) {
            if (target.approvalState is ToolApprovalState.Expired) {
                return ToolApprovalResult.EXPIRED
            }
            // A duplicate callback may have cancelled the continuation after the state was
            // persisted but before the tool executed. Treat that case as idempotent and restart
            // the continuation instead of leaving an approved call stranded forever.
            if (target.canResumeExecution) {
                if (continueGeneration) {
                    handleMessageComplete(conversationId)
                } else {
                    launchApprovalContinuation(conversationId)
                }
            }
            return ToolApprovalResult.ALREADY_HANDLED
        }

        val normalizedAnswer = if (answer != null) {
            if (target.toolName != AskUserProtocol.TOOL_NAME) {
                return ToolApprovalResult.INVALID_REQUEST
            }
            val request = AskUserProtocol.parseRequest(target.input).getOrElse {
                return ToolApprovalResult.INVALID_REQUEST
            }
            (if (AskUserInteraction.isCurrent(target.input, target.metadata)) {
                AskUserInteraction.submit(target.input, request, target.metadata, answer,
                    decisionClock)
            } else AskUserProtocol.validateAnswer(request, answer)).getOrElse {
                return ToolApprovalResult.INVALID_REQUEST
            }
        } else {
            null
        }

        if (target.toolName == AskUserProtocol.TOOL_NAME && approved && normalizedAnswer == null) {
            return ToolApprovalResult.INVALID_REQUEST
        }

        val pendingAt = target.toolName
            .takeIf { it == AskUserProtocol.TOOL_NAME }
            ?.let {
                target.metadata
                    ?.get(AskUserProtocol.PENDING_AT_METADATA_KEY)
                    ?.let { value -> (value as? JsonPrimitive)?.longOrNull }
            }
        val confirmationTimeoutSeconds = target.toolName
            .takeIf { it == AskUserProtocol.TOOL_NAME }
            ?.let { AskUserProtocol.parseRequest(target.input).getOrNull() }
            ?.confirmationTimeoutSeconds()
        val confirmationDeadline = target.metadata.takeUnless { AskUserInteraction.isCurrent(target.input, it) }
            ?.get(AskUserProtocol.CONFIRM_DEADLINE_METADATA_KEY)
            ?.let { (it as? JsonPrimitive)?.longOrNull }
            ?: confirmationTimeoutSeconds?.takeUnless { AskUserInteraction.isCurrent(target.input, target.metadata) }?.let {
                (pendingAt ?: System.currentTimeMillis()) + it * 1000L
            }
        val now = decisionClock.wall
        val confirmationExpired = confirmationDeadline != null && now >= confirmationDeadline
        val approvalExpired = pendingAt != null && now - pendingAt >= AskUserProtocol.APPROVAL_TIMEOUT_MILLIS
        if (confirmationExpired || approvalExpired) {
            var expiredApplied = false
            saveConversationUpdate(conversationId, session.state.value) { latestConversation ->
                latestConversation.copy(
                    messageNodes = latestConversation.messageNodes.map { node ->
                        node.copy(
                            messages = node.messages.map { message ->
                                message.copy(
                                    parts = message.parts.map { part ->
                                        if (part is UIMessagePart.Tool &&
                                            message.id == targetMessageId &&
                                            part.toolCallId == toolCallId &&
                                            part.input == target.input &&
                                            part.approvalState is ToolApprovalState.Pending
                                        ) {
                                            expiredApplied = true
                                            part.copy(
                                                approvalState = if (confirmationExpired) {
                                                    ToolApprovalState.Denied("Confirmation timed out and was rejected")
                                                } else {
                                                    ToolApprovalState.Expired(
                                                        "Tool approval expired after ${AskUserProtocol.APPROVAL_TIMEOUT_MILLIS / 60_000} minutes"
                                                    )
                                                },
                                                metadata = JsonObject(part.metadata.orEmpty() - AskUserInteraction.KEY),
                                            )
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
            if (expiredApplied) {
                if (continueGeneration) {
                    handleMessageComplete(conversationId)
                } else {
                    launchApprovalContinuation(conversationId)
                }
                _generationDoneFlow.emit(conversationId)
                return ToolApprovalResult.EXPIRED
            }
            return ToolApprovalResult.ALREADY_HANDLED
        }

        val newApprovalState = when {
            cancelled -> ToolApprovalState.Cancelled(reason.take(4096))
            normalizedAnswer != null -> ToolApprovalState.Answered(normalizedAnswer)
            approved -> ToolApprovalState.Approved
            else -> ToolApprovalState.Denied(reason.take(4096))
        }
        var decisionApplied = false
        var invalidDecision = false

        // Apply the decision to the latest snapshot under the persistence lock. This makes
        // duplicate/stale callbacks harmless when a cancelled generation races the UI request.
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
                                        msg.id == targetMessageId &&
                                        part.toolCallId == toolCallId &&
                                        part.input == target.input &&
                                        part.approvalState is ToolApprovalState.Pending
                                    ) {
                                        // Revalidate against the metadata under the same lock as
                                        // the decision. A concurrent draft must not restore stale consent.
                                        val latestAnswer = if (answer != null && AskUserInteraction.isCurrent(part.input, part.metadata)) {
                                            AskUserProtocol.parseRequest(part.input).mapCatching { request ->
                                                AskUserInteraction.submit(part.input, request, part.metadata, answer, decisionClock).getOrThrow()
                                            }.getOrNull()
                                        } else normalizedAnswer
                                        if (answer != null && latestAnswer == null) {
                                            invalidDecision = true
                                            part
                                        } else {
                                            decisionApplied = true
                                            part.copy(
                                                approvalState = latestAnswer?.let { ToolApprovalState.Answered(it) } ?: newApprovalState,
                                                metadata = JsonObject(part.metadata.orEmpty() - AskUserInteraction.KEY),
                                            )
                                        }
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

        if (invalidDecision) return ToolApprovalResult.INVALID_REQUEST
        if (!decisionApplied) return ToolApprovalResult.ALREADY_HANDLED

        val hasPendingTools = session.state.value.currentMessages.lastOrNull()
            ?.getTools()
            ?.any { it.isPending }
            ?: false
        if (!hasPendingTools) {
            if (continueGeneration) {
                handleMessageComplete(conversationId)
            } else {
                launchApprovalContinuation(conversationId)
            }
        }
        _generationDoneFlow.emit(conversationId)
        return ToolApprovalResult.APPLIED
    }

    /** Start the provider continuation outside the HTTP request coroutine. */
    private fun launchApprovalContinuation(conversationId: Uuid) {
        val session = getOrCreateSession(conversationId)
        val job = appScope.launch {
            runCatching {
                handleMessageComplete(conversationId)
            }.onFailure { error ->
                if (error is CancellationException) throw error
                addError(
                    error,
                    conversationId,
                    title = context.getString(R.string.error_title_generation),
                )
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
        val assistant = initialConversation.memoryAssistant(
            settings.getAssistantById(initialConversation.assistantId) ?: settings.getCurrentAssistant(),
            settings,
        )
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
            getOrCreateSession(conversationId).nextSuggestionGeneration()
            updateConversation(
                conversationId,
                initialConversation.copy(
                    chatSuggestions = emptyList(),
                    chatSuggestionItems = emptyList(),
                    suggestionSession = initialConversation.suggestionSession.copy(target = null, pinnedIds = emptySet(), previous = null, info = null),
                ),
            )

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
            var pendingLorebookEvaluation: me.rerere.rikkahub.data.model.PromptInjectionEvaluation? = null
            val excludedBooks = if (assistant.allowConversationPromptInjection) conversation.disabledLorebookIds else emptySet()
            generationHandler.generateText(
                settings = settings,
                model = model,
                processingStatus = session.processingStatus,
                conversationId = conversationId,
                messages = generationMessages,
                suggestionConversation = conversation,
                assistant = assistant.copy(lorebookIds = assistant.lorebookIds - excludedBooks),
                conversationSystemPrompt = conversation.customSystemPrompt,
                conversationModeInjectionIds = conversation.modeInjectionIds,
                conversationLorebookIds = conversation.lorebookIds - excludedBooks,
                temporaryModeInjections = conversation.temporaryModeInjections,
                lorebookRuntimeStates = conversation.lorebookRuntimeStates,
                conversationUserTurn = conversation.currentMessages.count { it.role == MessageRole.USER },
                onPromptInjectionEvaluation = { evaluation ->
                    session.promptInjectionDiagnostics.value = evaluation.diagnostics
                    pendingLorebookEvaluation = evaluation
                },
                workspaceCwd = conversation.workspaceCwd,
                workspaceFileOperationMode = conversation.workspaceFileOperationMode,
                rollingContextSummary = rollingSummary?.content,
                requestMessageStartIndex = requestMessageStartIndex,
                contextScopeKey = me.rerere.rikkahub.data.ai.context.requestContextScopeKey(preparedConversation, settings, model),
                resumeInterruptedResponse = resumeInterruptedResponse,
                memories = when {
                    !assistant.enableMemory -> emptyList()
                    else -> memoryRepository.getMemoriesForConversation(
                        if (assistant.useGlobalMemory) MemoryRepository.GLOBAL_MEMORY_ID else assistant.id.toString(), conversationId.toString())
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
                            lorebookRuntimeStates = if (cause == null) pendingLorebookEvaluation?.runtimeStates ?: latestConversation.lorebookRuntimeStates else latestConversation.lorebookRuntimeStates,
                            temporaryModeInjections = if (cause == null && pendingLorebookEvaluation != null) latestConversation.temporaryModeInjections.filterValues { it > pendingLorebookEvaluation!!.diagnostics.userTurn } else latestConversation.temporaryModeInjections,
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

            // Extraction is detached from the main request and coalesces ordinary turns. The
            // persisted checkpoint makes regeneration and process restarts idempotent.
            if (assistant.enableMemory) {
                val scheduleVersion = getOrCreateSession(conversationId)
                    .nextMemoryExtractionSchedule()
                launchWithConversationReference(conversationId) {
                    runScheduledMemoryExtraction(conversationId, scheduleVersion)
                }
            }

            if (assistant.enableMemoryRag) {
                launchWithConversationReference(conversationId) {
                    conversationMemoryIndexService.synchronize(
                        settings = settings,
                        conversation = finalConversation,
                        isAllowed = {
                            val latestSettings = settingsStore.settingsFlow.value
                            val latestConversation = getConversationFlow(conversationId).value
                            latestSettings.getAssistantById(latestConversation.assistantId)?.let {
                                latestConversation.memoryAssistant(it, latestSettings).enableMemoryRag
                            } == true
                        },
                    )
                }
            }

            launchWithConversationReference(conversationId) {
                generateTitle(conversationId, finalConversation)
            }
            launchWithConversationReference(conversationId) {
                generateSuggestion(conversationId, finalConversation)
            }
        }
    }

    private suspend fun runScheduledMemoryExtraction(
        conversationId: Uuid,
        scheduleVersion: Long,
        forceRetry: Boolean = false,
    ) {
        var idleWindowElapsed = false
        while (true) {
            val extractionSession = getOrCreateSession(conversationId)
            if (!extractionSession.isCurrentMemoryExtractionSchedule(scheduleVersion)) return
            val currentSettings = settingsStore.settingsFlow.first()
            // The generation completion is persisted before this task is launched, and later
            // turns may be persisted while the idle window is running. Prefer that complete
            // snapshot over a possibly stale in-memory value; keep the session as a fallback
            // for newly-created conversations that have not been inserted yet.
            val persistedConversation = withContext(Dispatchers.IO) {
                conversationRepo.getConversationById(conversationId)
            }
            val currentConversation = persistedConversation ?: extractionSession.state.value
            val selectedMessageCount = runCatching {
                currentConversation.currentMessages.size
            }.getOrDefault(-1)
            Log.d(
                TAG,
                "memorySchedule conversation=$conversationId version=$scheduleVersion " +
                    "source=${if (persistedConversation != null) "database" else "session"} " +
                    "messages=${currentConversation.messageNodes.size} " +
                    "selected=$selectedMessageCount assistant=${currentConversation.assistantId}",
            )
            val currentAssistant = currentConversation.memoryAssistant(
                currentSettings.getAssistantById(currentConversation.assistantId) ?: return,
                currentSettings,
            )
            if (!currentAssistant.enableMemory) {
                if (extractionSession.isCurrentMemoryExtractionSchedule(scheduleVersion)) {
                    extractionSession.memoryExtractionStatus.value = MemoryExtractionStatus.Idle
                }
                return
            }

            val result = try {
                memoryExtractionService.processPending(
                    settings = currentSettings,
                    assistant = currentAssistant,
                    conversationId = conversationId.toString(),
                    messages = currentConversation.currentMessages,
                    idleWindowElapsed = idleWindowElapsed,
                    forceRetry = forceRetry,
                    isAllowed = {
                        val latestSettings = settingsStore.settingsFlow.value
                        val latestConversation = extractionSession.state.value
                        extractionSession.isCurrentMemoryExtractionSchedule(scheduleVersion) &&
                            latestSettings.getAssistantById(latestConversation.assistantId)?.let {
                                val latestAssistant = latestConversation.memoryAssistant(it, latestSettings)
                                latestAssistant.enableMemory && latestAssistant.id == currentAssistant.id &&
                                    latestAssistant.enableMemoryRag == currentAssistant.enableMemoryRag &&
                                    latestAssistant.enableEpisodicMemory == currentAssistant.enableEpisodicMemory &&
                                    latestAssistant.useGlobalMemory == currentAssistant.useGlobalMemory &&
                                    latestSettings.resolveMemoryExtractionModel()?.id == currentSettings.resolveMemoryExtractionModel()?.id
                            } == true
                    },
                    onStarted = {
                        if (extractionSession.isCurrentMemoryExtractionSchedule(scheduleVersion)) {
                            extractionSession.memoryExtractionStatus.value = MemoryExtractionStatus.Running()
                        }
                    },
                )
            } catch (error: CancellationException) {
                if (extractionSession.isCurrentMemoryExtractionSchedule(scheduleVersion)) {
                    extractionSession.memoryExtractionStatus.value = MemoryExtractionStatus.Idle
                }
                throw error
            } catch (error: Throwable) {
                Log.w(TAG, "Background memory extraction failed", error)
                if (extractionSession.isCurrentMemoryExtractionSchedule(scheduleVersion)) {
                    extractionSession.memoryExtractionStatus.value = MemoryExtractionStatus.Failed(
                        message = error.message?.take(240) ?: error.javaClass.simpleName,
                    )
                }
                return
            }

            when (result) {
                MemoryExtractionProcessResult.Skipped -> {
                    if (extractionSession.isCurrentMemoryExtractionSchedule(scheduleVersion)) {
                        extractionSession.memoryExtractionStatus.value = MemoryExtractionStatus.Idle
                    }
                    return
                }
                is MemoryExtractionProcessResult.Waiting -> {
                    if (extractionSession.isCurrentMemoryExtractionSchedule(scheduleVersion)) {
                        extractionSession.memoryExtractionStatus.value = MemoryExtractionStatus.Queued(
                            pendingUserTurns = result.pendingUserTurns,
                        )
                    }
                    // A failed attempt waits for a future completed reply rather than retrying
                    // forever in the background. Ordinary small batches are processed after idle.
                    if (result.reason == me.rerere.rikkahub.data.memory.MemoryExtractionWaitReason.FAILURE_COOLDOWN) {
                        return
                    }
                    // Recheck the schedule periodically so superseded conversations do not
                    // leave a five-minute throttling coroutine alive until the full delay ends.
                    delay(result.delayMillis.coerceAtMost(MEMORY_EXTRACTION_IDLE_DELAY_MS))
                    idleWindowElapsed = true
                }
                is MemoryExtractionProcessResult.Processed -> {
                    if (!extractionSession.isCurrentMemoryExtractionSchedule(scheduleVersion)) return
                    extractionSession.memoryExtractionStatus.value = when (val outcome = result.outcome) {
                        is MemoryExtractionOutcome.Saved -> MemoryExtractionStatus.Completed(
                            savedCount = outcome.memories.size + outcome.updatedCount,
                            candidateCount = outcome.candidateCount,
                        )
                        is MemoryExtractionOutcome.NoChanges -> MemoryExtractionStatus.NoChanges(
                            reason = outcome.reason,
                            candidateCount = outcome.candidateCount,
                        )
                    }
                    return
                }
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
            cancelPendingApprovals = true,
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
        val session = getOrCreateSession(conversationId)
        val generationVersion = session.nextTitleGeneration()
        val shouldGenerate = when {
            force -> true
            conversation.title.isBlank() -> true
            else -> false
        }
        if (!shouldGenerate) return@withContext

        runCatching {
            val settings = settingsStore.settingsFlow.first()
            if (!force && !settings.enableTitleGeneration) return@runCatching
            val enabledProviders = settings.providers.filter { it.enabled }
            val model = settings.resolveBackgroundChatModel(settings.titleModelId)
                ?: error(context.getString(R.string.error_background_chat_model_unavailable))
            val assistant = settings.getAssistantById(conversation.assistantId)
                ?: settings.getCurrentAssistant()
            val provider = model.findProvider(enabledProviders)
                ?: error(context.getString(R.string.error_background_chat_provider_unavailable))
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
                            "max_title_length" to settings.titleMaxLength.toString(),
                            "content" to conversation.currentMessages
                                .takeLast(4).joinToString("\n\n") { it.summaryAsText(maxLength = 500) },
                        ) + "\n\nReturn exactly one plain-text title no longer than " +
                            "${settings.titleMaxLength} characters. Do not use a label, quote, or Markdown."
                    ),
                ),
                params = backgroundTextGenerationParams(model),
            )
            val title = normalizeGeneratedTitle(
                raw = result.message.toText(),
                maxLength = settings.titleMaxLength,
            )
                ?: throw IllegalStateException("Title generation returned an empty title")
            updateGeneratedConversationTitle(
                conversationId = conversationId,
                loadedConversation = conversation,
                title = title,
                expectedTitle = conversation.title,
                generationVersion = generationVersion,
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
        manual: Boolean = false,
        replaceId: String? = null,
    ) = withContext(Dispatchers.IO) {
        require(conversation.id == conversationId)
        val session = acquireSessionReference(conversationId)
        var generationVersion: Long? = null
        var sourceContextKey: String? = null
        var failed = false
        var requestModel: Model? = null
        val requestStartedNanos = System.nanoTime()
        try {
            initializeConversation(conversationId)
            val sourceConversation = session.state.value.suggestionContext()
            val source = sourceConversation.suggestionSourceMessage() ?: return@withContext
            val sourceKey = sourceConversation.suggestionContextKey()
            sourceContextKey = sourceKey
            if (sourceKey != conversation.suggestionContextKey() || (session.isGenerating && source.finishedAt == null)) return@withContext
            val settings = settingsStore.settingsFlow.first()
            val config = sourceConversation.suggestionConfig(settings)
            if (config.options.trigger == SuggestionTrigger.DISABLED || sourceConversation.suggestionSession.paused) return@withContext
            if (!manual && !sourceConversation.canAutomaticallySuggest(config, System.currentTimeMillis())) return@withContext
            val token = session.suggestionGeneration.begin(currentCoroutineContext()[Job]!!) ?: return@withContext
            generationVersion = token
            val startedNanos = System.nanoTime()
            updateAndSaveConversationState(conversationId) { current -> current.copy(
                suggestionSession = current.suggestionSession.copy(lastAttemptAt = System.currentTimeMillis())) }
            val enabledProviders = settings.providers.filter { it.enabled }
            val model = settings.resolveBackgroundChatModel(config.modelId)
                ?: error(context.getString(R.string.error_background_chat_model_unavailable))
            requestModel = model
            val assistant = settings.getAssistantById(sourceConversation.assistantId) ?: return@withContext
            val allowedActions = sourceConversation.availableSuggestionActions(settings).toMutableSet()
            if (knowledgeBaseRepository.getEnabledBaseIds(assistant.knowledgeBaseIds.mapTo(hashSetOf()) { it.toString() }).isEmpty()) {
                allowedActions.remove(ChatSuggestionAction.SEARCH_KNOWLEDGE)
            }
            val provider = model.findProvider(enabledProviders)
                ?: error(context.getString(R.string.error_background_chat_provider_unavailable))
            val promptVariables = resolvePromptVariables(
                settings = settings,
                model = model,
                assistant = assistant,
                workspaceCwd = sourceConversation.workspaceCwd,
            )

            val providerHandler = providerManager.getProviderByType(provider)
            val previousItems = sourceConversation.currentChatSuggestions()
            val retained = previousItems.filter { if (replaceId != null) it.id != replaceId else it.id in sourceConversation.suggestionSession.pinnedIds }
            val requestedCount = (config.count - retained.size).coerceAtLeast(0)
            if (requestedCount == 0) return@withContext
            val contextSources = mutableListOf("对话")
            val effectiveAssistant = sourceConversation.memoryAssistant(assistant, settings)
            val supplemental = buildString {
                if (config.options.goal.isNotBlank()) { appendLine("[Current goal]\n${config.options.goal}"); contextSources.add("当前目标") }
                if (config.style == ChatSuggestionStyle.ROLEPLAY) {
                    appendLine("[Character profile]\nName: ${assistant.name}; User: ${settings.displaySetting.userNickname}\n" +
                        limitSuggestionContext(assistant.systemPrompt, 400))
                }
                if (config.options.includeSummary && sourceConversation.suggestionSession.target == null) {
                    sourceConversation.rollingContextSummary?.content?.takeIf(String::isNotBlank)?.let { appendLine("[Earlier summary]\n$it"); contextSources.add("滚动摘要") }
                }
            }
            val memoryText = if (config.options.includeMemory && sourceConversation.suggestionSession.target == null) {
                if (effectiveAssistant.enableMemory) {
                    memoryRepository.getMemoriesForConversation(
                        if (effectiveAssistant.useGlobalMemory) MemoryRepository.GLOBAL_MEMORY_ID else assistant.id.toString(), conversationId.toString())
                        .filter { it.lifecycleState == MemoryLifecycleState.ACTIVE }.takeLast(8).joinToString("\n") { it.content }
                } else if (effectiveAssistant.enableMemoryRag) {
                    conversationMemoryIndexService.observeRecentRecords(conversationId.toString()).first().take(4).joinToString("\n") { it.content }
                } else ""
            } else ""
            if (memoryText.isNotBlank()) contextSources.add("有效记忆")
            val selectedText = sourceConversation.suggestionSession.target?.selectedText.orEmpty()
            if (selectedText.isNotBlank()) contextSources.add("选中文本")
            val feedback = assistant.suggestionFeedback.takeLast(30)
            val instruction = suggestionGenerationInstruction(config, allowedActions, requestedCount, previousItems, feedback)
            fun requestPrompt(content: String) = config.prompt.applyPlaceholders(
                "suggestion_count" to requestedCount.toString(),
                "suggestion_max_length" to config.maxLength.toString(),
                "suggestion_style" to config.style.name.lowercase(Locale.ROOT),
                "content" to content,
                *promptVariables,
            ) + "\n\n" + config.style.instruction() + "\n" + instruction
            val modelWindow = model.contextWindowTokens ?: inferContextWindowTokens(model.modelId)
            val contextBudget = suggestionContextBudget(config.options.contextBudget, modelWindow, requestPrompt(""))
            val contextText = boundedSuggestionContext(
                buildSuggestionContext(sourceConversation.currentMessages),
                supplemental + if (memoryText.isBlank()) "" else "\n[Relevant memory]\n$memoryText",
                selectedText, contextBudget,
            )
            contextSources.clear()
            contextSources.add("对话")
            if (contextText.contains("[Selected passage]")) contextSources.add("选中文本")
            if (contextText.contains("[Current goal]")) contextSources.add("当前目标")
            if (contextText.contains("[Earlier summary]")) contextSources.add("滚动摘要")
            if (contextText.contains("[Relevant memory]")) contextSources.add("有效记忆")
            if (contextText.contains("[Character profile]")) contextSources.add("角色资料")
            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(
                    UIMessage.user(requestPrompt(contextText))
                ),
                params = backgroundTextGenerationParams(model),
            )
            currentCoroutineContext().ensureActive()
            val latestSettings = settingsStore.settingsFlow.value
            if (session.state.value.suggestionConfig(latestSettings) != config || session.state.value.suggestionSession.paused ||
                latestSettings.resolveBackgroundChatModel(config.modelId) != model) return@withContext
            val suggestions = normalizeGeneratedSuggestionItems(
                raw = result.message.toText(),
                maxCount = 10,
                maxLength = config.maxLength,
            ).filter { (it.action != ChatSuggestionAction.IMAGE_DRAFT || it.action in allowedActions) &&
                (config.options.parameterForms || it.parameterForm == null) }.map { suggestion ->
                suggestion.copy(
                    action = suggestion.action.takeIf { it in allowedActions }
                        ?: ChatSuggestionAction.INSERT_TEXT,
                    sourceMessageId = source.id,
                    sourceContextKey = sourceKey,
                )
            }
            val batch = selectSuggestionBatch(suggestions, retained, config.count, config.options.balancedCategories, feedback)
            if (batch.isEmpty() && !isExplicitEmptySuggestionResponse(result.message.toText())) error("Suggestion generation returned no usable suggestions")
            val completedAt = System.currentTimeMillis()
            val elapsedMillis = (System.nanoTime() - startedNanos) / 1_000_000
            val logId = Logging.recordEvent("CHAT_SUGGESTIONS", "conversation=$conversationId model=${model.modelId} durationMs=$elapsedMillis count=${batch.size} usage=${result.usage}")
            val info = SuggestionRunInfo(model.displayName.ifBlank { model.modelId }, model.modelId, completedAt, elapsedMillis,
                result.usage ?: result.message.usage, contextSources, logId.toString())

            saveGeneratedSuggestions(
                conversationId = conversationId,
                loadedConversation = sourceConversation,
                suggestions = batch,
                generationVersion = token,
                info = info,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (generationVersion == null || !session.isCurrentSuggestionGeneration(generationVersion) ||
                session.state.value.suggestionContextKey() != sourceContextKey || session.state.value.suggestionConfig(settingsStore.settingsFlow.value).options.trigger == SuggestionTrigger.DISABLED) return@withContext
            failed = true
            val elapsed = (System.nanoTime() - requestStartedNanos) / 1_000_000
            val logId = Logging.recordEvent("CHAT_SUGGESTIONS", "conversation=$conversationId model=${requestModel?.modelId} durationMs=$elapsed failure=${error.javaClass.simpleName}")
            updateAndSaveConversationState(conversationId) { current ->
                if (!session.isCurrentSuggestionGeneration(generationVersion) || current.suggestionContextKey() != sourceContextKey) current else current.copy(
                    suggestionSession = current.suggestionSession.copy(info = SuggestionRunInfo(
                        requestModel?.displayName?.ifBlank { requestModel?.modelId.orEmpty() } ?: "模型不可用",
                        requestModel?.modelId.orEmpty(), System.currentTimeMillis(), elapsed, logId = logId.toString(),
                        error = "生成失败（${error.javaClass.simpleName}），请检查错误与诊断日志。")))
            }
            addError(
                error = error,
                conversationId = conversationId,
                title = context.getString(R.string.error_title_generate_suggestions),
                solution = ChatErrorSolution.CheckTitleModelSettings,
            )
        } finally {
            generationVersion?.let { session.suggestionGeneration.finish(it, failed) }
            session.release()
        }
    }

    suspend fun dismissSuggestions(
        conversationId: Uuid,
        loadedConversation: Conversation,
    ) {
        getOrCreateSession(conversationId).nextSuggestionGeneration()
        saveConversationUpdate(conversationId, loadedConversation) {
            it.copy(
                chatSuggestions = emptyList(),
                chatSuggestionItems = emptyList(),
                suggestionSession = it.suggestionSession.copy(pinnedIds = emptySet(), previous = null, info = null),
            )
        }
    }

    suspend fun updateSuggestionSession(conversationId: Uuid, update: (SuggestionSession) -> SuggestionSession) {
        updateAndSaveConversationState(conversationId) { current ->
            val changed = update(current.suggestionSession)
            if (changed.settings != current.suggestionSession.settings || changed.paused != current.suggestionSession.paused) {
                getOrCreateSession(conversationId).nextSuggestionGeneration()
            }
            current.copy(suggestionSession = changed)
        }
    }

    suspend fun setSuggestionTarget(conversationId: Uuid, target: SuggestionTarget?) {
        getOrCreateSession(conversationId).nextSuggestionGeneration()
        updateAndSaveConversationState(conversationId) { current ->
            require(target == null || current.currentMessages.any { it.id == target.messageId }) { "Message is no longer selected" }
            require(target == null || target.selectedText.isBlank() || current.currentMessages.first { it.id == target.messageId }.toText().contains(target.selectedText))
            current.copy(chatSuggestions = emptyList(), chatSuggestionItems = emptyList(), suggestionSession = current.suggestionSession.copy(
                target = target?.copy(selectedText = target.selectedText.takeCodePoints(8000)), collapsed = false, paused = false,
                pinnedIds = emptySet(), previous = null, info = null))
        }
    }

    fun stopSuggestionGeneration(conversationId: Uuid) { getOrCreateSession(conversationId).nextSuggestionGeneration() }

    suspend fun toggleSuggestionPin(conversationId: Uuid, id: String) = updateAndSaveConversationState(conversationId) { current ->
        if (current.currentChatSuggestions().none { it.id == id }) current else {
            getOrCreateSession(conversationId).nextSuggestionGeneration()
            val pins = current.suggestionSession.pinnedIds
            current.copy(suggestionSession = current.suggestionSession.copy(pinnedIds = if (id in pins) pins - id else pins + id))
        }
    }

    suspend fun undoSuggestionRefresh(conversationId: Uuid) {
        getOrCreateSession(conversationId).nextSuggestionGeneration()
        updateAndSaveConversationState(conversationId) { current ->
            val previous = current.suggestionSession.previous
            if (previous == null) current else {
                val restored = current.copy(chatSuggestions = previous.items.map { it.text }, chatSuggestionItems = previous.items,
                    suggestionSession = current.suggestionSession.copy(target = previous.target, info = previous.info, previous = null, pinnedIds = emptySet()))
                if (restored.currentChatSuggestions().size != previous.items.size) current else {
                    val feedback = settingsStore.settingsFlow.value.getAssistantById(current.assistantId)?.suggestionFeedback.orEmpty()
                    val items = previous.items.filterNot { item -> feedback.any {
                        suggestionTextKey(it.text) == suggestionTextKey(item.payload.ifBlank { item.text })
                    } }
                    restored.copy(chatSuggestions = items.map { it.text }, chatSuggestionItems = items)
                }
            }
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
        if (session.suggestionGeneration.state.value != SuggestionGenerationState.IDLE &&
            (conversation.suggestionSourceMessage() == null || conversation.suggestionContextKey() != session.state.value.suggestionContextKey())) {
            session.nextSuggestionGeneration()
        }
        checkFilesDelete(conversation, session.state.value)
        session.state.value = conversation
    }

    fun updateConversationState(conversationId: Uuid, update: (Conversation) -> Conversation) {
        val current = getConversationFlow(conversationId).value
        updateConversation(conversationId, update(current))
    }

    fun saveAskUserDraft(conversationId: Uuid, toolCallId: String, input: String, answers: JsonObject, displayed: Set<String>) {
        if (input.length > AskUserProtocol.MAX_ANSWER_LENGTH * 2 || answers.toString().length > AskUserProtocol.MAX_ANSWER_LENGTH) return
        val editClock = me.rerere.rikkahub.ui.components.message.askUserClock(context)
        val revision = System.nanoTime()
        synchronized(askUserDraftWrites) {
        val previous = askUserDraftWrites[conversationId]
        val write = launchWithConversationReference(conversationId) {
            previous?.join()
            try {
                updateAndSaveConversationState(conversationId) { conversation ->
                    val lastId = conversation.currentMessages.lastOrNull()?.id
                    conversation.copy(messageNodes = conversation.messageNodes.map { node -> node.copy(messages = node.messages.map { message ->
                        if (message.id != lastId) message else message.copy(parts = message.parts.map { part ->
                            if (part !is UIMessagePart.Tool || part.toolName != AskUserProtocol.TOOL_NAME || part.toolCallId != toolCallId ||
                                part.input != input || part.approvalState !is ToolApprovalState.Pending) part else {
                                val request = AskUserProtocol.parseRequest(input).getOrNull()
                                if (request == null) part else part.copy(metadata = AskUserInteraction.update(input, request, part.metadata,
                                    answers, displayed, editClock, revision))
                            }
                        })
                    }) })
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { me.rerere.common.android.Logging.logSoftwareError("AskUserDraft", "Save question draft", error) }
        }
        askUserDraftWrites[conversationId] = write
        write.invokeOnCompletion {
            synchronized(askUserDraftWrites) {
                if (askUserDraftWrites[conversationId] === write) askUserDraftWrites.remove(conversationId)
            }
        }
        }
    }

    /**
     * Applies a conversation update and persists the same latest snapshot while
     * holding the session persistence lock. This prevents a stale UI snapshot
     * from overwriting an update that was just made in memory.
     */
    suspend fun updateAndSaveConversationState(
        conversationId: Uuid,
        update: (Conversation) -> Conversation,
    ) {
        val session = getOrCreateSession(conversationId)
        session.withRefSuspend {
            session.initializeOnce {
                conversationRepo.getConversationById(conversationId)
                    ?: session.state.value
            }
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

    suspend fun setConversationMemoryMode(
        conversationId: Uuid,
        mode: me.rerere.rikkahub.data.model.ConversationMemoryMode,
    ) {
        val session = getOrCreateSession(conversationId)
        if (!session.isInitialized || session.state.value.sourceConversationId != null) return
        session.nextMemoryExtractionSchedule()
        session.memoryExtractionStatus.value = MemoryExtractionStatus.Idle
        updateConversationState(conversationId) { it.copy(memoryMode = mode) }
        saveConversation(conversationId, session.state.value)
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
    ) {
        getOrCreateSession(conversationId).nextTitleGeneration()
        updateConversationMetadata(
            conversationId = conversationId,
            loadedConversation = loadedConversation,
            update = { it.copy(title = title) },
            persist = { conversation ->
                conversationRepo.updateConversationTitle(conversationId, conversation.title)
            },
        )
    }

    private suspend fun updateGeneratedConversationTitle(
        conversationId: Uuid,
        loadedConversation: Conversation,
        title: String,
        expectedTitle: String,
        generationVersion: Long,
    ) {
        val session = getOrCreateSession(conversationId)
        session.withRefSuspend {
            session.initializeOnce { loadedConversation }
            withContext(Dispatchers.IO + NonCancellable) {
                session.withPersistenceLock {
                    val latest = session.state.value
                    if (!session.isCurrentTitleGeneration(generationVersion) ||
                        latest.title != expectedTitle
                    ) {
                        return@withPersistenceLock
                    }
                    val updated = latest.copy(title = title)
                    session.state.value = updated
                    conversationRepo.updateConversationTitle(conversationId, title)
                }
            }
        }
    }

    private suspend fun saveGeneratedSuggestions(
        conversationId: Uuid,
        loadedConversation: Conversation,
        suggestions: List<ChatSuggestionItem>,
        generationVersion: Long,
        info: SuggestionRunInfo,
    ) {
        val session = getOrCreateSession(conversationId)
        session.withRefSuspend {
            session.initializeOnce { loadedConversation }
            withContext(Dispatchers.IO + NonCancellable) {
                session.withPersistenceLock {
                    if (!session.isCurrentSuggestionGeneration(generationVersion)) {
                        return@withPersistenceLock
                    }
                    val latest = session.state.value
                    if (latest.suggestionConfig(settingsStore.settingsFlow.value).options.trigger == SuggestionTrigger.DISABLED || latest.suggestionSession.paused || latest.suggestionSourceMessage() == null ||
                        latest.suggestionContextKey() != loadedConversation.suggestionContextKey()) return@withPersistenceLock
                    persistConversationLocked(
                        conversationId = conversationId,
                        conversation = session.state.value.copy(
                            chatSuggestions = suggestions.map(ChatSuggestionItem::text),
                            chatSuggestionItems = suggestions,
                            suggestionSession = latest.suggestionSession.copy(
                                previous = SuggestionBatch(latest.currentChatSuggestions(), latest.suggestionSession.target, latest.suggestionSession.info),
                                pinnedIds = latest.suggestionSession.pinnedIds.intersect(suggestions.mapTo(hashSetOf()) { it.id }), info = info),
                        ),
                        session = session,
                    )
                }
            }
        }
    }

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
