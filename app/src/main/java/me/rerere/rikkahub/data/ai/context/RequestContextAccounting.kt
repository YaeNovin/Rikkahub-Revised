package me.rerere.rikkahub.data.ai.context

import kotlinx.serialization.encodeToString
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.*
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.model.*
import me.rerere.rikkahub.utils.JsonInstant
import java.security.MessageDigest

fun requestContextScopeKey(conversation: Conversation, settings: Settings, model: Model): String {
    val assistant = settings.getAssistantById(conversation.assistantId)?.let { conversation.memoryAssistant(it, settings) }
    val provider = model.findProvider(settings.providers)
    return memoryContentHash(listOf(model.id, model.modelId, model.inputModalities, model.tools, model.customBodies, provider?.javaClass?.simpleName,
        (provider as? ProviderSetting.OpenAI)?.useResponseApi, countHistoryReasoning(provider),
        assistant?.systemPrompt, assistant?.allowConversationSystemPrompt, assistant?.messageTemplate, assistant?.regexes,
        assistant?.enableRollingContextCompression, assistant?.rollingContextCompressionThresholdTokens,
        assistant?.enableWebSearch, assistant?.mcpServers, assistant?.customBodies, assistant?.enableMemory, assistant?.enableMemoryRag,
        assistant?.enableEpisodicMemory, assistant?.useGlobalMemory, assistant?.localTools, assistant?.knowledgeBaseIds,
        assistant?.modeInjectionIds, assistant?.lorebookIds, assistant?.enabledSkills, assistant?.workspaceId,
        conversation.customSystemPrompt, conversation.modeInjectionIds, conversation.lorebookIds, conversation.disabledLorebookIds,
        conversation.memoryMode, conversation.rollingContextSummary?.content, conversation.rollingContextSummary?.sourceMessageIds).joinToString("\u0000"))
}

@Suppress("DEPRECATION")
fun contextHistoryFingerprint(messages: List<UIMessage>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    fun add(value: String) { digest.update(value.toByteArray(Charsets.UTF_8)); digest.update(0.toByte()) }
    fun part(value: UIMessagePart) {
        add(value.javaClass.simpleName)
        when (value) {
            is UIMessagePart.Text -> add(value.text)
            is UIMessagePart.Image -> add(value.url)
            is UIMessagePart.Video -> add(value.url)
            is UIMessagePart.Audio -> add(value.url)
            is UIMessagePart.Document -> { add(value.url); add(value.fileName) }
            is UIMessagePart.Reasoning -> add(value.reasoning)
            is UIMessagePart.Tool -> { add(value.toolCallId); add(value.toolName); add(value.input); value.output.forEach(::part) }
            is UIMessagePart.ServerTool -> { add(value.toolCallId); add(value.input.toString()); add(value.output.toString()) }
            is UIMessagePart.ToolCall -> { add(value.toolCallId); add(value.arguments) }
            is UIMessagePart.ToolResult -> { add(value.toolCallId); add(value.content.toString()) }
            else -> Unit
        }
    }
    messages.forEach { add(it.id.toString()); add(it.role.name); it.parts.forEach(::part) }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
}

