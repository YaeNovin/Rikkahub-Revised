package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.InjectionPosition
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.LorebookEntryRuntimeState
import me.rerere.rikkahub.data.model.LorebookEntryStatus
import me.rerere.rikkahub.data.model.LorebookOverflowStrategy
import me.rerere.rikkahub.data.model.PromptInjectionDiagnosticEntry
import me.rerere.rikkahub.data.model.PromptInjectionDiagnostics
import me.rerere.rikkahub.data.model.PromptInjectionEvaluation
import me.rerere.rikkahub.data.model.evaluateKeywords
import me.rerere.rikkahub.data.model.extractContextForMatching
import me.rerere.rikkahub.data.model.isTriggered
import me.rerere.rikkahub.data.model.passesDeterministicProbability
import me.rerere.rikkahub.data.model.resolveActiveModes
import me.rerere.rikkahub.data.model.trimToEstimatedTokens
import me.rerere.rikkahub.data.ai.context.estimateTextTokens
import me.rerere.rikkahub.data.datastore.ExtensionManagementMode
import kotlin.uuid.Uuid
import me.rerere.rikkahub.data.model.LorebookScanSource
import me.rerere.rikkahub.data.model.lorebookScanText
import me.rerere.rikkahub.data.model.ruleFingerprint
import me.rerere.rikkahub.data.model.resolvedScanDepth
import me.rerere.rikkahub.data.model.scanText
import me.rerere.rikkahub.data.model.LorebookScanMode
import me.rerere.rikkahub.data.model.KeywordExpressionResult

/**
 * 提示词注入转换器
 *
 * 根据 Assistant 关联的 ModeInjection 和 Lorebook 进行提示词注入
 */
object PromptInjectionTransformer : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val entertainmentMode = ctx.settings.extensionManagementMode == ExtensionManagementMode.ENTERTAINMENT
        val evaluation = evaluateInjections(
            messages = messages,
            assistant = ctx.assistant,
            modeInjections = ctx.settings.modeInjections,
            lorebooks = ctx.settings.lorebooks,
            conversationModeInjectionIds = ctx.conversationModeInjectionIds,
            conversationLorebookIds = ctx.conversationLorebookIds,
            temporaryModeInjections = ctx.temporaryModeInjections,
            runtimeStates = ctx.lorebookRuntimeStates,
            currentUserTurn = ctx.conversationUserTurn,
            entertainmentMode = entertainmentMode,
            randomSeed = ctx.conversationId?.toString().orEmpty(),
            totalTokenBudget = ctx.settings.lorebookTotalTokenBudget,
            historyMessages = ctx.conversationMessages.ifEmpty { messages },
        )
        ctx.onPromptInjectionEvaluation?.invoke(evaluation)
        if (evaluation.injections.isEmpty()) return messages
        val byPosition = evaluation.injections
            .sortedByDescending { it.priority }
            .groupBy { it.position }
        return applyInjections(messages, byPosition)
    }
}

/**
 * 核心注入逻辑（可测试的纯函数）
 */
internal fun transformMessages(
    messages: List<UIMessage>,
    assistant: Assistant,
    modeInjections: List<PromptInjection.ModeInjection>,
    lorebooks: List<Lorebook>,
    conversationModeInjectionIds: Set<Uuid> = emptySet(),
    conversationLorebookIds: Set<Uuid> = emptySet(),
): List<UIMessage> {
    // 收集所有需要注入的内容
    val injections = collectInjections(
        messages = messages,
        assistant = assistant,
        modeInjections = modeInjections,
        lorebooks = lorebooks,
        conversationModeInjectionIds = conversationModeInjectionIds,
        conversationLorebookIds = conversationLorebookIds,
    )

    if (injections.isEmpty()) {
        return messages
    }

    // 按位置和优先级分组
    val byPosition = injections
        .sortedByDescending { it.priority }
        .groupBy { it.position }

    // 应用注入
    return applyInjections(messages, byPosition)
}

