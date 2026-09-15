package me.rerere.rikkahub.data.model

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import kotlinx.serialization.json.*

/** Editor overrides must survive inherited defaults and exported source metadata. */
internal fun PromptInjection.RegexInjection.withExplicitLorebookMatching(caseSensitive: Boolean? = null, wholeWords: Boolean? = null): PromptInjection.RegexInjection {
    val source = if (sourceFormat == LorebookSourceFormat.NATIVE) sourceData else JsonObject(sourceData.orEmpty() + buildMap {
        caseSensitive?.let { put("caseSensitive", JsonPrimitive(it)) }
        wholeWords?.let { put("matchWholeWords", JsonPrimitive(it)) }
    })
    return copy(caseSensitive = caseSensitive ?: this.caseSensitive, matchWholeWords = wholeWords ?: matchWholeWords, sourceData = source)
}

internal fun PromptInjection.RegexInjection.withLorebookMatchingDefaults(book: Lorebook): PromptInjection.RegexInjection {
    if (sourceFormat == LorebookSourceFormat.NATIVE) return this
    fun explicit(vararg keys: String): Boolean = keys.any { key ->
        val value = sourceData?.get(key)?.takeUnless { it is JsonNull } ?: (sourceData?.get("extensions") as? JsonObject)?.get(key)
        value != null && value !is JsonNull
    }
    return copy(caseSensitive = if (explicit("caseSensitive", "case_sensitive")) caseSensitive else book.defaultCaseSensitive,
        matchWholeWords = if (explicit("matchWholeWords", "match_whole_words")) matchWholeWords else book.defaultMatchWholeWords)
}