/** Excludes billed hidden reasoning; it is not automatically resent as input text. */
@Suppress("DEPRECATION")
fun estimateRequestCategories(messages: List<UIMessage>, includeReasoning: Boolean = false): Map<String, Int> {
    val totals = linkedMapOf<String, Int>()
    fun add(key: String, count: Int) { if (count > 0) totals[key] = ((totals[key] ?: 0).toLong() + count).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() }
    fun part(value: UIMessagePart, textKind: String) {
        when (value) {
            is UIMessagePart.Reasoning -> if (includeReasoning) add("REASONING", estimateTextTokens(value.reasoning))
            is UIMessagePart.Tool -> {
                add("TOOLS", estimateTextTokens(value.toolCallId + value.toolName + value.input))
                value.output.forEach { part(it, "TOOLS") }
            }
            is UIMessagePart.Text -> add(if (value.text.trimStart().startsWith("<UploadFile")) "DOCUMENT" else textKind, estimateTextTokens(value.text))
            is UIMessagePart.Image -> add("IMAGE", estimatePartTokens(value))
            is UIMessagePart.Video -> add("VIDEO", estimatePartTokens(value))
            is UIMessagePart.Audio -> add("AUDIO", estimatePartTokens(value))
            is UIMessagePart.Document -> Unit // Its extracted text is counted separately by DocumentAsPromptTransformer.
            is UIMessagePart.ToolCall, is UIMessagePart.ToolResult, is UIMessagePart.ServerTool -> add("TOOLS", estimatePartTokens(value))
            else -> add("OTHER", estimatePartTokens(value))
        }
    }
    messages.forEach { message ->
        message.parts.forEach { part(it, if (message.role == MessageRole.SYSTEM) "SYSTEM" else "TEXT") }
        add("FRAMING", 4)
    }
    return totals
}

fun countHistoryReasoning(provider: ProviderSetting?): Boolean = when (provider) {
    is ProviderSetting.Claude -> true
    is ProviderSetting.OpenAI -> provider.includeHistoryReasoning && !provider.useResponseApi
    else -> false
}

fun makeRequestContextSnapshot(rawHistory: List<UIMessage>, providerMessages: List<UIMessage>, tools: List<Tool>,
    model: Model, scopeKey: String?, currentAssistantId: kotlin.uuid.Uuid?, provider: ProviderSetting? = null): RequestContextSnapshot {
    val history = if (rawHistory.lastOrNull()?.role == MessageRole.ASSISTANT) rawHistory.dropLast(1) else rawHistory
    val includeReasoning = countHistoryReasoning(provider)
    val categories = estimateRequestCategories(providerMessages, includeReasoning).toMutableMap()
    val schemas = tools.sumOf { tool -> estimateTextTokens(tool.name + tool.description + tool.parameters()?.let { JsonInstant.encodeToString(it) }.orEmpty()) }
    if (schemas > 0) categories["TOOL_SCHEMA"] = schemas
    return RequestContextSnapshot(model.id.toString(), contextHistoryFingerprint(history), scopeKey, categories,
        providerMessages.firstOrNull { it.id == currentAssistantId && it.role == MessageRole.ASSISTANT }
            ?.let { estimateRequestCategories(listOf(it), includeReasoning) }.orEmpty(), includeReasoning = includeReasoning)
}

fun List<UIMessage>.withRequestContext(snapshot: RequestContextSnapshot): List<UIMessage> {
    val index = indexOfLast { it.role == MessageRole.ASSISTANT }
    if (index < 0) return this
    return toMutableList().apply { this[index] = this[index].copy(requestContext = snapshot) }
}

/** Calibrate estimates to the provider's reported input, including a visible residual bucket. */
fun calibrateRequestCategories(estimated: Map<String, Int>, actual: Int?): Map<String, Int> {
    val source = estimated.filterValues { it > 0 }
    val total = source.values.sumOf(Int::toLong)
    if (actual == null || actual < 0 || total == 0L) return if (actual != null && actual > 0 && total == 0L) mapOf("OTHER" to actual) else source
    if (actual.toLong() >= total) return source.toMutableMap().apply {
        val extra = actual - total.toInt()
        if (extra > 0) this["OTHER"] = (this["OTHER"] ?: 0) + extra
    }
    val scaled = source.mapValues { (_, value) -> (value.toLong() * actual / total).toInt() }.toMutableMap()
    var remainder = actual - scaled.values.sum()
    source.keys.sortedByDescending { source.getValue(it).toLong() * actual % total }.forEach { if (remainder > 0) { scaled[it] = scaled.getValue(it) + 1; remainder-- } }
    return scaled.filterValues { it > 0 }
}