internal fun evaluateInjections(
    messages: List<UIMessage>,
    assistant: Assistant,
    modeInjections: List<PromptInjection.ModeInjection>,
    lorebooks: List<Lorebook>,
    conversationModeInjectionIds: Set<Uuid> = emptySet(),
    conversationLorebookIds: Set<Uuid> = emptySet(),
    temporaryModeInjections: Map<Uuid, Int> = emptyMap(),
    runtimeStates: Map<Uuid, LorebookEntryRuntimeState> = emptyMap(),
    currentUserTurn: Int? = null,
    entertainmentMode: Boolean = false,
    randomSeed: String = "",
    totalTokenBudget: Int = 0,
    historyMessages: List<UIMessage> = messages,
): PromptInjectionEvaluation {
    if (!entertainmentMode) {
        // One evaluator and diagnostic path; advanced entertainment fields remain dormant.
        return evaluateInjections(
            messages = messages, assistant = assistant, modeInjections = modeInjections,
            lorebooks = lorebooks.map { book -> book.copy(tokenBudget = 0, overflowStrategy = LorebookOverflowStrategy.DROP_LOW_PRIORITY, entries = book.entries.map {
                it.copy(keywordExpression = "", triggerProbability = 100, stickyTurns = 1, cooldownTurns = 0, exclusiveGroup = "", selectionWeight = 0)
            }) },
            conversationModeInjectionIds = conversationModeInjectionIds, conversationLorebookIds = conversationLorebookIds,
            currentUserTurn = currentUserTurn, entertainmentMode = true, randomSeed = randomSeed,
            totalTokenBudget = totalTokenBudget, historyMessages = historyMessages,
        ).copy(runtimeStates = emptyMap())
    }

    val nonSystemMessages = messages.filter { it.role != MessageRole.SYSTEM }
    val userTurn = currentUserTurn ?: nonSystemMessages.count { it.role == MessageRole.USER }
    val conversationModes = if (assistant.allowConversationPromptInjection) {
        conversationModeInjectionIds
    } else {
        emptySet()
    }
    val temporaryModes = if (assistant.allowConversationPromptInjection) {
        temporaryModeInjections
    } else {
        emptyMap()
    }
    val activeModes = resolveActiveModes(
        modeInjections = modeInjections,
        assistantModeIds = assistant.modeInjectionIds,
        conversationModeIds = conversationModes,
        temporaryModes = temporaryModes,
        currentUserTurn = userTurn,
    )
    val injections = activeModes.mapTo(mutableListOf<PromptInjection>()) { it.injection }
    val validEntryIds = lorebooks.flatMap { it.entries }.mapTo(hashSetOf()) { it.id }
    val updatedRuntime = runtimeStates.filterKeys { it in validEntryIds }.toMutableMap()
    val diagnostics = mutableListOf<PromptInjectionDiagnosticEntry>()
    val scanCache = mutableMapOf<Triple<Int, LorebookScanSource, LorebookScanMode>, String>()
    val userMessages = historyMessages.filter { it.role == MessageRole.USER }
    var totalRemaining = if (totalTokenBudget > 0) totalTokenBudget else Int.MAX_VALUE
    val effectiveLorebookIds = assistant.lorebookIds + if (assistant.allowConversationPromptInjection) {
        conversationLorebookIds
    } else {
        emptySet()
    }

    val activeEntryIds = lorebooks.filter { it.enabled && it.id in effectiveLorebookIds }.flatMap { it.entries.filter { entry -> entry.enabled } }.map { it.id }.toSet()
    updatedRuntime.keys.retainAll(activeEntryIds)

    lorebooks.filter { it.enabled && it.id in effectiveLorebookIds }.forEach { lorebook ->
        data class Candidate(
            val entry: PromptInjection.RegexInjection,
            val matchedTerms: List<String>,
            val status: LorebookEntryStatus,
        )

        val candidates = mutableListOf<Candidate>()
        lorebook.entries.filter { it.enabled }.forEach { entry ->
            val depth = entry.resolvedScanDepth(lorebook)
            val context = if (entry.constantActive) "" else scanCache.getOrPut(Triple(depth, entry.scanSource, entry.scanMode)) { entry.scanText(nonSystemMessages, lorebook) }
            val keywordResult = if (depth == 0 && !entry.constantActive) KeywordExpressionResult(false, emptyList()) else entry.evaluateKeywords(context)
            val fingerprint = entry.copy(scanDepth = depth).ruleFingerprint()
            val previous = runtimeStates[entry.id]?.takeIf {
                it.ruleFingerprint == fingerprint && it.lastTriggeredTurn <= userTurn &&
                    (it.triggerMessageId == null || it.triggerMessageId == userMessages.getOrNull(it.lastTriggeredTurn - 1)?.id?.toString()) &&
                    (it.triggerTextHash == null || it.triggerTextHash == userMessages.getOrNull(it.lastTriggeredTurn - 1)?.toText()?.hashCode())
            }
            if (previous == null) updatedRuntime.remove(entry.id)
            val stickyActive = previous != null && userTurn <= previous.activeUntilTurn
            val candidateStatus = when {
                keywordResult.error != null -> LorebookEntryStatus.INVALID_EXPRESSION
                stickyActive -> LorebookEntryStatus.ACTIVE_FROM_PREVIOUS_TURN
                !keywordResult.matched -> LorebookEntryStatus.NOT_MATCHED
                previous != null && userTurn <= previous.cooldownUntilTurn -> LorebookEntryStatus.COOLDOWN
                !passesDeterministicProbability(entry.id, userTurn, entry.triggerProbability.coerceIn(0, 100), randomSeed) ->
                    LorebookEntryStatus.PROBABILITY_MISSED
                else -> LorebookEntryStatus.USED
            }
            if (candidateStatus == LorebookEntryStatus.USED ||
                candidateStatus == LorebookEntryStatus.ACTIVE_FROM_PREVIOUS_TURN
            ) {
                candidates += Candidate(entry, keywordResult.matchedTerms, candidateStatus)
            } else {
                diagnostics += entry.toDiagnostic(
                    lorebook = lorebook,
                    matchedTerms = keywordResult.matchedTerms,
                    status = candidateStatus,
                    detail = keywordResult.error ?: if (depth == 0) "未扫描：深度为 0，不会通过关键词激活；常驻与持续激活独立生效" else null,
                )
            }
        }

        val winners = candidates.filter { it.entry.exclusiveGroup.isNotBlank() }
            .groupBy { it.entry.exclusiveGroup.trim().lowercase() }.values.map { group ->
                val sticky = group.filter { it.status == LorebookEntryStatus.ACTIVE_FROM_PREVIOUS_TURN }
                val overrides = group.filter { it.entry.groupOverride }
                if (sticky.isNotEmpty()) sticky.maxBy { it.entry.priority }
                else if (overrides.isNotEmpty()) overrides.maxBy { it.entry.priority }
                else if (group.any { it.entry.selectionWeight > 0 }) {
                    val random = kotlin.random.Random("$randomSeed:$userTurn:${group.first().entry.exclusiveGroup}".hashCode())
                    var ticket = random.nextLong(group.sumOf { it.entry.selectionWeight.coerceIn(0, 10000).toLong() })
                    group.first { ticket -= it.entry.selectionWeight.coerceIn(0, 10000); ticket < 0 }
                } else group.maxBy { it.entry.priority }
            }.map { it.entry.id }.toSet()
        val ordered = candidates.filter { candidate ->
            val included = candidate.entry.exclusiveGroup.isBlank() || candidate.entry.id in winners
            if (!included) diagnostics += candidate.entry.toDiagnostic(lorebook, candidate.matchedTerms, LorebookEntryStatus.NOT_MATCHED, detail = "互斥组中采用了其他条目")
            included
        }.sortedByDescending { it.entry.priority }
        val budget = lorebook.tokenBudget.coerceAtLeast(0)
        val totalTokens = ordered.sumOf { estimateTextTokens(it.entry.content) }
        if (lorebook.overflowStrategy == LorebookOverflowStrategy.SKIP_BOOK && totalTokens > minOf(totalRemaining, if (budget > 0) budget else Int.MAX_VALUE)) {
            ordered.forEach { candidate ->
                diagnostics += candidate.entry.toDiagnostic(
                    lorebook,
                    candidate.matchedTerms,
                    LorebookEntryStatus.BUDGET_EXCEEDED,
                )
            }
            return@forEach
        }

        var usedTokens = 0
        var truncated = false
        ordered.forEach { candidate ->
            val tokens = estimateTextTokens(candidate.entry.content)
            val remaining = minOf(totalRemaining, if (budget == 0) Int.MAX_VALUE else budget - usedTokens)
            val selected = when {
                tokens <= remaining -> candidate.entry
                lorebook.overflowStrategy == LorebookOverflowStrategy.TRUNCATE_LAST && !truncated && remaining > 0 -> {
                    truncated = true
                    candidate.entry.copy(content = trimToEstimatedTokens(candidate.entry.content, remaining))
                }
                else -> null
            }
            if (selected == null || selected.content.isBlank()) {
                diagnostics += candidate.entry.toDiagnostic(
                    lorebook,
                    candidate.matchedTerms,
                    LorebookEntryStatus.BUDGET_EXCEEDED,
                )
            } else {
                val selectedTokens = estimateTextTokens(selected.content)
                usedTokens += selectedTokens
                totalRemaining -= selectedTokens
                injections += selected
                if (candidate.status == LorebookEntryStatus.USED) {
                    val activeUntil = userTurn + candidate.entry.stickyTurns.coerceIn(1, 10000) - 1
                    updatedRuntime[candidate.entry.id] = LorebookEntryRuntimeState(
                        lastTriggeredTurn = userTurn,
                        activeUntilTurn = activeUntil,
                        cooldownUntilTurn = activeUntil + candidate.entry.cooldownTurns.coerceIn(0, 10000),
                        ruleFingerprint = candidate.entry.copy(scanDepth = candidate.entry.resolvedScanDepth(lorebook)).ruleFingerprint(),
                        triggerMessageId = userMessages.getOrNull(userTurn - 1)?.id?.toString(),
                        triggerTextHash = userMessages.getOrNull(userTurn - 1)?.toText()?.hashCode(),
                    )
                }
                diagnostics += selected.toDiagnostic(
                    lorebook,
                    candidate.matchedTerms,
                    candidate.status,
                    estimatedTokens = selectedTokens,
                    detail = if (selected.content != candidate.entry.content) "truncated" else if (selected.constantActive) "常驻激活：仅在已选择本书的助手或对话生效，仍受预算与互斥约束" else null,
                )
            }
        }
    }

    return PromptInjectionEvaluation(
        injections = injections,
        runtimeStates = updatedRuntime,
        diagnostics = PromptInjectionDiagnostics(
            userTurn = userTurn,
            entries = diagnostics.map { diagnostic ->
                val state = updatedRuntime[diagnostic.entryId]
                diagnostic.copy(
                    remainingActiveTurns = state?.let { (it.activeUntilTurn - userTurn).coerceAtLeast(0) } ?: 0,
                    remainingCooldownTurns = state?.let { (it.cooldownUntilTurn - maxOf(userTurn, it.activeUntilTurn)).coerceAtLeast(0) } ?: 0,
                    injectedContent = injections.firstOrNull { it.id == diagnostic.entryId }?.content,
                )
            },
            totalEstimatedTokens = injections.sumOf { estimateTextTokens(it.content) },
        ),
    )
}