internal fun PromptInjection.RegexInjection.ruleFingerprint(): String {
    // Source-only fields do not affect execution and can contain large vendor extensions.
    val rule = copy(sourceData = null)
    return synchronized(fingerprintCache) {
        fingerprintCache.getOrPut(rule) {
            val json = me.rerere.rikkahub.utils.JsonInstant
            val fields = json.encodeToJsonElement(PromptInjection.RegexInjection.serializer(), rule).jsonObject.toMutableMap()
            // Preserve fingerprints already stored by previous versions for unchanged native rules.
            fields.remove("sourceData")
            if (rule.insertionOrder == null) fields.remove("insertionOrder")
            if (rule.timingUnit == LorebookTimingUnit.USER_TURNS) fields.remove("timingUnit")
            if (rule.delayMessages == 0) fields.remove("delayMessages")
            if (!rule.matchWholeWords) fields.remove("matchWholeWords")
            if (rule.sourceFormat == LorebookSourceFormat.NATIVE) fields.remove("sourceFormat")
            if (rule.unsupportedPosition == null) fields.remove("unsupportedPosition")
            if (!rule.preventRecursion) fields.remove("preventRecursion")
            if (!rule.excludeRecursion) fields.remove("excludeRecursion")
            if (!rule.delayUntilRecursion) fields.remove("delayUntilRecursion")
            if (rule.recursionLevel == 0) fields.remove("recursionLevel")
            if (!rule.useGroupScoring) fields.remove("useGroupScoring")
            if (rule.outletName.isBlank()) fields.remove("outletName")
            if (rule.generationTriggers.isEmpty()) fields.remove("generationTriggers")
            if (rule.characterFilter.isEmpty()) fields.remove("characterFilter")
            if (!rule.characterFilterExclude) fields.remove("characterFilterExclude")
            if (!rule.prioritizeInclusion) fields.remove("prioritizeInclusion")
            if (rule.inclusionGroups.isEmpty()) fields.remove("inclusionGroups")
            if (!rule.ignoreBudget) fields.remove("ignoreBudget")
            if (rule.characterFilterTags.isEmpty()) fields.remove("characterFilterTags")
            if (!rule.vectorized) fields.remove("vectorized")
            if (rule.additionalMatchingSources.isEmpty()) fields.remove("additionalMatchingSources")
            val serialized = json.encodeToString(JsonObject.serializer(), JsonObject(fields))
            java.security.MessageDigest.getInstance("SHA-256").digest(serialized.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        }
    }
}

/** Fingerprint layout used before compatibility metadata was introduced. */
internal fun PromptInjection.RegexInjection.legacyRuleFingerprint(): String {
    val rule = copy(sourceData = null)
    val json = me.rerere.rikkahub.utils.JsonInstant
    val fields = json.encodeToJsonElement(PromptInjection.RegexInjection.serializer(), rule).jsonObject.toMutableMap()
    setOf("insertionOrder", "timingUnit", "delayMessages", "matchWholeWords", "sourceFormat", "sourceData", "unsupportedPosition")
        .forEach(fields::remove)
    val serialized = json.encodeToString(JsonObject.serializer(), JsonObject(fields))
    return java.security.MessageDigest.getInstance("SHA-256")
        .digest(serialized.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

private val fingerprintCache = object : LinkedHashMap<PromptInjection.RegexInjection, String>(32, .75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<PromptInjection.RegexInjection, String>?) = size > 64
}

fun PromptInjection.RegexInjection.validationErrors(entertainment: Boolean): List<String> = buildList {
    if (name.isBlank()) add("条目名称不能为空")
    if (content.isBlank()) add("条目内容不能为空")
    if (enabled && unsupportedPosition != null) add("来源插入位置尚未映射；请在高级设置选择位置或停用条目")
    if (scanMode == LorebookScanMode.CUSTOM && scanDepth !in 0..1000) add("扫描深度须为 0–1000，0 表示不扫描")
    if (position == InjectionPosition.AT_DEPTH && injectDepth !in 0..1000) add("插入深度须为 0–1000，0 表示提示末尾")
    if (triggerProbability !in 0..100) add("触发概率须为 0–100")
    if (stickyTurns !in (if (timingUnit == LorebookTimingUnit.MESSAGES) 0 else 1)..10000 || cooldownTurns !in 0..10000) add("持续或冷却计数超出范围；按消息计数时 0 表示不持续")
    if (delayMessages !in 0..10000) add("延迟激活须为 0–10000 条消息")
    if (recursionLevel !in 0..1000) add("递归级别须为 0–1000，0 表示任意递归层")
    if (position == InjectionPosition.OUTLET && outletName.isBlank()) add("Outlet 条目必须填写名称")
    if (selectionWeight !in 0..10000) add("随机权重须为 0–10000")
    if (enabled && !constantActive && !vectorized && keywords.none { it.isNotBlank() } && !((entertainment || sourceFormat != LorebookSourceFormat.NATIVE) && keywordExpression.isNotBlank())) add("请填写触发条件、开启常驻或设置向量触发")
    val candidate = if (entertainment || sourceFormat != LorebookSourceFormat.NATIVE) this@validationErrors else copy(keywordExpression = "")
    candidate.evaluateKeywords("").error?.let { add(it) }
}

fun Lorebook.validationErrors(): List<String> = buildList {
    if (name.isBlank()) add("世界书名称不能为空")
    if (defaultScanDepth !in 0..1000) add("默认扫描深度须为 0–1000")
    if (tokenBudget !in 0..1_000_000) add("Token 预算须为 0–1000000")
    if (maxRecursionSteps !in 0..1000) add("最大递归步数须为 0–1000")
    if (minActivations !in 0..10000) add("最少激活条目须为 0–10000")
    if (maxScanDepth !in 0..1000) add("额外回溯最大深度须为 0–1000")
    if (minActivations > 0 && maxRecursionSteps > 0) add("最少激活与最大递归步数互斥，同时设置时将优先采用递归上限")
    entries.forEach { entry -> entry.validationErrors(true).forEach { issue -> add("${entry.name.ifBlank { "未命名条目" }}：$issue") } }
}

internal fun lorebookScanText(messages: List<UIMessage>, depth: Int, source: LorebookScanSource, includeNames: Boolean = false, userName: String = "user", assistantName: String = "assistant", tavernBoundaries: Boolean = false): String {
    if (depth == 0) return ""
    val selected = when (source) {
        LorebookScanSource.USER -> messages.filter { it.role == MessageRole.USER }
        LorebookScanSource.ASSISTANT -> messages.filter { it.role == MessageRole.ASSISTANT }
        else -> messages
    }.takeLast(depth.coerceIn(1, 1000))
    return selected.joinToString("\n") { message ->
        val text = if (source == LorebookScanSource.TEXT_ONLY) message.parts.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
        else message.toText()
        val namedText = if (includeNames) {
            val participant = when (message.role) {
                MessageRole.USER -> userName.ifBlank { "user" }
                MessageRole.ASSISTANT -> assistantName.ifBlank { "assistant" }
                else -> message.role.name.lowercase()
            }
            "$participant: $text"
        } else text
        if (tavernBoundaries) "\u0001$namedText" else namedText
    }
}

internal fun PromptInjection.RegexInjection.resolvedScanDepth(book: Lorebook): Int = when (scanMode) {
    LorebookScanMode.CUSTOM -> scanDepth.coerceIn(0, 1000)
    LorebookScanMode.INHERIT -> book.defaultScanDepth.coerceIn(0, 1000)
    LorebookScanMode.CURRENT_INPUT -> 1
    LorebookScanMode.NONE -> 0
}

internal fun PromptInjection.RegexInjection.scanText(messages: List<UIMessage>, book: Lorebook, userName: String = "user", assistantName: String = "assistant"): String {
    if (constantActive) return ""
    if (scanMode == LorebookScanMode.CURRENT_INPUT) {
        val current = messages.lastOrNull { it.role == MessageRole.USER } ?: return ""
        val text = current.parts.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
        val namedText = if (book.includeNames) "${userName.ifBlank { "user" }}: $text" else text
        return if (sourceFormat != LorebookSourceFormat.NATIVE) "\u0001$namedText" else namedText
    }
    return lorebookScanText(messages, resolvedScanDepth(book), scanSource, book.includeNames, userName, assistantName, sourceFormat != LorebookSourceFormat.NATIVE)
}

internal fun Lorebook.withRevisionOf(previous: Lorebook): Lorebook {
    if (copy(revisions = emptyList()) == previous.copy(revisions = emptyList())) return this
    val revision = LorebookRevision(System.currentTimeMillis(), previous.name, previous.description, previous.enabled,
        previous.entries, previous.tokenBudget, previous.overflowStrategy, previous.defaultScanDepth, previous.sourceFormat, previous.sourceData,
        previous.recursiveScanning, previous.maxRecursionSteps, previous.minActivations, previous.maxScanDepth, previous.includeNames, previous.useGroupScoring,
        previous.defaultCaseSensitive, previous.defaultMatchWholeWords, previous.alertOnOverflow)
        .let { it.copy(sourceScope = previous.sourceScope) }
    return copy(revisions = (previous.revisions + revision).takeLast(10))
}

fun Lorebook.restore(revision: LorebookRevision) = copy(name = revision.name, description = revision.description,
    enabled = revision.enabled, entries = revision.entries, tokenBudget = revision.tokenBudget, overflowStrategy = revision.overflowStrategy, defaultScanDepth = revision.defaultScanDepth,
    sourceFormat = revision.sourceFormat, sourceData = revision.sourceData,
    recursiveScanning = revision.recursiveScanning, maxRecursionSteps = revision.maxRecursionSteps,
    minActivations = revision.minActivations, maxScanDepth = revision.maxScanDepth,
    includeNames = revision.includeNames, useGroupScoring = revision.useGroupScoring,
    defaultCaseSensitive = revision.defaultCaseSensitive, defaultMatchWholeWords = revision.defaultMatchWholeWords,
    alertOnOverflow = revision.alertOnOverflow)
    .copy(sourceScope = revision.sourceScope)

/** A merge or transfer must not reinterpret an inherited scan depth using the target book. */
fun PromptInjection.RegexInjection.forTransferFrom(book: Lorebook): PromptInjection.RegexInjection =
    if (scanMode == LorebookScanMode.INHERIT) copy(scanMode = LorebookScanMode.CUSTOM, scanDepth = book.defaultScanDepth) else this

internal fun List<PromptInjection>.orderedForInsertion(): List<PromptInjection> =
    sortedWith(compareBy<PromptInjection> { (it as? PromptInjection.RegexInjection)?.lorebookSourceRank ?: 0 }.thenBy {
        (it as? PromptInjection.RegexInjection)?.insertionOrder?.toLong() ?: -it.priority.toLong()
    })
