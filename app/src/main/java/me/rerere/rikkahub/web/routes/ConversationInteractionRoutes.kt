package me.rerere.rikkahub.web.routes

import me.rerere.ai.provider.parameterModelId
import io.ktor.server.routing.*
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import me.rerere.ai.ui.AskUserProtocol
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.provider.inferContextWindowTokens
import me.rerere.rikkahub.data.datastore.*
import me.rerere.rikkahub.data.ai.context.*
import me.rerere.rikkahub.data.ai.transformers.DocumentAsPromptTransformer
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.ui.components.ai.calculateChatContextUsage
import me.rerere.rikkahub.web.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

@Serializable
private data class AskDraft(val toolCallId: String, val input: String, val answers: JsonObject, val displayed: Set<String>)
@Serializable
private data class CompressRequest(val targetTokens: Int = 2048, val additionalPrompt: String = "")

private val compressionLocks = ConcurrentHashMap<Uuid, Mutex>()

fun Route.conversationInteractionRoutes(chat: ChatService, store: SettingsStore) {
    post("/{id}/ask-user/draft") {
        val id = call.parameters["id"].toUuid("conversation id")
        val request = call.receive<AskDraft>()
        if (request.input.length > AskUserProtocol.MAX_ANSWER_LENGTH * 2 || request.answers.toString().length > AskUserProtocol.MAX_ANSWER_LENGTH)
            throw BadRequestException("问答草稿过大")
        chat.initializeConversation(id, store, updateSelection = false)
        val tool = chat.getConversationFlow(id).value.currentMessages.lastOrNull()?.parts
            ?.filterIsInstance<UIMessagePart.Tool>()?.find { it.toolCallId == request.toolCallId }
            ?: throw NotFoundException("问答不存在")
        if (tool.toolName != AskUserProtocol.TOOL_NAME || tool.input != request.input || tool.approvalState !is ToolApprovalState.Pending)
            throw ConflictException("问答已变化，请刷新")
        chat.saveAskUserDraft(id, request.toolCallId, request.input, request.answers, request.displayed)?.join()
        val latest = chat.getConversationFlow(id).value.currentMessages.lastOrNull()?.parts
            ?.filterIsInstance<UIMessagePart.Tool>()?.find { it.toolCallId == request.toolCallId }
        call.respond(buildJsonObject { put("metadata", latest?.metadata ?: JsonNull); put("serverTime", System.currentTimeMillis()) })
    }
    get("/{id}/context") {
        val id = call.parameters["id"].toUuid("conversation id")
        chat.initializeConversation(id, store, updateSelection = false)
        val conversation = chat.getConversationFlow(id).value
        val settings = store.settingsFlow.value
        val assistant = settings.getAssistantById(conversation.assistantId) ?: settings.getCurrentAssistant()
        val model = settings.findModelById(assistant.chatModelId ?: settings.chatModelId)
        val raw = conversation.currentMessages
        val usage = calculateChatContextUsage(
            messages = DocumentAsPromptTransformer.transformDocumentContents(raw), rawMessages = raw,
            rollingContextSummary = conversation.rollingContextSummary?.takeIf { assistant.enableRollingContextCompression || it.manuallyRequested },
            capacityTokens = model?.let { it.contextWindowTokens ?: inferContextWindowTokens(it.parameterModelId()) },
            scopeKey = model?.let { requestContextScopeKey(conversation, settings, it) }, modelId = model?.id?.toString(),
            includeReasoning = countHistoryReasoning(model?.findProvider(settings.providers)),
            systemPrompt = if (assistant.allowConversationSystemPrompt && !conversation.customSystemPrompt.isNullOrBlank()) conversation.customSystemPrompt.orEmpty() else assistant.systemPrompt,
        )
        call.respond(buildJsonObject {
            put("usedTokens", usage.usedTokens); put("capacityTokens", usage.capacityTokens?.let(::JsonPrimitive) ?: JsonNull)
            put("remainingTokens", usage.remainingTokens?.let(::JsonPrimitive) ?: JsonNull)
            put("percentage", usage.percentage?.let(::JsonPrimitive) ?: JsonNull)
            put("measuredInputTokens", usage.measuredInputTokens?.let(::JsonPrimitive) ?: JsonNull)
            put("calibrated", usage.calibrated); put("basedOnRequest", usage.basedOnRequest); put("isEstimated", usage.isEstimated)
            put("breakdown", buildJsonObject { usage.breakdown.forEach { (key, value) -> put(key.name, value) } })
            put("summary", conversation.rollingContextSummary?.content.orEmpty())
            put("processingStatus", chat.getProcessingStatusFlow(id).value.orEmpty())
            put("compressing", compressionLocks[id]?.isLocked == true)
        })
    }
    post("/{id}/compress") {
        val id = call.parameters["id"].toUuid("conversation id")
        val request = call.receive<CompressRequest>()
        if (request.targetTokens !in 256..32000 || request.additionalPrompt.length > 8000) throw BadRequestException("压缩参数超出范围")
        val lock = compressionLocks.getOrPut(id) { Mutex() }
        if (!lock.tryLock()) throw ConflictException("正在压缩，请稍候")
        try {
            chat.initializeConversation(id, store, updateSelection = false)
            if (chat.getConversationJobs().first()[id] != null) throw ConflictException("请等待生成结束后压缩")
            chat.refreshRollingContext(id, chat.getConversationFlow(id).value, request.additionalPrompt, request.targetTokens, store).getOrThrow()
            call.respond(mapOf("status" to "compressed"))
        } finally { lock.unlock() }
    }
}