private fun PromptInjection.RegexInjection.toDiagnostic(
    lorebook: Lorebook,
    matchedTerms: List<String>,
    status: LorebookEntryStatus,
    estimatedTokens: Int = estimateTextTokens(content),
    detail: String? = null,
) = PromptInjectionDiagnosticEntry(
    lorebookId = lorebook.id,
    lorebookName = lorebook.name,
    entryId = id,
    entryName = name,
    matchedTerms = matchedTerms,
    status = status,
    position = position,
    estimatedTokens = estimatedTokens,
    detail = detail,
)

/**
 * 收集需要注入的内容
 */
internal fun collectInjections(
    messages: List<UIMessage>,
    assistant: Assistant,
    modeInjections: List<PromptInjection.ModeInjection>,
    lorebooks: List<Lorebook>,
    conversationModeInjectionIds: Set<Uuid> = emptySet(),
    conversationLorebookIds: Set<Uuid> = emptySet(),
): List<PromptInjection> {
    val injections = mutableListOf<PromptInjection>()
    val conversationModes = if (assistant.allowConversationPromptInjection) {
        conversationModeInjectionIds
    } else {
        emptySet()
    }
    val effectiveModes = resolveActiveModes(
        modeInjections = modeInjections,
        assistantModeIds = assistant.modeInjectionIds,
        conversationModeIds = conversationModes,
        temporaryModes = emptyMap(),
        currentUserTurn = 0,
    )
    val effectiveLorebookIds = assistant.lorebookIds + if (assistant.allowConversationPromptInjection) {
        conversationLorebookIds
    } else {
        emptySet()
    }

    // 1. 获取关联的 ModeInjection
    effectiveModes.forEach { injections.add(it.injection) }

    // 2. 获取关联的 Lorebook 中被触发的 RegexInjection
    val enabledLorebooks = lorebooks.filter {
        it.enabled && effectiveLorebookIds.contains(it.id)
    }
    if (enabledLorebooks.isNotEmpty()) {
        // 提取上下文用于匹配（只取非 SYSTEM 消息）
        val nonSystemMessages = messages.filter { it.role != MessageRole.SYSTEM }

        enabledLorebooks.forEach { lorebook ->
            lorebook.entries
                .filter { entry ->
                    val context = entry.scanText(nonSystemMessages, lorebook)
                    entry.enabled && (entry.constantActive || (entry.resolvedScanDepth(lorebook) > 0 && entry.isTriggered(context)))
                }
                .forEach { injections.add(it) }
        }
    }

    return injections
}

