package me.rerere.rikkahub.ui.pages.extensions

import androidx.compose.runtime.*
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.data.model.*

/** Large drafts stay in a ViewModel, never in the Activity's saved-state Bundle. */
internal class LorebookEditorSession(val base: Settings, val original: Lorebook) {
    var draft by mutableStateOf(original)
    var entry by mutableStateOf<LorebookEntryDraft?>(null)
    var budget by mutableStateOf(original.tokenBudget.toString())
    var defaultDepth by mutableStateOf(original.defaultScanDepth.toString())
    var maxRecursionSteps by mutableStateOf(original.maxRecursionSteps.toString())
    var minActivations by mutableStateOf(original.minActivations.toString())
    var maxScanDepth by mutableStateOf(original.maxScanDepth.toString())
    var saving by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    val dirty get() = draft != original || budget != original.tokenBudget.toString() || defaultDepth != original.defaultScanDepth.toString() ||
        maxRecursionSteps != original.maxRecursionSteps.toString() || minActivations != original.minActivations.toString() || maxScanDepth != original.maxScanDepth.toString() || entry != null

    fun restore(revision: LorebookRevision) {
        draft = draft.restore(revision)
        budget = draft.tokenBudget.toString(); defaultDepth = draft.defaultScanDepth.toString()
        maxRecursionSteps = draft.maxRecursionSteps.toString(); minActivations = draft.minActivations.toString(); maxScanDepth = draft.maxScanDepth.toString()
    }

    val validNumbers get() = budget.toIntOrNull() in 0..1000000 && defaultDepth.toIntOrNull() in 0..1000 &&
        maxRecursionSteps.toIntOrNull() in 0..1000 && minActivations.toIntOrNull() in 0..10000 && maxScanDepth.toIntOrNull() in 0..1000
}

internal class LorebookEntryDraft(val original: PromptInjection.RegexInjection) {
    var draft by mutableStateOf(original)
    var keywords by mutableStateOf(original.keywords.joinToString("\n"))
    var settingKeys by mutableStateOf(original.settingKeys.joinToString(", "))
    var groups by mutableStateOf(original.inclusionGroups.ifEmpty { listOf(original.exclusiveGroup).filter(String::isNotBlank) }.joinToString(", "))
    var characters by mutableStateOf(original.characterFilter.joinToString(", "))
    var characterTags by mutableStateOf(original.characterFilterTags.joinToString(", "))
    var allWords by mutableStateOf("")
    var anyWords by mutableStateOf("")
    var notWords by mutableStateOf("")
    var testText by mutableStateOf("")
    val numbers = mutableStateMapOf(
        "扫描深度" to original.scanDepth.toString(), "优先级" to original.priority.toString(),
        "插入深度" to original.injectDepth.toString(), "概率" to original.triggerProbability.toString(),
        "持续计数" to original.stickyTurns.toString(), "冷却计数" to original.cooldownTurns.toString(),
        "随机权重" to original.selectionWeight.toString(), "延迟消息数" to original.delayMessages.toString(),
        "插入顺序" to original.insertionOrder?.toString().orEmpty(),
        "递归级别" to original.recursionLevel.toString(),
    )

    fun candidate(): PromptInjection.RegexInjection {
        val selectedGroups = parseLorebookList(groups)
        return draft.copy(
            keywords = keywords.lines().map(String::trim).filter(String::isNotEmpty).distinct(),
            settingKeys = parseLorebookList(settingKeys),
            inclusionGroups = if (selectedGroups == original.inclusionGroups.ifEmpty { listOf(original.exclusiveGroup).filter(String::isNotBlank) }) original.inclusionGroups else selectedGroups,
            exclusiveGroup = selectedGroups.firstOrNull().orEmpty(),
            characterFilter = parseLorebookList(characters), characterFilterTags = parseLorebookList(characterTags),
            scanDepth = numbers["扫描深度"]?.toIntOrNull() ?: draft.scanDepth,
            priority = numbers["优先级"]?.toIntOrNull() ?: draft.priority,
            injectDepth = numbers["插入深度"]?.toIntOrNull() ?: draft.injectDepth,
            triggerProbability = numbers["概率"]?.toIntOrNull() ?: draft.triggerProbability,
            stickyTurns = numbers["持续计数"]?.toIntOrNull() ?: draft.stickyTurns,
            cooldownTurns = numbers["冷却计数"]?.toIntOrNull() ?: draft.cooldownTurns,
            selectionWeight = numbers["随机权重"]?.toIntOrNull() ?: draft.selectionWeight,
            delayMessages = numbers["延迟消息数"]?.toIntOrNull() ?: draft.delayMessages,
            recursionLevel = numbers["递归级别"]?.toIntOrNull() ?: draft.recursionLevel,
            insertionOrder = numbers["插入顺序"]?.toIntOrNull(),
        )
    }

    fun numberErrors(advanced: Boolean): List<String> = numbers.keys.filter { key -> when (key) {
        "扫描深度" -> !draft.constantActive && draft.scanMode == LorebookScanMode.CUSTOM
        "插入深度" -> draft.position == InjectionPosition.AT_DEPTH
        "优先级", "插入顺序" -> true
        else -> advanced
    } }.filter { numbers[it]?.toIntOrNull() == null && !(it == "插入顺序" && numbers[it].isNullOrBlank()) }
        .map { "$it 请填写整数" }

    val dirty get() = candidate() != original || keywords != original.keywords.joinToString("\n") ||
        settingKeys != original.settingKeys.joinToString(", ") || groups != original.inclusionGroups.ifEmpty { listOf(original.exclusiveGroup).filter(String::isNotBlank) }.joinToString(", ") ||
        characters != original.characterFilter.joinToString(", ") || characterTags != original.characterFilterTags.joinToString(", ") || numberErrors(true).isNotEmpty()
}

internal fun parseLorebookList(text: String): List<String> = text.split(',', '，', '\n').map(String::trim).filter(String::isNotBlank).distinct()

internal fun buildLorebookExpression(all: String, any: String, not: String): String {
    fun terms(text: String, op: String) = text.lines().map(String::trim).filter(String::isNotBlank).joinToString(" $op ", transform = ::quoteLorebookKeyword)
    return listOf(terms(all, "AND").takeIf(String::isNotBlank)?.let { "($it)" },
        terms(any, "OR").takeIf(String::isNotBlank)?.let { "($it)" },
        terms(not, "OR").takeIf(String::isNotBlank)?.let { "NOT ($it)" }).filterNotNull().joinToString(" AND ")
}
