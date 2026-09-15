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
internal fun decodeTavernLorebook(book: JsonObject, fallbackName: String, sourceFormat: LorebookSourceFormat = LorebookSourceFormat.SILLY_TAVERN): Lorebook {
    val warnings = mutableListOf<String>()
    val raw = when (val entries = book["entries"]) {
        is JsonArray -> entries.toList()
        is JsonObject -> entries.map { (key, value) -> if (value is JsonObject && "uid" !in value) JsonObject(value + ("uid" to JsonPrimitive(key))) else value }
        else -> throw IllegalArgumentException("世界书缺少有效的 entries 列表")
    }
    require(raw.size <= 10000) { "世界书最多支持 10000 个条目" }
    fun bookValue(vararg keys: String): JsonPrimitive? = keys.firstNotNullOfOrNull { key -> (book[key] ?: (book["extensions"] as? JsonObject)?.get(key)) as? JsonPrimitive }
    fun bookFlag(vararg keys: String): Boolean? = bookValue(*keys)?.booleanOrNull
        ?: bookValue(*keys)?.contentOrNull?.trim()?.lowercase()?.let { value ->
            when (value) { "true", "1", "yes", "on" -> true; "false", "0", "no", "off" -> false; else -> null }
        }
    val entries = raw.mapIndexedNotNull { index, element ->
        val entry = element as? JsonObject ?: run { warnings += "第 ${index + 1} 项不是有效条目，已跳过"; return@mapIndexedNotNull null }
        val extensions = entry["extensions"] as? JsonObject ?: JsonObject(emptyMap())
        fun value(vararg keys: String): JsonPrimitive? = keys.firstNotNullOfOrNull { key ->
            ((entry[key]?.takeUnless { it is JsonNull } ?: extensions[key]) as? JsonPrimitive)?.takeUnless { it is JsonNull }
        }
        fun number(vararg keys: String) = value(*keys)?.intOrNull
        fun flag(vararg keys: String) = value(*keys)?.booleanOrNull ?: value(*keys)?.contentOrNull?.trim()?.lowercase()?.let { when (it) { "true", "1", "yes", "on" -> true; "false", "0", "no", "off" -> false; else -> null } }
        fun text(vararg keys: String) = value(*keys)?.contentOrNull
        fun words(vararg keys: String): List<String> {
            fun normalizeTerm(value: String): String = value
                .filterNot { it.isISOControl() && it != '\t' }
                .replace('\uFFFD'.toString(), "")
                .trim()

            // Older Tavern exports sometimes serialize a key array as one
            // whitespace/comma-delimited string. Tokenize that form without
            // splitting quoted phrases or `/regex/flags` literals (commas and
            // spaces inside those constructs are part of the expression).
            fun findLiteralEnd(value: String, start: Int): Int {
                if (start >= value.length || value[start] != '/') return -1
                var escaped = false
                var inClass = false
                for (index in start + 1 until value.length) {
                    val ch = value[index]
                    if (escaped) { escaped = false; continue }
                    if (ch == '\\') { escaped = true; continue }
                    if (ch == '[') { inClass = true; continue }
                    if (ch == ']' && inClass) { inClass = false; continue }
                    if (ch == '/' && !inClass) return index
                }
                return -1
            }
            fun decodeQuoted(raw: String): String = runCatching {
                ExportSerializer.DefaultJson.parseToJsonElement("\"$raw\"").jsonPrimitive.content
            }.getOrDefault(raw)
            fun decodeOrList(content: String): List<String> {
                val result = mutableListOf<String>()
                var cursor = 0
                while (cursor < content.length) {
                    val quote = content.indexOf('"', cursor)
                    if (quote < 0) break
                    cursor = quote + 1
                    val escaped = StringBuilder()
                    var slash = false
                    var closed = false
                    while (cursor < content.length) {
                        val ch = content[cursor++]
                        if (slash) { escaped.append(ch); slash = false; continue }
                        if (ch == '\\') { escaped.append(ch); slash = true; continue }
                        if (ch == '"') { closed = true; break }
                        escaped.append(ch)
                    }
                    if (!closed) return emptyList()
                    result += decodeQuoted(escaped.toString())
                }
                return result
            }
            fun tokenizeLegacy(content: String): List<String> {
                val result = mutableListOf<String>()
                var cursor = 0
                while (cursor < content.length) {
                    while (cursor < content.length && (content[cursor].isWhitespace() || content[cursor] == ',' || content[cursor] == ';')) cursor++
                    if (cursor >= content.length) break
                    val start = cursor
                    when (content[cursor]) {
                        '"' -> {
                            cursor++
                            val quotedStart = cursor
                            var escaped = false
                            var closed = false
                            while (cursor < content.length) {
                                val ch = content[cursor++]
                                if (escaped) { escaped = false; continue }
                                if (ch == '\\') { escaped = true; continue }
                                if (ch == '"') { closed = true; break }
                            }
                            if (!closed) {
                                // Keep malformed input visible to the editor instead
                                // of silently dropping its final character.
                                result += content.substring(start)
                                break
                            }
                            val end = (cursor - 1).coerceAtLeast(quotedStart)
                            result += decodeQuoted(content.substring(quotedStart, end))
                        }
                        '/' -> {
                            val end = findLiteralEnd(content, cursor)
                            if (end > cursor) {
                                cursor = end + 1
                                while (cursor < content.length && content[cursor].isLetter()) cursor++
                                result += content.substring(start, cursor)
                            } else {
                                while (cursor < content.length && !content[cursor].isWhitespace() && content[cursor] != ',' && content[cursor] != ';') cursor++
                                result += content.substring(start, cursor)
                            }
                        }
                        else -> {
                            while (cursor < content.length && !content[cursor].isWhitespace() && content[cursor] != ',' && content[cursor] != ';') cursor++
                            result += content.substring(start, cursor)
                        }
                    }
                }
                return result
            }
            fun decode(value: JsonElement, splitString: Boolean = true): List<String> = when (value) {
                // Array elements are already term boundaries. In particular,
                // keep a phrase such as ["alpha beta"] intact; only legacy
                // scalar strings use whitespace/comma tokenization.
                is JsonArray -> value.flatMap { decode(it, splitString = false) }
                is JsonPrimitive -> {
                    val content = value.contentOrNull.orEmpty().trim()
                    if (content.startsWith("[") && content.endsWith("]")) {
                        runCatching { ExportSerializer.DefaultJson.parseToJsonElement(content) }.getOrNull()?.let(::decode)
                            ?: tokenizeLegacy(content.removePrefix("[").removeSuffix("]"))
                    } else if (content.startsWith("OR ", ignoreCase = true) && content.indexOf('"') >= 0) {
                        val terms = decodeOrList(content)
                        if (terms.isNotEmpty()) terms else listOf(content)
                    } else if (splitString) tokenizeLegacy(content) else listOf(content)
                }
                else -> emptyList()
            }
            return keys.asSequence().mapNotNull { key -> entry[key] ?: extensions[key] }.firstOrNull()?.let(::decode)
                .orEmpty().map(::normalizeTerm).filter(String::isNotBlank).distinct()
        }
        fun triggers(vararg keys: String): Set<LorebookGenerationTrigger> {
            val raw = keys.asSequence().mapNotNull { key -> entry[key] ?: extensions[key] }.firstOrNull() ?: return emptySet()
            val values = when (raw) {
                is JsonArray -> raw.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                is JsonPrimitive -> raw.contentOrNull.orEmpty().split(',', ' ', ';')
                else -> emptyList()
            }
            return values.mapNotNull { value ->
                when (value.trim().lowercase()) {
                    "normal", "generate" -> LorebookGenerationTrigger.NORMAL
                    "continue" -> LorebookGenerationTrigger.CONTINUE
                    "impersonate" -> LorebookGenerationTrigger.IMPERSONATE
                    "swipe" -> LorebookGenerationTrigger.SWIPE
                    "regenerate", "regeneration" -> LorebookGenerationTrigger.REGENERATE
                    "quiet", "background" -> LorebookGenerationTrigger.QUIET
                    else -> null
                }
            }.toSet()
        }
        val primary = words("keys", "key")
        val secondary = words("secondary_keys", "keysecondary")
        fun List<String>.expression(join: String) = joinToString(" $join ") { quoteLorebookKeyword(it) }
        val useRegex = flag("use_regex", "useRegex") ?: false
        val v3Regex = sourceFormat == LorebookSourceFormat.CHARACTER_CARD_V3 && useRegex
        val constant = !v3Regex && (flag("constant") ?: false)
        val enabled = flag("enabled") ?: !(flag("disable") ?: false)
        val content = text("content").orEmpty()
        if (enabled && (content.isBlank() || (!constant && primary.isEmpty()))) warnings += "第 ${index + 1} 项缺少正文或关键词，已保留，请检查触发条件"
        val selective = !v3Regex && flag("selective") == true && secondary.isNotEmpty()
        val expression = if (selective) {
            val secondaryExpression = when (number("selective_logic", "selectiveLogic") ?: 0) {
                1 -> "NOT (${secondary.expression("AND")})"
                2 -> "NOT (${secondary.expression("OR")})"
                3 -> secondary.expression("AND")
                else -> secondary.expression("OR")
            }
            "(${primary.expression("OR")}) AND ($secondaryExpression)"
        } else ""
        val sourcePosition = text("position")?.lowercase()
        var unsupportedPosition: String? = null
        val position = when (sourcePosition) {
            "0", "before_char", "before_system", "before_system_prompt" -> InjectionPosition.BEFORE_SYSTEM_PROMPT
            "1", "after_char", "after_system", "after_system_prompt" -> InjectionPosition.AFTER_SYSTEM_PROMPT
            "2", "top_of_an", "top_of_author_note", "author_note_top" -> InjectionPosition.AFTER_SYSTEM_PROMPT
            "3", "bottom_of_an", "bottom_of_author_note", "author_note_bottom" -> InjectionPosition.AFTER_SYSTEM_PROMPT
            "4", "at_depth", "depth" -> InjectionPosition.AT_DEPTH
            "5", "top_of_example_messages", "example_top", "top_of_examples" -> InjectionPosition.TOP_OF_CHAT
            "6", "bottom_of_example_messages", "example_bottom", "bottom_of_examples" -> InjectionPosition.TOP_OF_CHAT
            "7", "outlet" -> InjectionPosition.OUTLET
            null -> InjectionPosition.AFTER_SYSTEM_PROMPT
            else -> {
                unsupportedPosition = sourcePosition
                warnings += "第 ${index + 1} 项位置 $sourcePosition 暂无等价插入点，保留原值并暂停该条目，请在编辑器映射位置"
                InjectionPosition.AFTER_SYSTEM_PROMPT
            }
        }
        val group = text("group").orEmpty()
        val groups = group.split(',', '，').map(String::trim).filter(String::isNotBlank).distinct()
        if (flag("vectorized") == true) warnings += "第 ${index + 1} 项启用向量触发；需在来源与绑定页显式开启语义匹配并配置向量模型"
        if (!text("automationId", "automation_id").isNullOrBlank()) warnings += "第 ${index + 1} 项包含 automationId；当前仅保留标记，未执行 STscript 自动化"
        val importedRole = when (text("role")?.lowercase()) {
            "0", "system" -> me.rerere.ai.core.MessageRole.SYSTEM
            "2", "assistant" -> me.rerere.ai.core.MessageRole.ASSISTANT
            "1", "user" -> me.rerere.ai.core.MessageRole.USER
            null -> if (sourceFormat == LorebookSourceFormat.SILLY_TAVERN) me.rerere.ai.core.MessageRole.SYSTEM else me.rerere.ai.core.MessageRole.USER
            else -> { if (position == InjectionPosition.AT_DEPTH) warnings += "第 ${index + 1} 项独立消息角色暂不支持，按用户消息导入"; me.rerere.ai.core.MessageRole.USER }
        }
        val result = PromptInjection.RegexInjection(
            name = text("name", "comment")?.ifBlank { null } ?: primary.firstOrNull() ?: "条目 ${index + 1}",
            enabled = enabled,
            content = content, keywords = primary, keywordExpression = expression,
            constantActive = constant, position = position,
            priority = number("priority") ?: number("order", "insertion_order") ?: 100,
            insertionOrder = number("insertion_order", "order") ?: 100,
            injectDepth = number("depth") ?: 4,
            role = importedRole,
            scanMode = if (number("scan_depth", "scanDepth") == null) LorebookScanMode.INHERIT else LorebookScanMode.CUSTOM,
            scanDepth = number("scan_depth", "scanDepth") ?: (book["scan_depth"] as? JsonPrimitive)?.intOrNull ?: 4,
            useRegex = useRegex,
            caseSensitive = flag("case_sensitive", "caseSensitive") ?: bookFlag("case_sensitive", "caseSensitive") ?: false,
            triggerProbability = if (flag("useProbability", "use_probability") == true) number("probability") ?: 100 else 100,
            stickyTurns = number("sticky") ?: 0, cooldownTurns = number("cooldown") ?: 0,
            timingUnit = LorebookTimingUnit.MESSAGES,
            delayMessages = (number("delay") ?: 0).coerceIn(0, 10000),
            matchWholeWords = flag("matchWholeWords", "match_whole_words") ?: bookFlag("matchWholeWords", "match_whole_words") ?: false,
            // The live content field already preserves decorators. Do not retain a hidden old
            // copy of the prompt when the user edits/removes that text later.
            sourceFormat = sourceFormat, sourceData = JsonObject(entry - "content"), unsupportedPosition = unsupportedPosition,
            preventRecursion = flag("preventRecursion", "prevent_recursion") ?: false,
            excludeRecursion = flag("excludeRecursion", "exclude_recursion") ?: false,
            delayUntilRecursion = (flag("delayUntilRecursion", "delay_until_recursion") ?: false) || (number("delayUntilRecursion", "delay_until_recursion") ?: 0) > 0,
            recursionLevel = (number("recursionLevel", "recursion_level") ?: number("delayUntilRecursion", "delay_until_recursion") ?: 0).coerceIn(0, 1000),
            useGroupScoring = flag("useGroupScoring", "use_group_scoring") ?: false,
            outletName = text("outlet", "outlet_name").orEmpty().trim(),
            exclusiveGroup = groups.firstOrNull().orEmpty(),
            inclusionGroups = groups,
            groupOverride = flag("groupOverride", "group_override") ?: false,
            prioritizeInclusion = flag("prioritizeInclusion", "prioritize_inclusion") ?: false,
            ignoreBudget = flag("ignoreBudget", "ignore_budget") ?: false,
            selectionWeight = if (groups.isEmpty() || flag("groupOverride", "group_override") == true || flag("prioritizeInclusion", "prioritize_inclusion") == true) 0 else (number("groupWeight", "group_weight") ?: 100).coerceIn(0, 10000),
            characterFilter = words("character_filter", "characterFilter", "character_names", "characterNames"),
            characterFilterExclude = flag("character_filter_exclude", "characterFilterExclude", "character_filter_exclude_mode") ?: false,
            characterFilterTags = words("character_filter_tags", "characterFilterTags", "character_tags", "characterTags"),
            vectorized = flag("vectorized") ?: false,
            additionalMatchingSources = setOf("matchCharacterDescription", "matchCharacterPersonality", "matchScenario", "matchPersonaDescription", "matchCharacterDepthPrompt", "matchCreatorNotes").filter { flag(it) == true }.toSet(),
            generationTriggers = triggers("triggers", "generationTriggers", "generation_types", "generationTypes"),
        )
        result.validationErrors(true).forEach { warnings += "${result.name}：$it" }
        if (sourceFormat == LorebookSourceFormat.CHARACTER_CARD_V3 && content.lineSequence().any { it.trim().startsWith("@@") }) warnings += "${result.name}：V3 装饰器已解析基础行为；未知装饰器将被忽略"
        result.copy(scanDepth = result.scanDepth.coerceIn(0, 1000), injectDepth = result.injectDepth.coerceIn(0, 1000),
            triggerProbability = result.triggerProbability.coerceIn(0, 100), stickyTurns = result.stickyTurns.coerceIn(0, 10000), cooldownTurns = result.cooldownTurns.coerceIn(0, 10000))
    }
    fun bookNumber(vararg keys: String): Int? = bookValue(*keys)?.intOrNull
    return Lorebook(name = (book["name"] as? JsonPrimitive)?.contentOrNull?.ifBlank { null } ?: fallbackName,
        description = (book["description"] as? JsonPrimitive)?.contentOrNull.orEmpty(), entries = entries,
        tokenBudget = ((book["token_budget"] as? JsonPrimitive)?.intOrNull ?: 0).coerceIn(0, 1000000),
        defaultScanDepth = ((book["scan_depth"] as? JsonPrimitive)?.intOrNull ?: 4).coerceIn(0, 1000),
        importWarnings = warnings.distinct(), sourceFormat = sourceFormat, sourceData = JsonObject(book - "entries"),
        recursiveScanning = bookFlag("recursive_scanning", "recursiveScanning", "recursive") ?: false,
        maxRecursionSteps = (bookNumber("max_recursion_steps", "maxRecursionSteps") ?: 0).coerceIn(0, 1000),
        minActivations = (bookNumber("min_activations", "minActivations") ?: 0).coerceIn(0, 10000),
        maxScanDepth = (bookNumber("max_depth", "maxDepth") ?: 0).coerceIn(0, 1000),
        includeNames = bookFlag("include_names", "includeNames") ?: false,
        useGroupScoring = bookFlag("use_group_scoring", "useGroupScoring") ?: false,
        defaultCaseSensitive = bookFlag("case_sensitive", "caseSensitive") ?: false,
        defaultMatchWholeWords = bookFlag("match_whole_words", "matchWholeWords") ?: false,
        alertOnOverflow = bookFlag("alert_on_overflow", "alertOnOverflow") ?: false,
        sourceScope = if (sourceFormat == LorebookSourceFormat.CHARACTER_CARD_V2 || sourceFormat == LorebookSourceFormat.CHARACTER_CARD_V3) LorebookSourceScope.CHARACTER else LorebookSourceScope.GLOBAL)
}