/**
 * 应用注入到消息列表
 */
internal fun applyInjections(
    messages: List<UIMessage>,
    byPosition: Map<InjectionPosition, List<PromptInjection>>
): List<UIMessage> {
    val result = messages.toMutableList()

    // 找到系统消息的索引（通常是第一条）
    val systemIndex = result.indexOfFirst { it.role == MessageRole.SYSTEM }

    // 处理 BEFORE_SYSTEM_PROMPT 和 AFTER_SYSTEM_PROMPT
    if (systemIndex >= 0) {
        val beforeContent = byPosition[InjectionPosition.BEFORE_SYSTEM_PROMPT]
            ?.joinToString("\n") { it.content } ?: ""
        val afterContent = byPosition[InjectionPosition.AFTER_SYSTEM_PROMPT]
            ?.joinToString("\n") { it.content } ?: ""

        if (beforeContent.isNotEmpty() || afterContent.isNotEmpty()) {
            val systemMessage = result[systemIndex]
            val originalText = systemMessage.parts
                .filterIsInstance<UIMessagePart.Text>()
                .joinToString("") { it.text }

            val newText = buildString {
                if (beforeContent.isNotEmpty()) {
                    append(beforeContent)
                    appendLine()
                }
                append(originalText)
                if (afterContent.isNotEmpty()) {
                    appendLine()
                    append(afterContent)
                }
            }

            result[systemIndex] = systemMessage.copy(
                parts = listOf(UIMessagePart.Text(newText))
            )
        }
    } else {
        // 没有系统消息时，创建一个新的系统消息
        val beforeContent = byPosition[InjectionPosition.BEFORE_SYSTEM_PROMPT]
            ?.joinToString("\n") { it.content } ?: ""
        val afterContent = byPosition[InjectionPosition.AFTER_SYSTEM_PROMPT]
            ?.joinToString("\n") { it.content } ?: ""

        val combinedContent = buildString {
            if (beforeContent.isNotEmpty()) {
                append(beforeContent)
            }
            if (afterContent.isNotEmpty()) {
                if (isNotEmpty()) appendLine()
                append(afterContent)
            }
        }

        if (combinedContent.isNotEmpty()) {
            result.add(0, UIMessage.system(combinedContent))
        }
    }

    // 处理 TOP_OF_CHAT：在第一条用户消息之前插入
    val topInjections = byPosition[InjectionPosition.TOP_OF_CHAT]
    if (!topInjections.isNullOrEmpty()) {
        // 重新计算索引（因为可能插入了系统消息）
        var insertIndex = result.indexOfFirst { it.role == MessageRole.USER }
            .takeIf { it >= 0 } ?: result.size
        insertIndex = findSafeInsertIndex(result, insertIndex)
        createMergedInjectionMessages(topInjections).forEach { message ->
            result.add(insertIndex, message)
            insertIndex++
        }
    }

    // 处理 BOTTOM_OF_CHAT：在最后一条消息之前插入
    val bottomInjections = byPosition[InjectionPosition.BOTTOM_OF_CHAT]
    if (!bottomInjections.isNullOrEmpty()) {
        var insertIndex = (result.size - 1).coerceAtLeast(0)
        insertIndex = findSafeInsertIndex(result, insertIndex)
        createMergedInjectionMessages(bottomInjections).forEach { message ->
            result.add(insertIndex, message)
            insertIndex++
        }
    }

    // 处理 AT_DEPTH：在指定深度位置插入（从最新消息往前数）
    // 按 injectDepth 分组，相同深度的合并，按深度从大到小处理（避免索引变化问题）
    val atDepthInjections = byPosition[InjectionPosition.AT_DEPTH]
    if (!atDepthInjections.isNullOrEmpty()) {
        val byDepth = atDepthInjections.groupBy { it.injectDepth }
        byDepth.keys.sortedDescending().forEach { depth ->
            val injections = byDepth[depth] ?: return@forEach
            // 计算插入位置：result.size - depth，但要确保在有效范围内
            // depth=1 表示在最后一条消息之前，depth=2 表示在倒数第二条之前...
            var insertIndex = (result.size - depth.coerceAtLeast(1)).coerceIn(0, result.size)
            insertIndex = findSafeInsertIndex(result, insertIndex)
            createMergedInjectionMessages(injections).forEach { message ->
                result.add(insertIndex, message)
                insertIndex++
            }
        }
    }

    return result
}

