package me.rerere.rikkahub.data.render

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.text.Normalizer

/** Persisted with display settings; the same policy is applied to fences and tool discovery. */
@Serializable
data class LinguisticRenderingSettings(
    val ipa: Boolean = true,
    val ruby: Boolean = true,
    val gloss: Boolean = true,
    val syntaxTree: Boolean = true,
    val dependency: Boolean = true,
    val literary: Boolean = true,
) {
    fun enabled(id: String): Boolean = when (id) {
        "ipa" -> ipa
        "ruby_annotation" -> ruby
        "gloss" -> gloss
        "syntax_tree" -> syntaxTree
        "dependency" -> dependency
        "annotated_text" -> literary
        else -> true
    }
}

internal const val LINGUISTIC_SCHEMA_VERSION = 1
internal const val MAX_LINGUISTIC_SOURCE_CHARS = 32768
internal val linguisticRendererIds = setOf("ipa", "ruby_annotation", "gloss", "syntax_tree", "dependency")

/** This is a transport schema, not an automatic linguistic analyser. Never evaluates model code. */
internal fun compileLinguisticSpec(renderer: String, source: String): JsonObject {
    require(renderer in linguisticRendererIds) { "Unknown linguistic renderer" }
    require(source.isNotBlank() && source.length <= MAX_LINGUISTIC_SOURCE_CHARS) { "Empty or oversized linguistic source (max 32768 characters)" }
    if (renderer == "dependency") return compileConllu(source)
    // Bound nesting before the JSON parser/recursive tree visitor sees an adversarial input.
    var depth = 0
    var quoted = false
    var escaped = false
    source.forEach { c ->
        if (quoted) {
            if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false
        } else when (c) {
            '"' -> quoted = true
            '{', '[' -> { depth++; require(depth <= 48) { "JSON nesting exceeds 48" } }
            '}', ']' -> depth--
        }
    }
    val obj = Json.parseToJsonElement(source) as? JsonObject ?: error("Expected a JSON object")
    require(obj["version"] == JsonPrimitive(1)) { "version must be 1" }
    val common = setOf("version", "title", "language", "dir", "id", "source")
    obj.text("title", 160, false); obj.text("id", 80, false); obj.text("source", 600, false)
    obj.text("language", 48, false)?.let { require(Regex("[A-Za-z0-9-]{1,48}").matches(it)) { "Invalid language tag" } }
    obj.text("dir", 4, false)?.let { require(it in setOf("ltr", "rtl", "auto")) { "dir must be ltr, rtl or auto" } }
    when (renderer) {
        "ipa" -> {
            obj.only(common + setOf("text", "notation"))
            obj.text("text", 4000)
            obj.text("notation", 16, false)?.let { require(it in setOf("phonemic", "phonetic", "plain")) { "Unknown IPA notation" } }
        }
        "ruby_annotation" -> {
            obj.only(common + setOf("segments", "translation", "writingMode", "rubyPosition"))
            obj.text("writingMode", 16, false)?.let { require(it in setOf("horizontal-tb", "vertical-rl", "vertical-lr")) { "Invalid writing mode" } }
            obj.text("rubyPosition", 16, false)?.let { require(it in setOf("over", "under", "inter-character")) { "Invalid ruby position" } }
            obj.text("translation", 1200, false)
            obj.array("segments", 128).forEach { item ->
                val segment = item as? JsonObject ?: error("Each ruby segment must be an object")
                segment.only(setOf("base", "reading"))
                segment.text("base", 120); segment.text("reading", 120, false)
            }
        }
        "gloss" -> {
            obj.only(common + setOf("words", "translation", "legend"))
            obj.text("translation", 2000)
            val words = obj.array("words", 128).map { it as? JsonObject ?: error("Each gloss word must be an object") }
            val segmented = words.any { "morphemes" in it }
            words.forEach { word ->
                word.only(setOf("text", "morphemes", "gloss"))
                val text = word.text("text", 120)!!
                val morphemes = word.text("morphemes", 160, false)
                val gloss = word.text("gloss", 160)!!
                require(!text.any(Char::isWhitespace) && !gloss.any(Char::isWhitespace)) { "Use one object per word, not whitespace alignment" }
                require(!segmented || morphemes != null) { "Provide morphemes for every word when using the segmentation row" }
                val boundaries = { s: String -> s.filter { it == '-' || it == '=' } }
                require(boundaries(morphemes ?: text) == boundaries(gloss)) { "Leipzig rule 2: matching - and = boundaries required in source and gloss" }
            }
            (obj["legend"] as? JsonObject)?.let { legend ->
                require(legend.size <= 48) { "At most 48 gloss abbreviations" }
                legend.forEach { (key, value) -> require(key.length in 1..24 && value is JsonPrimitive && value.isString && value.content.length in 1..160) { "Invalid gloss legend" } }
            } ?: require("legend" !in obj) { "legend must be an object" }
        }
        "syntax_tree" -> {
            obj.only(common + setOf("root"))
            var nodes = 0
            fun visit(element: JsonElement, level: Int) {
                require(++nodes <= 128 && level <= 16) { "Tree limit: 128 nodes, depth 16" }
                val node = element as? JsonObject ?: error("Tree node must be an object")
                node.only(setOf("label", "children")); node.text("label", 80)
                if ("children" in node) node.array("children", 32).forEach { visit(it, level + 1) }
            }
            visit(obj["root"] ?: error("Missing root"), 1)
        }
    }
    return buildJsonObject {
        obj.forEach { (k, v) -> put(k, v) }
        put("renderer", renderer)
    }
}

