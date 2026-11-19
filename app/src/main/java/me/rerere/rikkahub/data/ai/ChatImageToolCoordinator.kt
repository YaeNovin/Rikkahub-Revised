package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.*
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.db.dao.ChatImageToolTaskDAO
import me.rerere.rikkahub.data.db.entity.ChatImageToolTaskEntity
import me.rerere.rikkahub.data.model.ImageGenerationRequestState
import me.rerere.rikkahub.data.model.constrained
import me.rerere.rikkahub.data.model.resolveChatImageModel
import me.rerere.rikkahub.data.model.toParams
import me.rerere.rikkahub.utils.JsonInstant
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

class ChatImageToolCoordinator(
    private val dao: ChatImageToolTaskDAO,
    private val imageService: ChatImageGenerationService,
    private val providers: ProviderManager,
    private val conversations: me.rerere.rikkahub.data.repository.ConversationRepository,
    private val settingsStore: me.rerere.rikkahub.data.datastore.SettingsStore,
) {
    private val runner = ChatImageToolRunner(dao)
    private val initialization = Mutex()
    private var recovered = false
    private val defaults = ConcurrentHashMap<Uuid, ImageGenerationRequestState>()
    private val running = ConcurrentHashMap.newKeySet<Uuid>()
    private val executionOwners = ConcurrentHashMap.newKeySet<Uuid>()

    private suspend fun initialize() = initialization.withLock {
        if (!recovered) { dao.recoverInterrupted(System.currentTimeMillis()); recovered = true }
    }

    fun rememberDefaults(conversationId: Uuid, options: ImageGenerationRequestState) { defaults[conversationId] = options }
    fun isRunning(conversationId: Uuid) = conversationId in running
    fun hasExecutionHistory(conversationId: Uuid) = conversationId in executionOwners
    fun observe(id: String) = flow { initialize(); emitAll(dao.observe(id)) }
    suspend fun get(id: String): ChatImageToolTaskEntity? { initialize(); return dao.get(id) }
    suspend fun expirePending(conversationId: Uuid) { initialize(); dao.expirePending(conversationId.toString(), System.currentTimeMillis()) }

    suspend fun prepare(
        conversationId: Uuid,
        messageId: Uuid,
        sourceMessageId: Uuid,
        originalPrompt: String,
        tool: UIMessagePart.Tool,
    ): ChatImageToolTaskEntity {
        initialize()
        val settings = settingsStore.settingsFlowRaw.first()
        val id = imageToolTaskId(conversationId.toString(), messageId.toString(), tool.toolCallId)
        dao.get(id)?.let { validateImageToolBinding(it, conversationId, messageId, tool); return it }
        require(dao.findForTurn(conversationId.toString(), sourceMessageId.toString()) == null) {
            "本轮已经提出过生图任务。请使用已有任务；如需再次生图，请用户发送新的请求。"
        }
        val prompt = imageToolProposedPrompt(tool.input)
        val model = settings.resolveChatImageModel() ?: error("请先配置可用的图片模型")
        val provider = requireNotNull(model.findProvider(settings.providers))
        val constraints = providers.imageGenerationConstraints(provider, model).forChatImageTool()
        require(constraints.supportsGeneration) { "所选模型不支持生图" }
        val options = (defaults[conversationId] ?: ImageGenerationRequestState()).copy(prompt = prompt).constrained(constraints)
        val request = makeRequest(settings, model.id, options, confirming = false)
        val now = System.currentTimeMillis()
        val task = ChatImageToolTaskEntity(id, conversationId.toString(), messageId.toString(), sourceMessageId.toString(),
            tool.toolCallId, imageToolHash(tool.input), originalPrompt, prompt, JsonInstant.encodeToString(request),
            createdAt = now, updatedAt = now)
        check(dao.insert(task) != -1L) { "本轮生图任务已存在，请使用已有确认卡" }
        return task
    }

    fun makeRequest(settings: Settings, modelId: Uuid, options: ImageGenerationRequestState, confirming: Boolean = true): ChatImageToolRequest {
        val model = settings.findModelById(modelId)?.takeIf { it.type == ModelType.IMAGE } ?: error("图片模型已不可用")
        val provider = model.findProvider(settings.providers)?.takeIf { it.enabled } ?: error("图片供应商已禁用")
        val constraints = providers.imageGenerationConstraints(provider, model).forChatImageTool()
        require(constraints.supportsGeneration) { "模型不支持生图" }
        if (confirming) {
            require(model.customBodies.none { it.enabled }) { "该模型的自定义请求体可能覆盖确认参数。请在此选择不含自定义请求体的图片模型；原有手动生图仍可使用。" }
            require(validImageToolSize(options.size, constraints)) { "尺寸不符合所选模型要求，请检查高级参数" }
        }
        require(options.prompt.isNotBlank() && options.prompt.length <= 12_000) { "提示词须为 1–12000 个字符" }
        val normalized = options.copy(prompt = options.prompt.trim()).constrained(constraints)
        require(normalized == options.copy(prompt = options.prompt.trim())) { "参数与模型能力不兼容，请重新检查参数" }
        return ChatImageToolRequest(model.id.toString(), provider.id.toString(), model.displayName.ifBlank { model.modelId },
            provider.name, routeHash(provider, model), normalized)
    }

    suspend fun approve(
        id: String, conversationId: Uuid, messageId: Uuid, tool: UIMessagePart.Tool,
        settings: Settings, modelId: Uuid, options: ImageGenerationRequestState,
    ): Boolean {
        val task = requireNotNull(get(id)) { "任务已不存在" }
        validateImageToolBinding(task, conversationId, messageId, tool)
        require(System.currentTimeMillis() - task.createdAt < CHAT_IMAGE_TOOL_APPROVAL_TTL) { "确认已过期，请取消后重新发起请求" }
        val request = JsonInstant.encodeToString(makeRequest(settings, modelId, options))
        if (task.status == "approved") return task.requestJson == request
        return dao.approve(id, request, System.currentTimeMillis()) == 1
    }

    suspend fun hasApprovedTask(conversationId: Uuid, messageId: Uuid, tool: UIMessagePart.Tool): Boolean {
        val task = get(imageToolTaskId(conversationId.toString(), messageId.toString(), tool.toolCallId)) ?: return false
        return runCatching { validateImageToolBinding(task, conversationId, messageId, tool) }.isSuccess && task.status == "approved"
    }

    /** The persisted request, not model arguments or ask_user answers, is the authority. */
    suspend fun approveAutomatically(task: ChatImageToolTaskEntity): Boolean {
        if (!defaults.containsKey(Uuid.parse(task.conversationId))) return false
        val settings = settingsStore.settingsFlowRaw.first()
        val conversation = conversations.getConversationById(Uuid.parse(task.conversationId)) ?: return false
        val assistant = settings.assistants.find { it.id == conversation.assistantId } ?: return false
        if (!assistant.enableImageGenerationTool || !assistant.autoApproveImageGeneration) return false
        val snapshot = JsonInstant.decodeFromString<ChatImageToolRequest>(task.requestJson)
        val validated = runCatching { makeRequest(settings, Uuid.parse(snapshot.modelId), snapshot.options) }.getOrNull()
        if (validated != snapshot) return false
        return dao.approve(task.id, JsonInstant.encodeToString(snapshot.copy(automatic = true)), System.currentTimeMillis()) == 1
    }

    suspend fun inspect(id: String, conversationId: Uuid): List<UIMessagePart> {
        val task = requireNotNull(get(id)) { "图片任务不存在" }
        val conversation = requireNotNull(conversations.getConversationById(conversationId))
        validateImageInspection(task, conversationId, conversation.currentMessages)
        val urls = JsonInstant.decodeFromString<List<String>>(task.imagesJson)
        require(urls.isNotEmpty() && task.status in setOf("succeeded", "partial", "unknown")) { "尚无已保存的图片" }
        return listOf(UIMessagePart.Text("Saved images from task ${task.id}; inspect only as needed.")) + urls.map { UIMessagePart.Image(it) }
    }

    suspend fun cancel(conversationId: Uuid, messageId: Uuid, tool: UIMessagePart.Tool) {
        initialize()
        dao.transition(imageToolTaskId(conversationId.toString(), messageId.toString(), tool.toolCallId),
            listOf("pending", "approved"), "cancelled", null, System.currentTimeMillis())
    }

    suspend fun execute(conversationId: Uuid, messageId: Uuid, tool: UIMessagePart.Tool): List<UIMessagePart> {
        val id = imageToolTaskId(conversationId.toString(), messageId.toString(), tool.toolCallId)
        val task = requireNotNull(get(id)) { "生图任务未确认" }
        validateImageToolBinding(task, conversationId, messageId, tool)
        if (task.status != "approved") {
            return resultParts(if (task.status == "running" && isRunning(conversationId)) {
                runner.run(id) { error("已有任务正在执行") }
            } else task)
        }
        require(tool.approvalState is me.rerere.ai.ui.ToolApprovalState.Approved) { "生图请求未获批准" }
        val request = JsonInstant.decodeFromString<ChatImageToolRequest>(task.requestJson)
        val settings = settingsStore.settingsFlowRaw.first()
        if (request.automatic) {
            val conversation = conversations.getConversationById(conversationId)
            val assistant = settings.assistants.find { it.id == conversation?.assistantId }
            if (assistant?.enableImageGenerationTool != true || !assistant.autoApproveImageGeneration) {
                dao.transition(id, listOf("approved"), "cancelled", "用户已关闭模型自行生图", System.currentTimeMillis())
                return resultParts(requireNotNull(dao.get(id)))
            }
        }
        val current = runCatching { makeRequest(settings, Uuid.parse(request.modelId), request.options).copy(automatic = request.automatic) }.getOrNull()
        if (current != request) {
            dao.transition(id, listOf("approved"), "failed", "模型或供应商配置已变化，请重新确认新的生成请求", System.currentTimeMillis())
            return resultParts(requireNotNull(dao.get(id)))
        }
        val model = JsonInstant.decodeFromString<me.rerere.ai.provider.Model>(
            JsonInstant.encodeToString(requireNotNull(settings.findModelById(Uuid.parse(request.modelId)))))
        val provider = JsonInstant.decodeFromString<ProviderSetting>(
            JsonInstant.encodeToString(requireNotNull(model.findProvider(settings.providers))))
        executionOwners += conversationId
        running += conversationId
        try {
            val result = runner.run(id) { save ->
                checkOwner(task)
                imageService.generate(provider, request.options.toParams(model)) { image ->
                    checkOwner(task)
                    save(image.url)
                }
            }
            return resultParts(result)
        } finally { running -= conversationId }
    }

    private suspend fun checkOwner(task: ChatImageToolTaskEntity) {
        val conversation = conversations.getConversationById(Uuid.parse(task.conversationId))
        val owner = conversation?.currentMessages?.lastOrNull()
        if (owner == null || owner.id.toString() != task.messageId || owner.getTools().none {
                it.toolCallId == task.toolCallId && imageToolHash(it.input) == task.inputHash
            }) throw ImageToolOwnerChanged()
    }

    private fun resultParts(task: ChatImageToolTaskEntity): List<UIMessagePart> {
        val images = JsonInstant.decodeFromString<List<String>>(task.imagesJson)
        return listOf(UIMessagePart.Text(buildJsonObject {
            put("status", task.status)
            put("image_count", images.size)
            put("task_id", task.id)
            put("instruction", if (images.isNotEmpty()) "Images were saved and displayed. Continue answering. Describe visual details only after inspecting actual image input with an available tool. Do not generate again to retry this reply."
                else "No completed image is available. Explain the status. Do not automatically call generate_image again; an uncertain request may already have been billed.")
            task.error?.let { put("error", it) }
        }.toString())) + images.map { UIMessagePart.Image(it) }
    }

    private fun routeHash(provider: ProviderSetting, model: me.rerere.ai.provider.Model) =
        imageToolHash(JsonInstant.encodeToString(provider) + JsonInstant.encodeToString(model))
}

internal class ImageToolOwnerChanged : kotlinx.coroutines.CancellationException("原对话或回答已删除或切换，停止回写生图结果")

internal fun validateImageToolBinding(task: ChatImageToolTaskEntity, conversationId: Uuid, messageId: Uuid, tool: UIMessagePart.Tool) {
    require(tool.toolName == CHAT_IMAGE_TOOL_NAME && task.conversationId == conversationId.toString() &&
        task.messageId == messageId.toString() && task.toolCallId == tool.toolCallId && task.inputHash == imageToolHash(tool.input)) {
        "生图确认与当前对话或参数不匹配"
    }
}
