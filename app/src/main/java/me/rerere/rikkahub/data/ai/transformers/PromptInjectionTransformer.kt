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
import me.rerere.rikkahub.data.model.ruleFingerprint
import me.rerere.rikkahub.data.model.legacyRuleFingerprint
import me.rerere.rikkahub.data.model.resolvedScanDepth
import me.rerere.rikkahub.data.model.scanText
import me.rerere.rikkahub.data.model.LorebookScanMode
import me.rerere.rikkahub.data.model.KeywordExpressionResult
import me.rerere.rikkahub.data.model.LorebookSourceFormat
import me.rerere.rikkahub.data.model.LorebookTimingUnit
import me.rerere.rikkahub.data.model.LorebookMatchBudget
import me.rerere.rikkahub.data.model.orderedForInsertion
import me.rerere.rikkahub.data.model.lorebookScanText
import me.rerere.rikkahub.data.model.withLorebookMatchingDefaults
import me.rerere.rikkahub.data.model.LorebookGenerationTrigger
import me.rerere.rikkahub.data.model.LorebookSourceScope
import me.rerere.rikkahub.data.model.LorebookInsertionStrategy
import me.rerere.rikkahub.data.model.resolve
import me.rerere.rikkahub.data.model.insertionRank
import me.rerere.rikkahub.data.model.parseLorebookDecorators
import me.rerere.rikkahub.utils.applyPlaceholders

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
        val variables = PlaceholderTransformer.resolveValues(ctx).toList().toTypedArray()
        val sources = ctx.settings.lorebookSources.resolve(ctx.assistant, ctx.conversationLorebookIds, ctx.disabledLorebookIds)
        val vectorEntries = ctx.settings.lorebooks.filter { it.enabled && it.id in sources }.flatMap { it.entries }
            .filter { it.enabled && it.vectorized && (entertainmentMode || it.sourceFormat != LorebookSourceFormat.NATIVE) }
        val vectorMatches = if (ctx.allowLorebookNetwork && ctx.settings.lorebookSources.vectorEnabled && vectorEntries.isNotEmpty()) {
            org.koin.core.context.GlobalContext.get().get<LorebookVectorMatcher>().match(ctx.context, ctx.settings, vectorEntries, ctx.conversationMessages.ifEmpty { messages })
        } else LorebookVectorMatches(note = if (vectorEntries.isNotEmpty()) (if (!ctx.allowLorebookNetwork) "静态预览不调用向量模型，仅使用关键词" else "世界书向量匹配未开启，继续使用关键词") else null)
        val matchingFields = ctx.assistant.lorebookCharacterFields + mapOf(
            "matchCharacterDescription" to (ctx.assistant.lorebookCharacterFields["matchCharacterDescription"] ?: ctx.assistant.systemPrompt),
            "matchPersonaDescription" to ctx.settings.lorebookSources.personas.firstOrNull { it.id == ctx.settings.lorebookSources.activePersonaId }?.description.orEmpty().takeIf { ctx.assistant.usePersonaLorebooks }.orEmpty(),
        )
        val evaluation = evaluateInjections(
            messages = messages,
            assistant = ctx.assistant.copy(lorebookIds = sources.keys),
            modeInjections = ctx.settings.modeInjections,
            lorebooks = ctx.settings.lorebooks,
            conversationModeInjectionIds = ctx.conversationModeInjectionIds,
            conversationLorebookIds = ctx.conversationLorebookIds - ctx.disabledLorebookIds,
            temporaryModeInjections = ctx.temporaryModeInjections,
            runtimeStates = ctx.lorebookRuntimeStates,
            currentUserTurn = ctx.conversationUserTurn,
            entertainmentMode = entertainmentMode,
            randomSeed = ctx.conversationId?.toString().orEmpty(),
            totalTokenBudget = ctx.settings.lorebookTotalTokenBudget,
            historyMessages = ctx.conversationMessages.ifEmpty { messages },
            resolveContent = { it.applyPlaceholders(*variables) },
            userName = ctx.userName,
            assistantName = ctx.assistantName,
            generationTrigger = ctx.generationTrigger,
            assistantTagNames = ctx.assistantTagNames,
            sourceBindings = sources,
            insertionStrategy = ctx.settings.lorebookSources.insertionStrategy,
            vectorMatches = vectorMatches,
            additionalMatchingText = matchingFields,
        )
        ctx.onPromptInjectionEvaluation?.invoke(evaluation)
        if (evaluation.injections.isEmpty()) return messages
        val byPosition = evaluation.injections
            .orderedForInsertion()
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
        .orderedForInsertion()
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
    resolveContent: (String) -> String = { it },
    userName: String = "user",
    assistantName: String = assistant.name.ifBlank { "assistant" },
    generationTrigger: LorebookGenerationTrigger = LorebookGenerationTrigger.NORMAL,
    assistantTagNames: Set<String> = emptySet(),
    sourceBindings: Map<Uuid, LorebookSourceScope> = emptyMap(),
    insertionStrategy: LorebookInsertionStrategy = LorebookInsertionStrategy.LEGACY,
    vectorMatches: LorebookVectorMatches = LorebookVectorMatches(),
    additionalMatchingText: Map<String, String> = emptyMap(),
): PromptInjectionEvaluation {
    if (!entertainmentMode) {
        // One evaluator and diagnostic path; advanced entertainment fields remain dormant.
        val compatibleIds = lorebooks.flatMap { it.entries }.filter { it.sourceFormat != LorebookSourceFormat.NATIVE }.mapTo(hashSetOf()) { it.id }
        return evaluateInjections(
            messages = messages, assistant = assistant, modeInjections = modeInjections,
            lorebooks = lorebooks.map { book ->
                val compatible = book.entries.any { it.sourceFormat != LorebookSourceFormat.NATIVE }
                book.copy(
                    tokenBudget = if (compatible) book.tokenBudget else 0,
                    recursiveScanning = if (compatible) book.recursiveScanning else false,
                    maxRecursionSteps = if (compatible) book.maxRecursionSteps else 0,
                    minActivations = if (compatible) book.minActivations else 0,
                    maxScanDepth = if (compatible) book.maxScanDepth else 0,
                    includeNames = if (compatible) book.includeNames else false,
                    useGroupScoring = if (compatible) book.useGroupScoring else false,
                    entries = book.entries.map {
                        if (it.sourceFormat != LorebookSourceFormat.NATIVE) it else it.copy(
                            keywordExpression = "", triggerProbability = 100, stickyTurns = 1,
                            cooldownTurns = 0, delayMessages = 0, timingUnit = LorebookTimingUnit.USER_TURNS,
                            exclusiveGroup = "", selectionWeight = 0, delayUntilRecursion = false,
                            inclusionGroups = emptyList(),
                            recursionLevel = 0, useGroupScoring = false, outletName = "",
                            preventRecursion = false, excludeRecursion = false,
                            generationTriggers = emptySet(), characterFilter = emptyList(),
                            characterFilterExclude = false, prioritizeInclusion = false,
                            ignoreBudget = false, characterFilterTags = emptyList(),
                            vectorized = false,
                            additionalMatchingSources = emptySet(),
                        )
                    }
                )
            },
            conversationModeInjectionIds = conversationModeInjectionIds, conversationLorebookIds = conversationLorebookIds,
            currentUserTurn = currentUserTurn, entertainmentMode = true, randomSeed = randomSeed,
            totalTokenBudget = totalTokenBudget, historyMessages = historyMessages, resolveContent = resolveContent,
            runtimeStates = runtimeStates.filterKeys { it in compatibleIds },
            userName = userName,
            assistantName = assistantName,
            generationTrigger = generationTrigger,
            assistantTagNames = assistantTagNames,
            sourceBindings = sourceBindings,
            insertionStrategy = insertionStrategy,
            vectorMatches = vectorMatches,
            additionalMatchingText = additionalMatchingText,
        ).let { evaluation -> evaluation.copy(runtimeStates = evaluation.runtimeStates.filterKeys { it in compatibleIds }) }
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
    val scanCache = mutableMapOf<String, String>()
    val userMessages = historyMessages.filter { it.role == MessageRole.USER }
    val timedMessages = historyMessages.filter { it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT }
    val matchBudget = LorebookMatchBudget()
    fun tick(entry: PromptInjection.RegexInjection) = if (entry.timingUnit == LorebookTimingUnit.MESSAGES) timedMessages.size else userTurn
    fun decorator(entry: PromptInjection.RegexInjection, name: String): String? = if (entry.sourceFormat == LorebookSourceFormat.CHARACTER_CARD_V3) {
        parseLorebookDecorators(entry.content)[name]?.firstOrNull()
    } else null
    fun hasDecorator(entry: PromptInjection.RegexInjection, name: String) = decorator(entry, name) != null
    fun entryContent(entry: PromptInjection.RegexInjection) = if (entry.sourceFormat == LorebookSourceFormat.CHARACTER_CARD_V3) entry.content.lineSequence().filterNot { it.trim().startsWith("@@") }.joinToString("\n") else entry.content
    fun tokens(content: String) = estimateTextTokens(resolveContent(content))
    var totalRemaining = if (totalTokenBudget > 0) totalTokenBudget else Int.MAX_VALUE
    val effectiveLorebookIds = assistant.lorebookIds + if (assistant.allowConversationPromptInjection) {
        conversationLorebookIds
    } else {
        emptySet()
    }

    val activeEntryIds = lorebooks.filter { it.enabled && it.id in effectiveLorebookIds }.flatMap { it.entries.filter { entry -> entry.enabled } }.map { it.id }.toSet()
    updatedRuntime.keys.retainAll(activeEntryIds)

    // Keep the SillyTavern source precedence for the sources currently exposed by the app:
    // chat-bound lore is evaluated before assistant-bound lore, so its entries receive the
    // earlier share of the cross-book budget. Persona/global bindings can extend this rank later.
    val conversationLorebookSet = if (assistant.allowConversationPromptInjection) conversationLorebookIds else emptySet()
    val enabledLorebooks = lorebooks
        .filter { it.enabled && it.id in effectiveLorebookIds }
        .sortedBy {
            when {
                it.id in sourceBindings -> sourceBindings.getValue(it.id).insertionRank(if (insertionStrategy == LorebookInsertionStrategy.LEGACY) LorebookInsertionStrategy.CHARACTER_FIRST else insertionStrategy)
                it.id in conversationLorebookSet -> 0
                it.sourceScope == LorebookSourceScope.PERSONA -> 1
                it.sourceScope == LorebookSourceScope.CHARACTER -> 2
                else -> 3
            }
        }
    val maxRecursionPasses = if (enabledLorebooks.any { it.recursiveScanning }) {
        // A zero limit is unbounded in SillyTavern. We cap it defensively here,
        // but must not let another book's smaller explicit limit constrain it.
        if (enabledLorebooks.any { it.recursiveScanning && it.maxRecursionSteps == 0 }) 32
        else enabledLorebooks.maxOfOrNull { it.maxRecursionSteps.coerceIn(1, 32) } ?: 0
    } else 0
    val recursiveBuffer = StringBuilder()
    val bookUsedTokens = mutableMapOf<Uuid, Int>()
    val occupiedGroups = mutableSetOf<String>()
    val expandedDepths = mutableMapOf<Uuid, Int>()
    val delayedLevels = enabledLorebooks.flatMap { it.entries }.filter { it.enabled && it.delayUntilRecursion }
        .map { it.recursionLevel.coerceAtLeast(1) }.distinct().sorted()
    var delayedLevel = delayedLevels.firstOrNull() ?: 1
    val activatedIds = injections.mapNotNull { (it as? PromptInjection.RegexInjection)?.id }.toMutableSet()
    fun groupsOf(entry: PromptInjection.RegexInjection): List<String> = (entry.inclusionGroups.ifEmpty { listOf(entry.exclusiveGroup) })
        .map(String::trim).filter(String::isNotBlank).distinct()
    var recursionPass = 0
    var scanSweeps = 0
    while (true) {
        if (++scanSweeps > 1000) break
        val injectionsBeforePass = injections.size
        enabledLorebooks.forEach { lorebook ->
            if (recursionPass > 0 && (!lorebook.recursiveScanning || (lorebook.maxRecursionSteps > 0 && recursionPass >= lorebook.maxRecursionSteps))) return@forEach
        data class Candidate(
            val entry: PromptInjection.RegexInjection,
            val matchedTerms: List<String>,
            val status: LorebookEntryStatus,
            val score: Int,
        )

        val candidates = mutableListOf<Candidate>()
        lorebook.entries.filter { it.enabled }.forEach { entry ->
            if (entry.id in activatedIds) return@forEach
            if (entry.unsupportedPosition != null) {
                updatedRuntime.remove(entry.id)
                diagnostics += entry.toDiagnostic(lorebook, emptyList(), LorebookEntryStatus.INVALID_EXPRESSION, detail = "未应用：来源位置 ${entry.unsupportedPosition} 需要手动映射", recursionLevel = recursionPass, source = if (recursionPass == 0) "chat" else "recursion")
                return@forEach
            }
            if (entry.position == InjectionPosition.OUTLET && entry.outletName.isBlank()) {
                diagnostics += entry.toDiagnostic(lorebook, emptyList(), LorebookEntryStatus.INVALID_EXPRESSION, detail = "Outlet 条目缺少名称，已跳过", recursionLevel = recursionPass, source = if (recursionPass == 0) "chat" else "recursion")
                return@forEach
            }
            if (entry.generationTriggers.isNotEmpty() && generationTrigger !in entry.generationTriggers) {
                diagnostics += entry.toDiagnostic(lorebook, emptyList(), LorebookEntryStatus.NOT_MATCHED, detail = "当前生成类型 ${generationTrigger.name} 不在条目触发范围", recursionLevel = recursionPass, source = "trigger")
                return@forEach
            }
            if (entry.position == InjectionPosition.OUTLET && !containsOutletMacro(messages, entry.outletName)) {
                diagnostics += entry.toDiagnostic(lorebook, emptyList(), LorebookEntryStatus.NOT_MATCHED, detail = "Outlet 未被当前提示词引用，不计入注入与预算", recursionLevel = recursionPass, source = "outlet")
                return@forEach
            }
            if (recursionPass > 0 && (entry.excludeRecursion || activatedIds.contains(entry.id))) return@forEach
            if (recursionPass == 0 && entry.delayUntilRecursion) {
                diagnostics += entry.toDiagnostic(lorebook, emptyList(), LorebookEntryStatus.NOT_MATCHED, detail = "仅递归阶段激活", recursionLevel = recursionPass, source = "chat")
                return@forEach
            }
            if (entry.delayUntilRecursion) {
                if (entry.recursionLevel.coerceAtLeast(1) > delayedLevel) return@forEach
            } else if (entry.recursionLevel > 0 && entry.recursionLevel != recursionPass) return@forEach
            // Imported Tavern books may define matching defaults at book level. Preserve an
            // explicit entry override; only fill defaults when the source did not provide it.
            val matchingEntry = entry.withLorebookMatchingDefaults(lorebook)
            val forcedByDecorator = hasDecorator(matchingEntry, "activate")
            if (matchingEntry.sourceFormat == LorebookSourceFormat.CHARACTER_CARD_V3 && hasDecorator(matchingEntry, "dont_activate") && !forcedByDecorator) {
                diagnostics += matchingEntry.toDiagnostic(lorebook, emptyList(), LorebookEntryStatus.NOT_MATCHED, detail = "V3 装饰器要求停用（@@dont_activate）", recursionLevel = recursionPass, source = "v3_decorator")
                return@forEach
            }
            val activateAfter = decorator(matchingEntry, "activate_only_after")?.toIntOrNull()
            val assistantMessages = timedMessages.count { it.role == MessageRole.ASSISTANT }
            if (!forcedByDecorator && activateAfter != null && assistantMessages < activateAfter) {
                diagnostics += matchingEntry.toDiagnostic(lorebook, emptyList(), LorebookEntryStatus.NOT_MATCHED, detail = "V3 延迟激活：$assistantMessages/$activateAfter 条助手消息", recursionLevel = recursionPass, source = "v3_decorator")
                return@forEach
            }
            val activateEvery = decorator(matchingEntry, "activate_only_every")?.toIntOrNull()
            if (!forcedByDecorator && activateEvery != null && activateEvery > 0 && assistantMessages % activateEvery != 0) {
                diagnostics += matchingEntry.toDiagnostic(lorebook, emptyList(), LorebookEntryStatus.NOT_MATCHED, detail = "V3 周期触发：当前消息数不满足每 ${activateEvery} 条", recursionLevel = recursionPass, source = "v3_decorator")
                return@forEach
            }
            val filterNames = matchingEntry.characterFilter.map { it.trim() }.filter { it.isNotBlank() }
            val filterTags = matchingEntry.characterFilterTags.map { it.trim() }.filter { it.isNotBlank() }
            if (filterNames.isNotEmpty() || filterTags.isNotEmpty()) {
                val matches = filterNames.any { it.equals(assistant.name.trim(), ignoreCase = true) } ||
                    filterTags.any { wanted -> assistantTagNames.any { it.equals(wanted, ignoreCase = true) } }
                val allowed = if (matchingEntry.characterFilterExclude) !matches else matches
                if (!allowed) {
                    diagnostics += matchingEntry.toDiagnostic(lorebook, emptyList(), LorebookEntryStatus.NOT_MATCHED, detail = "角色过滤未通过", recursionLevel = recursionPass, source = "character_filter")
                    return@forEach
                }
            }
            val currentTick = tick(entry)
            val anchorMessages = if (entry.timingUnit == LorebookTimingUnit.MESSAGES) timedMessages else userMessages
            val depth = decorator(entry, "scan_depth")?.toIntOrNull() ?: entry.resolvedScanDepth(lorebook)
            val extraContext = matchingEntry.additionalMatchingSources.mapNotNull { additionalMatchingText[it]?.takeIf(String::isNotBlank) }.joinToString("\n")
            val context = when {
                entry.constantActive -> ""
                entry.scanMode == LorebookScanMode.NONE && recursionPass == 0 -> ""
                recursionPass > 0 -> recursiveBuffer.toString()
                else -> {
                    val scanDepth = maxOf(depth, expandedDepths[lorebook.id] ?: 0)
                    scanCache.getOrPut("$scanDepth:${entry.scanSource}:${entry.scanMode}:${lorebook.includeNames}:${entry.sourceFormat}") {
                        if (entry.scanMode == LorebookScanMode.CURRENT_INPUT) entry.scanText(nonSystemMessages, lorebook, userName, assistantName)
                        else lorebookScanText(nonSystemMessages, scanDepth, entry.scanSource, lorebook.includeNames, userName, assistantName, entry.sourceFormat != LorebookSourceFormat.NATIVE)
                    }
                }
            }.let { if (extraContext.isBlank()) it else "$it\n$extraContext" }
            val fingerprint = matchingEntry.copy(scanDepth = depth).ruleFingerprint()
            val previous = runtimeStates[entry.id]?.takeIf {
                (it.ruleFingerprint == fingerprint || it.ruleFingerprint == entry.legacyRuleFingerprint()) && it.timingUnit == entry.timingUnit && it.lastTriggeredTurn <= currentTick &&
                    (entry.timingUnit != LorebookTimingUnit.MESSAGES || currentTick > it.lastTriggeredTurn) &&
                    (it.triggerMessageId == null || it.triggerMessageId == anchorMessages.getOrNull(it.lastTriggeredTurn - 1)?.id?.toString()) &&
                    (it.triggerTextHash == null || it.triggerTextHash == anchorMessages.getOrNull(it.lastTriggeredTurn - 1)?.toText()?.hashCode())
            }
            if (previous == null) updatedRuntime.remove(entry.id)
            val keepAfterMatch = hasDecorator(matchingEntry, "keep_activate_after_match") && previous != null
            val vectorMatched = recursionPass == 0 && matchingEntry.vectorized && matchingEntry.id in vectorMatches.scores
            var keywordResult = if (forcedByDecorator || keepAfterMatch || matchingEntry.constantActive) KeywordExpressionResult(true, emptyList())
            else if (vectorMatched) KeywordExpressionResult(true, listOf("向量匹配 ${"%.3f".format(java.util.Locale.ROOT, vectorMatches.scores.getValue(entry.id))}"), score = 0)
            else if (entry.scanMode == LorebookScanMode.NONE && recursionPass == 0 && extraContext.isBlank()) KeywordExpressionResult(false, emptyList())
            else if (depth == 0 && (expandedDepths[lorebook.id] ?: 0) == 0 && recursionPass == 0 && extraContext.isBlank()) KeywordExpressionResult(false, emptyList())
            else matchingEntry.evaluateKeywords(context, resolveContent, matchBudget)
            if (keywordResult.matched && !forcedByDecorator && matchingEntry.sourceFormat == LorebookSourceFormat.CHARACTER_CARD_V3 && !matchingEntry.useRegex) {
                val decorators = parseLorebookDecorators(matchingEntry.content)
                fun matches(value: String) = matchingEntry.copy(constantActive = false, keywordExpression = "", keywords = value.split(',').map(String::trim).filter(String::isNotBlank))
                    .evaluateKeywords(context, resolveContent, matchBudget)
                val additional = decorators["additional_keys"].orEmpty().map(::matches)
                val exclude = decorators["exclude_keys"]?.firstOrNull()?.let(::matches)
                val error = additional.firstNotNullOfOrNull { it.error } ?: exclude?.error
                if (error != null || additional.any { !it.matched } || exclude?.matched == true) keywordResult = KeywordExpressionResult(false, keywordResult.matchedTerms, error)
            }
            val stickyActive = previous != null && currentTick <= previous.activeUntilTurn
            val candidateStatus = when {
                keywordResult.error != null -> LorebookEntryStatus.INVALID_EXPRESSION
                timedMessages.size < entry.delayMessages -> LorebookEntryStatus.NOT_MATCHED
                hasDecorator(matchingEntry, "dont_activate_after_match") && previous != null -> LorebookEntryStatus.NOT_MATCHED
                stickyActive -> LorebookEntryStatus.ACTIVE_FROM_PREVIOUS_TURN
                !keywordResult.matched -> LorebookEntryStatus.NOT_MATCHED
                previous != null && currentTick <= previous.cooldownUntilTurn -> LorebookEntryStatus.COOLDOWN
                !passesDeterministicProbability(entry.id, currentTick, entry.triggerProbability.coerceIn(0, 100), randomSeed) ->
                    LorebookEntryStatus.PROBABILITY_MISSED
                else -> LorebookEntryStatus.USED
            }
            if (candidateStatus == LorebookEntryStatus.USED ||
                candidateStatus == LorebookEntryStatus.ACTIVE_FROM_PREVIOUS_TURN
            ) {
                candidates += Candidate(matchingEntry, keywordResult.matchedTerms, candidateStatus, keywordResult.score)
            } else {
                diagnostics += entry.toDiagnostic(
                    lorebook = lorebook,
                    matchedTerms = keywordResult.matchedTerms,
                    status = candidateStatus,
                    estimatedTokens = tokens(entryContent(entry)),
                    detail = keywordResult.error ?: (if (entry.vectorized) vectorMatches.note else null) ?: if (timedMessages.size < entry.delayMessages) "延迟激活：当前 ${timedMessages.size}/${entry.delayMessages} 条消息" else if (depth == 0 && recursionPass == 0) "未扫描聊天：深度为 0，可使用常驻、递归、额外来源或已启用的向量匹配" else null,
                    recursionLevel = recursionPass,
                    source = if (recursionPass == 0) "chat" else "recursion",
                )
            }
        }

        val winners = candidates.flatMap { candidate -> groupsOf(candidate.entry).map { it.lowercase() to candidate } }
            .groupBy({ it.first }, { it.second }).values.map { group ->
                val scoreEnabled = lorebook.useGroupScoring || group.any { it.entry.useGroupScoring }
                val scoredGroup = if (scoreEnabled) {
                    val maxScore = group.maxOfOrNull { it.score } ?: 0
                    group.filter { it.score == maxScore }
                } else group
                val sticky = scoredGroup.filter { it.status == LorebookEntryStatus.ACTIVE_FROM_PREVIOUS_TURN }
                val overrides = scoredGroup.filter { it.entry.groupOverride || it.entry.prioritizeInclusion }
                if (sticky.isNotEmpty()) sticky.maxBy { it.entry.priority }
                else if (overrides.isNotEmpty()) overrides.maxBy { it.entry.insertionOrder ?: it.entry.priority }
                else if (scoredGroup.any { it.entry.selectionWeight > 0 }) {
                    val random = kotlin.random.Random("$randomSeed:$userTurn:${group.first().entry.exclusiveGroup}".hashCode())
                    var ticket = random.nextLong(scoredGroup.sumOf { it.entry.selectionWeight.coerceIn(0, 10000).toLong() })
                    scoredGroup.first { ticket -= it.entry.selectionWeight.coerceIn(0, 10000); ticket < 0 }
                } else scoredGroup.maxBy { it.entry.priority }
            }.map { it.entry.id }.toSet()
        val winnerIds = winners
        val ordered = candidates.filter { candidate ->
            val candidateGroups = groupsOf(candidate.entry)
            val included = candidateGroups.isEmpty() || candidate.entry.id in winnerIds
            if (!included) diagnostics += candidate.entry.toDiagnostic(lorebook, candidate.matchedTerms, LorebookEntryStatus.NOT_MATCHED, detail = "互斥组中采用了其他条目", recursionLevel = recursionPass, source = if (recursionPass == 0) "chat" else "recursion")
            included
        }.sortedWith(compareByDescending<Candidate> { it.entry.sourceFormat == LorebookSourceFormat.SILLY_TAVERN && it.entry.constantActive }.thenByDescending { it.entry.priority })
        val budget = lorebook.tokenBudget.coerceAtLeast(0)
        val totalTokens = ordered.filterNot { it.entry.ignoreBudget }.sumOf { tokens(entryContent(it.entry)) }
        if (lorebook.overflowStrategy == LorebookOverflowStrategy.SKIP_BOOK && totalTokens > minOf(totalRemaining, if (budget > 0) budget - (bookUsedTokens[lorebook.id] ?: 0) else Int.MAX_VALUE)) {
            ordered.forEach { candidate ->
                diagnostics += candidate.entry.toDiagnostic(
                    lorebook,
                    candidate.matchedTerms,
                    LorebookEntryStatus.BUDGET_EXCEEDED,
                    estimatedTokens = tokens(entryContent(candidate.entry)),
                    recursionLevel = recursionPass,
                    source = if (recursionPass == 0) "chat" else "recursion",
                )
            }
            return@forEach
        }

        var usedTokens = bookUsedTokens[lorebook.id] ?: 0
        var truncated = false
        ordered.forEach { candidate ->
            val candidateGroups = groupsOf(candidate.entry).map { it.lowercase() }
            if (candidateGroups.any { it in occupiedGroups }) {
                diagnostics += candidate.entry.toDiagnostic(lorebook, candidate.matchedTerms, LorebookEntryStatus.NOT_MATCHED, detail = "组内已采用其他条目", recursionLevel = recursionPass, source = "group")
                return@forEach
            }
            val decoratedPosition = if (candidate.entry.sourceFormat == LorebookSourceFormat.CHARACTER_CARD_V3) {
                when (decorator(candidate.entry, "position")?.lowercase()) {
                    "before_desc" -> InjectionPosition.BEFORE_SYSTEM_PROMPT
                    "after_desc", "personality", "scenario" -> InjectionPosition.AFTER_SYSTEM_PROMPT
                    else -> candidate.entry.position
                }
            } else candidate.entry.position
            val decoratedRole = if (candidate.entry.sourceFormat == LorebookSourceFormat.CHARACTER_CARD_V3) {
                when (decorator(candidate.entry, "role")?.lowercase()) {
                    "system" -> MessageRole.SYSTEM
                    "assistant" -> MessageRole.ASSISTANT
                    "user" -> MessageRole.USER
                    else -> candidate.entry.role
                }
            } else candidate.entry.role
            val decoratedDepth = decorator(candidate.entry, "depth")?.toIntOrNull()
            val prepared = candidate.entry.copy(
                content = entryContent(candidate.entry),
                position = decoratedPosition,
                role = decoratedRole,
                injectDepth = decoratedDepth ?: candidate.entry.injectDepth,
                lorebookSourceRank = sourceBindings[lorebook.id]?.insertionRank(insertionStrategy) ?: 0,
            )
            val tokens = tokens(prepared.content)
            val remaining = if (candidate.entry.ignoreBudget) Int.MAX_VALUE
            else minOf(totalRemaining, if (budget == 0) Int.MAX_VALUE else budget - usedTokens)
            val selected = when {
                tokens <= remaining -> prepared
                lorebook.overflowStrategy == LorebookOverflowStrategy.TRUNCATE_LAST && !truncated && remaining > 0 -> {
                    truncated = true
                    prepared.copy(content = trimToEstimatedTokens(prepared.content, remaining, resolveContent))
                }
                else -> null
            }
            if (selected == null || selected.content.isBlank()) {
                diagnostics += candidate.entry.toDiagnostic(
                    lorebook,
                    candidate.matchedTerms,
                    LorebookEntryStatus.BUDGET_EXCEEDED,
                    estimatedTokens = tokens(prepared.content),
                    recursionLevel = recursionPass,
                    source = if (recursionPass == 0) "chat" else "recursion",
                )
            } else {
                val selectedTokens = tokens(selected.content)
                if (!candidate.entry.ignoreBudget) {
                    totalRemaining -= selectedTokens
                    usedTokens += selectedTokens
                    bookUsedTokens[lorebook.id] = usedTokens
                }
                injections += selected
                activatedIds += selected.id
                occupiedGroups += candidateGroups
                if (recursionPass < maxRecursionPasses && !selected.preventRecursion && selected.position != InjectionPosition.OUTLET) {
                    if (recursiveBuffer.isNotEmpty()) recursiveBuffer.appendLine()
                    recursiveBuffer.append(resolveContent(selected.content))
                }
                if (candidate.status == LorebookEntryStatus.USED) {
                    val currentTick = tick(candidate.entry)
                    val messageTiming = candidate.entry.timingUnit == LorebookTimingUnit.MESSAGES
                    val activeUntil = currentTick + candidate.entry.stickyTurns.coerceIn(if (messageTiming) 0 else 1, 10000) - 1
                    val anchorMessages = if (messageTiming) timedMessages else userMessages
                    updatedRuntime[candidate.entry.id] = LorebookEntryRuntimeState(
                        lastTriggeredTurn = currentTick,
                        activeUntilTurn = activeUntil,
                        cooldownUntilTurn = activeUntil + candidate.entry.cooldownTurns.coerceIn(0, 10000),
                        ruleFingerprint = candidate.entry.copy(scanDepth = candidate.entry.resolvedScanDepth(lorebook)).ruleFingerprint(),
                        triggerMessageId = anchorMessages.getOrNull(currentTick - 1)?.id?.toString(),
                        triggerTextHash = anchorMessages.getOrNull(currentTick - 1)?.toText()?.hashCode(),
                        timingUnit = candidate.entry.timingUnit,
                        recursionLevel = recursionPass,
                    )
                }
                diagnostics += selected.toDiagnostic(
                    lorebook,
                    candidate.matchedTerms,
                    candidate.status,
                    estimatedTokens = selectedTokens,
                    detail = if (selected.content != prepared.content) "truncated" else if (selected.constantActive) "常驻激活：仅在已选择本书的助手或对话生效，仍受预算与互斥约束" else if (selected.vectorized) vectorMatches.note else null,
                    recursionLevel = recursionPass,
                    source = if (recursionPass == 0) "chat" else "recursion",
                )
            }
        }
        }
        if (recursionPass < maxRecursionPasses) {
            if (injections.size > injectionsBeforePass) { recursionPass++; continue }
            val nextLevel = delayedLevels.firstOrNull { it > delayedLevel }
            if (nextLevel != null) { delayedLevel = nextLevel; recursionPass++; continue }
        }
        // Min Activations extends the chat scan by one message at a time. It never uses
        // recursive content as the initial scan and stops once enough entries were adopted.
        var expandAgain = false
        enabledLorebooks.filter { it.minActivations > 0 && it.maxRecursionSteps == 0 }.forEach { book ->
            val used = book.entries.count { it.id in activatedIds }
            val cap = minOf(nonSystemMessages.size, book.maxScanDepth.takeIf { it > 0 } ?: 1000)
            val baseDepth = book.entries.filter { !it.constantActive && it.scanMode !in setOf(LorebookScanMode.NONE, LorebookScanMode.CURRENT_INPUT) }
                .minOfOrNull { it.resolvedScanDepth(book) } ?: cap
            val depth = expandedDepths[book.id] ?: baseDepth
            if (used < book.minActivations && depth < cap && totalRemaining > 0 && (book.tokenBudget == 0 || (bookUsedTokens[book.id] ?: 0) < book.tokenBudget)) {
                expandedDepths[book.id] = depth + 1; expandAgain = true
            }
        }
        if (!expandAgain) break
        recursionPass = 0
        delayedLevel = delayedLevels.firstOrNull() ?: 1
    }

    return PromptInjectionEvaluation(
        injections = injections,
        runtimeStates = updatedRuntime,
        diagnostics = PromptInjectionDiagnostics(
            userTurn = userTurn,
            entries = diagnostics.map { diagnostic ->
                val state = updatedRuntime[diagnostic.entryId]
                val currentTick = if (state?.timingUnit == LorebookTimingUnit.MESSAGES) timedMessages.size else userTurn
                diagnostic.copy(
                    remainingActiveTurns = state?.let { (it.activeUntilTurn - currentTick).coerceAtLeast(0) } ?: 0,
                    remainingCooldownTurns = state?.let { (it.cooldownUntilTurn - maxOf(currentTick, it.activeUntilTurn)).coerceAtLeast(0) } ?: 0,
                    injectedContent = injections.firstOrNull { it.id == diagnostic.entryId }?.content?.let(resolveContent),
                    timingUnit = state?.timingUnit ?: diagnostic.timingUnit,
                )
            },
            totalEstimatedTokens = injections.sumOf { tokens(it.content) },
        ),
    )
}

private fun PromptInjection.RegexInjection.toDiagnostic(
    lorebook: Lorebook,
    matchedTerms: List<String>,
    status: LorebookEntryStatus,
    estimatedTokens: Int = estimateTextTokens(content),
    detail: String? = null,
    recursionLevel: Int = 0,
    source: String? = null,
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
    timingUnit = timingUnit,
    recursionLevel = recursionLevel,
    source = source,
)

private fun containsOutletMacro(messages: List<UIMessage>, outletName: String): Boolean {
    val name = outletName.trim()
    if (name.isBlank()) return false
    val macro = Regex("\\{\\{outlet::\\s*${Regex.escape(name)}\\s*}}")
    return messages.any { message ->
        message.parts.filterIsInstance<UIMessagePart.Text>().any { part -> macro.containsMatchIn(part.text) }
    }
}

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
                    entry.enabled && entry.unsupportedPosition == null && (entry.constantActive || (entry.resolvedScanDepth(lorebook) > 0 && entry.evaluateKeywords(context).matched))
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

    // SillyTavern outlets are explicit pull points. Expand them only in the
    // original prompt snapshot; outlet content itself is never rescanned.
    val outlets = byPosition[InjectionPosition.OUTLET]
        .orEmpty()
        .filterIsInstance<PromptInjection.RegexInjection>()
        .filter { it.outletName.isNotBlank() }
        .groupBy { it.outletName.trim() }
        .mapValues { (_, entries) -> entries.orderedForInsertion().joinToString("\n") { it.content } }
    if (outlets.isNotEmpty()) {
        val outletMacro = Regex("\\{\\{outlet::([^}]+)}}")
        result.indices.forEach { index ->
            val message = result[index]
            val parts = message.parts.map { part ->
                if (part is UIMessagePart.Text) {
                    part.copy(text = outletMacro.replace(part.text) { match -> outlets[match.groupValues[1].trim()].orEmpty() })
                } else part
            }
            result[index] = message.copy(parts = parts)
        }
    }

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

    // Resolve all anchors against the same snapshot. Insertions must not count toward depth.
    val pending = linkedMapOf<Int, MutableList<PromptInjection>>()
    fun schedule(index: Int, entries: List<PromptInjection>) {
        val safeIndex = findSafeInsertIndex(result, index)
        pending.getOrPut(safeIndex) { mutableListOf() }.addAll(entries)
    }
    byPosition[InjectionPosition.TOP_OF_CHAT]?.let {
        schedule(result.indexOfFirst { it.role == MessageRole.USER }.takeIf { it >= 0 } ?: result.size, it)
    }
    byPosition[InjectionPosition.BOTTOM_OF_CHAT]?.let { schedule((result.size - 1).coerceAtLeast(0), it) }
    byPosition[InjectionPosition.AT_DEPTH]?.groupBy { it.injectDepth }?.forEach { (depth, entries) ->
        val minimumDepth = if (entries.all { it is PromptInjection.RegexInjection }) 0 else 1
        schedule((result.size - depth.coerceAtLeast(minimumDepth)).coerceIn(0, result.size), entries)
    }
    pending.keys.sortedDescending().forEach { index ->
        result.addAll(index, createMergedInjectionMessages(pending.getValue(index).orderedForInsertion()))
    }

    return result
}

/**
 * 将同一 role 的注入合并成消息列表
 * 按 role 分组后合并内容，返回合并后的消息列表
 */
private fun createMergedInjectionMessages(injections: List<PromptInjection>): List<UIMessage> {
    if (injections.any { it is PromptInjection.RegexInjection && it.insertionOrder != null }) {
        val groups = mutableListOf<MutableList<PromptInjection>>()
        injections.forEach { injection ->
            if (groups.lastOrNull()?.lastOrNull()?.role != injection.role) groups += mutableListOf(injection)
            else groups.last().add(injection)
        }
        return groups.map { group ->
            val content = group.joinToString("\n") { it.content }
            when (group.first().role) {
                MessageRole.SYSTEM -> UIMessage.system(content)
                MessageRole.ASSISTANT -> UIMessage.assistant(content)
                else -> UIMessage.user(content)
            }
        }
    }
    return injections
        .groupBy { it.role }
        .map { (role, grouped) ->
            val mergedContent = grouped.joinToString("\n") { it.content }
            when (role) {
                MessageRole.ASSISTANT -> UIMessage.assistant(mergedContent)
                MessageRole.SYSTEM -> UIMessage.system(mergedContent)
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