private fun JsonObject.only(fields: Set<String>) {
    require(keys.all { it in fields }) { "Unknown fields: ${(keys - fields).joinToString()}" }
}

private fun JsonObject.text(name: String, max: Int, required: Boolean = true): String? {
    val value = get(name) ?: return if (required) error("Missing $name") else null
    require(value is JsonPrimitive && value.isString) { "$name must be a string" }
    val text = value.content
    require(text.isNotBlank() && text.length <= max && text.none { it.code < 32 && it !in "\n\t\r" }) { "$name must be nonempty text, at most $max characters" }
    return text
}

private fun JsonObject.array(name: String, max: Int): JsonArray {
    val array = get(name) as? JsonArray ?: error("$name must be an array")
    require(array.size in 1..max) { "$name must contain 1..$max items" }
    return array
}

/** CoNLL-U: retain all ten columns, comments, multiword ranges and enhanced empty nodes. */
private fun compileConllu(raw: String): JsonObject {
    val source = raw.replace("\r\n", "\n")
    require(Normalizer.isNormalized(source, Normalizer.Form.NFC)) { "CoNLL-U must use NFC Unicode" }
    val blocks = source.trim().split(Regex("\n[\\t ]*\n+")).filter(String::isNotBlank)
    require(blocks.size in 1..8) { "CoNLL-U limit: 1..8 sentences" }
    val sentences = blocks.map { block ->
        val comments = mutableListOf<String>()
        val rows = mutableListOf<List<String>>()
        block.lineSequence().forEach { line ->
            if (line.startsWith('#')) comments += line else {
                val fields = line.split('\t')
                require(fields.size == 10 && fields.all { it.isNotEmpty() && it.length <= 600 }) { "CoNLL-U requires exactly ten nonempty TAB-separated fields" }
                require(Regex("(?:[1-9][0-9]*(?:[-.][1-9][0-9]*)?|0\\.[1-9][0-9]*)").matches(fields[0])) { "Invalid CoNLL-U ID" }
                rows += fields
            }
        }
        require(rows.size in 1..100 && comments.size <= 32) { "Sentence limit: 100 rows, 32 comments" }
        val ids = rows.map { it[0] }
        require(ids.distinct().size == ids.size) { "Duplicate token ID" }
        val words = rows.filter { '-' !in it[0] && '.' !in it[0] }
        require(words.size in 1..80 && words.map { it[0].toInt() } == (1..words.size).toList()) { "Word IDs must be sequential from 1 (max 80)" }
        require(words.count { it[6] == "0" } == 1) { "A basic UD tree requires exactly one root" }
        val heads = words.associate { row ->
            val head = row[6].toIntOrNull() ?: error("Basic words require an integer HEAD")
            require(head.toString() == row[6]) { "HEAD must be canonical integer notation" }
            require(head in 0..words.size && head.toString() != row[0]) { "HEAD must reference ROOT or a different word" }
            require((head == 0) == (row[7] == "root") && Regex("[a-z][a-z0-9]*(?::[a-z0-9_]+)*").matches(row[7])) { "Invalid DEPREL or root relation" }
            row[0] to head.toString()
        }
        words.forEach { row ->
            val seen = mutableSetOf<String>()
            var id = row[0]
            while (id != "0") { require(seen.add(id)) { "Cycle in basic dependencies" }; id = heads.getValue(id) }
        }
        val graphIds = rows.filter { '-' !in it[0] }.map { it[0] }.toSet() + "0"
        val covered = mutableSetOf<Int>()
        val emptyCounts = mutableMapOf<Int, Int>()
        var previousWord = 0
        val arcs = mutableListOf<JsonObject>()
        rows.forEach { row ->
            val id = row[0]
            when {
                '-' in id -> {
                    val (start, end) = id.split('-').map(String::toInt)
                    require(start == previousWord + 1 && end > start && end <= words.size && (start..end).all(covered::add)) { "Invalid, misplaced or overlapping multiword range" }
                    require((2..8).all { row[it] == "_" || (it == 5 && row[it] == "Typo=Yes") }) { "Multiword rows only have FORM and MISC" }
                }
                '.' in id -> {
                    val (base, suffix) = id.split('.').map(String::toInt)
                    val next = (emptyCounts[base] ?: 0) + 1
                    require(base == previousWord && suffix == next && row[6] == "_" && row[7] == "_" && row[8] != "_") { "Invalid empty-node position, suffix, HEAD, DEPREL or DEPS" }
                    emptyCounts[base] = next
                }
                else -> {
                    previousWord = id.toInt()
                    arcs += buildJsonObject { put("from", row[6]); put("to", id); put("label", row[7]); put("enhanced", false) }
                }
            }
            if (row[8] != "_") row[8].split('|').forEach { dep ->
                val head = dep.substringBefore(':'); val label = dep.substringAfter(':', "")
                require(head in graphIds && head != id && Regex("[a-z][a-z0-9]*(?::[a-z0-9_]+)*").matches(label)) { "Invalid enhanced dependency" }
                if (head != row[6] || label != row[7]) arcs += buildJsonObject {
                    put("from", head); put("to", id); put("label", label); put("enhanced", true)
                }
            }
        }
        require(arcs.size <= 240) { "Dependency limit: 240 arcs per sentence" }
        buildJsonObject {
            put("comments", JsonArray(comments.map(::JsonPrimitive)))
            put("rows", JsonArray(rows.map { JsonArray(it.map(::JsonPrimitive)) }))
            put("arcs", JsonArray(arcs))
        }
    }
    return buildJsonObject { put("version", LINGUISTIC_SCHEMA_VERSION); put("renderer", "dependency"); put("sentences", JsonArray(sentences)) }
}
