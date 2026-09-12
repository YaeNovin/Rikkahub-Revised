package me.rerere.rikkahub.data.export

import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.model.*

internal fun readLorebookJson(input: java.io.InputStream): String {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        require(output.size() + count <= 16 * 1024 * 1024) { "世界书文件超过 16 MiB，请拆分后导入" }
        output.write(buffer, 0, count)
    }
    return output.toString("UTF-8")
}

/** Both standalone world info and embedded V2/V3 cards use this mapping. */
internal fun decodeTavernLorebook(book: JsonObject, fallbackName: String): Lorebook {
    val warnings = mutableListOf<String>()
    val raw = when (val entries = book["entries"]) {
        is JsonArray -> entries.toList()
        is JsonObject -> entries.values.toList()
        else -> throw IllegalArgumentException("世界书缺少有效的 entries 列表")
    }
    require(raw.size <= 10000) { "世界书最多支持 10000 个条目" }
    val entries = raw.mapIndexedNotNull { index, element ->
        val entry = element as? JsonObject ?: run { warnings += "第 ${index + 1} 项不是有效条目，已跳过"; return@mapIndexedNotNull null }
        val extensions = entry["extensions"] as? JsonObject ?: JsonObject(emptyMap())
        fun value(vararg keys: String): JsonPrimitive? = keys.firstNotNullOfOrNull { key ->
            ((entry[key]?.takeUnless { it is JsonNull } ?: extensions[key]) as? JsonPrimitive)?.takeUnless { it is JsonNull }
        }
        fun number(vararg keys: String) = value(*keys)?.intOrNull
        fun flag(vararg keys: String) = value(*keys)?.booleanOrNull
        fun text(vararg keys: String) = value(*keys)?.contentOrNull
        fun words(vararg keys: String): List<String> = keys.firstNotNullOfOrNull { entry[it] as? JsonArray }
            .orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.filter { it.isNotBlank() }
        val primary = words("keys", "key")
        val secondary = words("secondary_keys", "keysecondary")
        fun List<String>.expression(join: String) = joinToString(" $join ") { quoteLorebookKeyword(it) }
        val constant = flag("constant") ?: false
        val enabled = flag("enabled") ?: !(flag("disable") ?: false)
        val content = text("content").orEmpty()
        if (enabled && (content.isBlank() || (!constant && primary.isEmpty()))) {
            warnings += "第 ${index + 1} 项缺少正文或关键词，已跳过"
            return@mapIndexedNotNull null
        }
        val selective = flag("selective") == true && secondary.isNotEmpty()
        val expression = if (selective) {
            val secondaryExpression = when (number("selective_logic", "selectiveLogic") ?: 0) {
                1 -> "NOT (${secondary.expression("AND")})"
                2 -> "NOT (${secondary.expression("OR")})"
                3 -> secondary.expression("AND")
                else -> secondary.expression("OR")
            }
            "(${primary.expression("OR")}) AND ($secondaryExpression)"
        } else primary.expression("OR")
        val position = when (text("position")?.lowercase()) {
            "0", "before_char", "before_system", "before_system_prompt" -> InjectionPosition.BEFORE_SYSTEM_PROMPT
            "1", "after_char", "after_system", "after_system_prompt" -> InjectionPosition.AFTER_SYSTEM_PROMPT
            "2", "3", "before_example", "after_example", "top_of_chat" -> InjectionPosition.TOP_OF_CHAT
            "4", "at_depth", "depth" -> InjectionPosition.AT_DEPTH
            else -> { warnings += "第 ${index + 1} 项插入位置未识别，按系统提示词后处理"; InjectionPosition.AFTER_SYSTEM_PROMPT }
        }
        listOf("delay", "recursive", "preventRecursion", "excludeRecursion", "vectorized").forEach { key ->
            if ((entry[key] ?: extensions[key])?.toString()?.let { it !in listOf("false", "0", "null") } == true) warnings += "第 ${index + 1} 项的 $key 尚不支持，未应用"
        }
        listOf("useGroupScoring", "use_group_scoring", "matchWholeWords", "match_whole_words", "ignoreBudget", "ignore_budget", "delayUntilRecursion").forEach { key ->
            if (value(key)?.let { it.booleanOrNull == true || (it.intOrNull ?: 0) > 0 } == true) warnings += "第 ${index + 1} 项的 $key 尚不支持，未应用；请检查匹配与预算结果"
        }
        val group = text("group").orEmpty()
        if (group.contains(',')) warnings += "第 ${index + 1} 项属于多个互斥组，目前仅使用第一个组"
        val importedRole = when (text("role")?.lowercase()) {
            "2", "assistant" -> me.rerere.ai.core.MessageRole.ASSISTANT
            "1", "user", null -> me.rerere.ai.core.MessageRole.USER
            else -> { if (position == InjectionPosition.AT_DEPTH) warnings += "第 ${index + 1} 项独立消息角色暂不支持，按用户消息导入"; me.rerere.ai.core.MessageRole.USER }
        }
        val result = PromptInjection.RegexInjection(
            name = text("name", "comment")?.ifBlank { null } ?: primary.firstOrNull() ?: "条目 ${index + 1}",
            enabled = enabled,
            content = content, keywords = primary, keywordExpression = expression,
            constantActive = constant, position = position,
            priority = number("priority", "insertion_order", "order") ?: 100,
            injectDepth = number("depth") ?: 4,
            role = importedRole,
            scanMode = if (number("scan_depth", "scanDepth") == null) LorebookScanMode.INHERIT else LorebookScanMode.CUSTOM,
            scanDepth = number("scan_depth", "scanDepth") ?: (book["scan_depth"] as? JsonPrimitive)?.intOrNull ?: 4,
            useRegex = flag("use_regex", "useRegex") ?: false,
            caseSensitive = flag("case_sensitive", "caseSensitive") ?: false,
            triggerProbability = if (flag("useProbability", "use_probability") == true) number("probability") ?: 100 else 100,
            stickyTurns = (number("sticky") ?: 1).coerceAtLeast(1), cooldownTurns = number("cooldown") ?: 0,
            exclusiveGroup = group.substringBefore(',').trim(),
            groupOverride = flag("groupOverride", "group_override") ?: false,
            selectionWeight = if (group.isBlank() || flag("groupOverride", "group_override") == true) 0 else (number("groupWeight", "group_weight") ?: 100).coerceIn(0, 10000),
        )
        result.validationErrors(true).forEach { warnings += "${result.name}：$it" }
        result.copy(scanDepth = result.scanDepth.coerceIn(0, 1000), injectDepth = result.injectDepth.coerceIn(1, 1000),
            triggerProbability = result.triggerProbability.coerceIn(0, 100), stickyTurns = result.stickyTurns.coerceIn(1, 10000), cooldownTurns = result.cooldownTurns.coerceIn(0, 10000))
    }
    if ((book["recursive_scanning"] as? JsonPrimitive)?.booleanOrNull == true) warnings += "递归扫描尚不支持，未应用"
    return Lorebook(name = (book["name"] as? JsonPrimitive)?.contentOrNull?.ifBlank { null } ?: fallbackName,
        description = (book["description"] as? JsonPrimitive)?.contentOrNull.orEmpty(), entries = entries,
        tokenBudget = ((book["token_budget"] as? JsonPrimitive)?.intOrNull ?: 0).coerceIn(0, 1000000),
        defaultScanDepth = ((book["scan_depth"] as? JsonPrimitive)?.intOrNull ?: 4).coerceIn(0, 1000),
        importWarnings = warnings.distinct())
}
