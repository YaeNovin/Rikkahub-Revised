package me.rerere.rikkahub.data.model

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

internal fun PromptInjection.RegexInjection.ruleFingerprint(): String {
    val serialized = me.rerere.rikkahub.utils.JsonInstant.encodeToString(PromptInjection.RegexInjection.serializer(), this)
    return java.security.MessageDigest.getInstance("SHA-256").digest(serialized.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

fun PromptInjection.RegexInjection.validationErrors(entertainment: Boolean): List<String> = buildList {
    if (name.isBlank()) add("条目名称不能为空")
    if (content.isBlank()) add("条目内容不能为空")
    if (scanMode == LorebookScanMode.CUSTOM && scanDepth !in 0..1000) add("扫描深度须为 0–1000，0 表示不扫描")
    if (position == InjectionPosition.AT_DEPTH && injectDepth !in 1..1000) add("插入深度须为 1–1000")
    if (triggerProbability !in 0..100) add("触发概率须为 0–100")
    if (stickyTurns !in 1..10000 || cooldownTurns !in 0..10000) add("持续轮数须为 1–10000，冷却须为 0–10000")
    if (selectionWeight !in 0..10000) add("随机权重须为 0–10000")
    if (enabled && !constantActive && keywords.none { it.isNotBlank() } && !(entertainment && keywordExpression.isNotBlank())) add("请填写触发条件或开启常驻")
    val candidate = if (entertainment) this@validationErrors else copy(keywordExpression = "")
    candidate.evaluateKeywords("").error?.let { add(it) }
}

internal fun lorebookScanText(messages: List<UIMessage>, depth: Int, source: LorebookScanSource): String {
    if (depth == 0) return ""
    val selected = when (source) {
        LorebookScanSource.USER -> messages.filter { it.role == MessageRole.USER }
        LorebookScanSource.ASSISTANT -> messages.filter { it.role == MessageRole.ASSISTANT }
        else -> messages
    }.takeLast(depth.coerceIn(1, 1000))
    return selected.joinToString("\n") { message ->
        if (source == LorebookScanSource.TEXT_ONLY) message.parts.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
        else message.toText()
    }
}

internal fun PromptInjection.RegexInjection.resolvedScanDepth(book: Lorebook): Int = when (scanMode) {
    LorebookScanMode.CUSTOM -> scanDepth.coerceIn(0, 1000)
    LorebookScanMode.INHERIT -> book.defaultScanDepth.coerceIn(0, 1000)
    LorebookScanMode.CURRENT_INPUT -> 1
    LorebookScanMode.NONE -> 0
}

internal fun PromptInjection.RegexInjection.scanText(messages: List<UIMessage>, book: Lorebook): String {
    if (constantActive) return ""
    if (scanMode == LorebookScanMode.CURRENT_INPUT) {
        val current = messages.lastOrNull { it.role == MessageRole.USER } ?: return ""
        return current.parts.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
    }
    return lorebookScanText(messages, resolvedScanDepth(book), scanSource)
}

internal fun Lorebook.withRevisionOf(previous: Lorebook): Lorebook {
    if (copy(revisions = emptyList()) == previous.copy(revisions = emptyList())) return this
    val revision = LorebookRevision(System.currentTimeMillis(), previous.name, previous.description, previous.enabled,
        previous.entries, previous.tokenBudget, previous.overflowStrategy, previous.defaultScanDepth)
    return copy(revisions = (previous.revisions + revision).takeLast(10))
}

fun Lorebook.restore(revision: LorebookRevision) = copy(name = revision.name, description = revision.description,
    enabled = revision.enabled, entries = revision.entries, tokenBudget = revision.tokenBudget, overflowStrategy = revision.overflowStrategy, defaultScanDepth = revision.defaultScanDepth)