/**
 * 将同一 role 的注入合并成消息列表
 * 按 role 分组后合并内容，返回合并后的消息列表
 */
private fun createMergedInjectionMessages(injections: List<PromptInjection>): List<UIMessage> {
    return injections
        .groupBy { it.role }
        .map { (role, grouped) ->
            val mergedContent = grouped.joinToString("\n") { it.content }
            when (role) {
                MessageRole.ASSISTANT -> UIMessage.assistant(mergedContent)
                else -> UIMessage.user(mergedContent)
            }
        }
}

/**
 * 查找安全的插入位置，避免注入到 USER → ASSISTANT(含Tool) 之间
 *
 * 某些提供商（如 deepseek）要求 USER 之后紧跟带工具的 ASSISTANT，
 * 在两者之间插入消息会导致报错或破坏推理连续性。
 */
internal fun findSafeInsertIndex(messages: List<UIMessage>, targetIndex: Int): Int {
    var index = targetIndex.coerceIn(0, messages.size)

    // 向前查找，直到找到一个安全的位置
    while (index > 0) {
        val prevMessage = messages.getOrNull(index - 1)
        val currentMessage = messages.getOrNull(index)

        // 不能插入到 USER → ASSISTANT(含Tool) 之间
        val isPrevUser = prevMessage?.role == MessageRole.USER
        val isCurrentAssistantWithTools = currentMessage?.role == MessageRole.ASSISTANT
            && currentMessage.getTools().isNotEmpty()

        if (isPrevUser && isCurrentAssistantWithTools) {
            index--
        } else {
            break
        }
    }

    return index
}
