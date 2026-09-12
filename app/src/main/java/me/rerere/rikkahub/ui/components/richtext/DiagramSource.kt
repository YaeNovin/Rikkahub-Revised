package me.rerere.rikkahub.ui.components.richtext

import kotlinx.serialization.json.*
import org.jsoup.Jsoup
import org.jsoup.parser.Parser

internal const val MAX_DIAGRAM_SOURCE_LENGTH = 128 * 1024

internal val LocalRichTextStreaming = androidx.compose.runtime.compositionLocalOf { false }

internal fun canRenderFinishedJsonFence(language: String, code: String, streaming: Boolean): Boolean {
    if (streaming || code.length > MAX_DIAGRAM_SOURCE_LENGTH) return false
    val declared = normalizeCodeFenceLanguage(language)
    if (declared !in GENERIC_JSON_LANGUAGES && declared !in setOf("geojson", "leaflet", "map", "vega", "vega-lite", "wavedrom", "waveform", "echarts", "chart", "railroad")) return false
    return resolveDiagramLanguage("json", code) in setOf("geojson", "vega", "wavedrom", "echarts", "railroad")
}

private val GENERIC_JSON_LANGUAGES = setOf("", "text", "plaintext", "json", "jsonc", "json5", "javascript", "js")
private val GEOJSON_GEOMETRIES = setOf("Point", "MultiPoint", "LineString", "MultiLineString", "Polygon", "MultiPolygon")

private fun JsonElement.isGeoJson(): Boolean {
    val value = this as? JsonObject ?: return false
    return when ((value["type"] as? JsonPrimitive)?.content) {
        "FeatureCollection" -> value["features"] is JsonArray
        "Feature" -> "geometry" in value
        "GeometryCollection" -> value["geometries"] is JsonArray
        in GEOJSON_GEOMETRIES -> value["coordinates"] is JsonArray
        else -> false
    }
}

/** Accept JSON and common comments/trailing commas/object literals, never script execution. */
internal fun parseDiagramData(source: String): JsonElement? {
    if (source.length > MAX_DIAGRAM_SOURCE_LENGTH) return null
    val text = source.trim().removePrefix("\uFEFF").trim()
    return runCatching { Json.parseToJsonElement(text) }.getOrElse {
        runCatching { DiagramLiteralParser(text, allowConstructors = false).data() }.getOrNull()
    }
}

internal fun normalizeDiagramData(source: String, renderer: InteractiveCodeRenderer): String {
    val data = parseDiagramData(source) ?: return source
    return if (renderer == InteractiveCodeRenderer.LEAFLET && data is JsonArray) {
        buildJsonObject { put("geoJson", data) }.toString()
    } else data.toString()
}

/** Only infer from generic code fences and recognizable, self-contained shapes.
 * The original language/source remains available for copying and source view. */
internal fun resolveDiagramLanguage(language: String, code: String): String {
    val declared = normalizeCodeFenceLanguage(language)
    if (code.length > MAX_DIAGRAM_SOURCE_LENGTH) return declared
    if (declared == "ebnf") return "railroad"
    if (declared !in GENERIC_JSON_LANGUAGES && declared != "xml") return declared
    val source = code.trim()
    if (declared in setOf("", "text", "plaintext", "xml") && source.startsWith("<")) {
        val document = Jsoup.parse(source, "", Parser.xmlParser())
        if (document.childrenSize() == 1 && document.child(0).tagName() == "score-partwise" &&
            source.trimEnd().endsWith("</score-partwise>")) return "musicxml"
        if (document.childrenSize() == 1 && document.child(0).tagName().equals("svg", true) &&
            Regex("</svg\\s*>\\s*$", RegexOption.IGNORE_CASE).containsMatchIn(source)) return "svg"
    }
    if (declared in GENERIC_JSON_LANGUAGES) {
        val data = parseDiagramData(source)
        if (data is JsonArray && data.isNotEmpty() && data.all { it.isGeoJson() }) return "geojson"
        val json = data as? JsonObject
        if (json != null) {
            if ((json["signal"] is JsonArray && json["signal"]!!.jsonArray.any { it is JsonObject && "wave" in it }) ||
                (json["reg"] is JsonArray && json["reg"]!!.jsonArray.any { it is JsonObject && "bits" in it })) return "wavedrom"
            val schema = (json["\$schema"] as? JsonPrimitive)?.content.orEmpty()
            if (schema.startsWith("https://vega.github.io/schema/") ||
                ("mark" in json && "encoding" in json && "data" in json) ||
                listOf("layer", "hconcat", "vconcat", "concat").any { json[it] is JsonArray && json[it]!!.jsonArray.isNotEmpty() && "data" in json }) return "vega"
            val type = (json["type"] as? JsonPrimitive)?.content
            if (json.isGeoJson() ||
                "geoJson" in json || "geojson" in json ||
                (json["markers"] is JsonArray && (json["markers"] as JsonArray).any { it is JsonObject && "lat" in it && "lng" in it }) ||
                (json["polylines"] is JsonArray && json["polylines"]!!.jsonArray.any { it is JsonObject && it["points"] is JsonArray })) return "geojson"
            if (json["series"] is JsonArray || (json["series"] is JsonObject && "type" in json["series"]!!.jsonObject) ||
                "geo" in json || (json["option"] as? JsonObject)?.containsKey("series") == true) return "echarts"
            if (type in setOf("sequence", "stack", "choice", "optional", "oneOrMore", "zeroOrMore") && json["items"] is JsonArray) return "railroad"
        }
    }
    if (declared in setOf("", "text", "plaintext", "javascript", "js")) {
        val start = source.replace(Regex("(?s)^\\s*(?:(?://[^\\n]*(?:\\n|$)|/\\*.*?\\*/)\\s*)*"), "")
        if (Regex("^(?:new\\s+)?(?:Diagram|Stack)\\s*\\(").containsMatchIn(start)) return "railroad"
        if (declared in setOf("", "text", "plaintext") && Regex("^(?:strict\\s+)?(?:digraph|graph)\\s+(?:[A-Za-z_][A-Za-z0-9_]*\\s*)?\\{").containsMatchIn(start)) return "dot"
        if (start.startsWith("echarts.registerMap") ||
            (Regex("^(?:(?:const|let|var)\\s+)?option\\s*=").containsMatchIn(start) &&
                Regex("\\b(?:series|geo|xAxis|radar)[\"']?\\s*:").containsMatchIn(start))) return "echarts"
        if (Regex("^(?:const|let|var)\\s+").containsMatchIn(start) && start.contains("echarts.registerMap")) {
            val option = runCatching { DiagramLiteralParser(start).chart()["option"] as? JsonObject }.getOrNull()
            if (option != null && ("series" in option || "geo" in option)) return "echarts"
        }
    }
    return declared
}

/** Parses literal data and the railroad constructor DSL; never executes JavaScript. */
internal class DiagramLiteralParser(private val source: String, private val allowConstructors: Boolean = true) {
    private var index = 0
    private var depth = 0
    private val bindings = mutableMapOf<String, JsonElement>()
    private fun whitespace() {
        while (index < source.length) when {
            source[index].isWhitespace() -> index++
            source.startsWith("//", index) -> index = source.indexOf('\n', index).takeIf { it >= 0 } ?: source.length
            source.startsWith("/*", index) -> { val end = source.indexOf("*/", index + 2); require(end >= 0); index = end + 2 }
            else -> return
        }
    }
    private fun take(value: String): Boolean { whitespace(); if (!source.startsWith(value, index)) return false; index += value.length; return true }
    private fun expect(value: String) { require(take(value)) { "需要 '$value'" } }
    private fun name(): String {
        whitespace()
        val match = Regex("[A-Za-z_$][A-Za-z0-9_$]*").find(source, index)
        require(match != null && match.range.first == index) { "无法识别的声明" }
        index += match.value.length
        return match.value
    }
    private fun string(): String {
        whitespace(); val quote = source[index++]
        val result = StringBuilder()
        while (index < source.length) {
            val c = source[index++]
            if (c == quote) return result.toString()
            if (c != '\\') { result.append(c); continue }
            require(index < source.length)
            when (val escaped = source[index++]) {
                'n' -> result.append('\n'); 'r' -> result.append('\r'); 't' -> result.append('\t')
                'u' -> { require(index + 4 <= source.length); result.append(source.substring(index, index + 4).toInt(16).toChar()); index += 4 }
                else -> result.append(escaped)
            }
        }
        error("字符串未闭合")
    }
    private fun value(): JsonElement {
        require(++depth <= 80) { "图形配置嵌套过深" }
        try {
            whitespace(); require(index < source.length) { "配置未完整提供" }
            if (source[index] in "\"'") return JsonPrimitive(string())
            if (take("{")) {
                val fields = linkedMapOf<String, JsonElement>()
                if (!take("}")) do {
                    whitespace(); val key = if (source.getOrNull(index) in listOf('"', '\'')) string() else name()
                    expect(":"); fields[key] = value()
                    if (take("}")) break
                    expect(",")
                    if (take("}")) break
                } while (true)
                return JsonObject(fields)
            }
            if (take("[")) {
                val elements = mutableListOf<JsonElement>()
                if (!take("]")) do {
                    elements += value()
                    if (take("]")) break
                    expect(",")
                    if (take("]")) break
                } while (true)
                return JsonArray(elements)
            }
            val number = Regex("-?(?:0|[1-9]\\d*)(?:\\.\\d+)?(?:[eE][+-]?\\d+)?").find(source, index)
            if (number != null && number.range.first == index) { index += number.value.length; return Json.parseToJsonElement(number.value) }
            var identifier = name()
            if (identifier == "new") identifier = name()
            if (take("(")) {
                require(allowConstructors) { "图形数据不能包含函数调用" }
                val args = mutableListOf<JsonElement>()
                if (!take(")")) do { args += value(); if (take(")")) break; expect(","); if (take(")")) break } while (true)
                return railroadCall(identifier, args)
            }
            return when (identifier) {
                "true" -> JsonPrimitive(true); "false" -> JsonPrimitive(false); "null" -> JsonNull
                else -> bindings[identifier] ?: error("缺少本代码块中的数据定义：$identifier")
            }
        } finally { depth-- }
    }
    private fun railroadCall(name: String, args: List<JsonElement>): JsonObject {
        val type = when (name) {
            "Diagram", "Sequence" -> "sequence"; "Choice" -> "choice"; "Optional" -> "optional"
            "Stack" -> "stack"
            "OneOrMore" -> "oneOrMore"; "ZeroOrMore" -> "zeroOrMore"; "Skip" -> "skip"
            "Terminal" -> "terminal"; "NonTerminal" -> "nonterminal"; "Comment" -> "comment"
            else -> error("不支持的图形构造器：$name")
        }
        if (type == "stack") require(args.isNotEmpty()) { "Stack 至少需要一个子节点" }
        return buildJsonObject {
            put("type", type)
            if (type in setOf("terminal", "nonterminal", "comment")) put("text", args.firstOrNull() ?: JsonPrimitive(""))
            else {
                put("items", JsonArray(when (type) { "choice" -> args.drop(1); "optional" -> args.take(1); else -> args }))
                if (type == "choice") put("normal", args.firstOrNull() ?: JsonPrimitive(0))
                if (type == "optional" && (args.getOrNull(1) as? JsonPrimitive)?.content == "skip") put("skip", true)
            }
        }
    }
    fun railroad(): JsonElement {
        val result = value()
        if (take(".")) { expect("addTo"); expect("("); expect(")") }
        take(";"); whitespace(); require(index == source.length) { "铁路图包含未支持的额外脚本" }
        return result
    }
    fun data(): JsonElement {
        val result = value()
        require(result is JsonObject || result is JsonArray) { "需要 JSON 对象或数组" }
        whitespace()
        require(index == source.length) { "图形数据包含额外脚本" }
        return result
    }
    fun chart(): JsonObject {
        val maps = linkedMapOf<String, JsonElement>()
        while (true) {
            whitespace(); if (index == source.length) break
            val identifier = name()
            if (identifier == "echarts") {
                expect("."); expect("registerMap"); expect("(")
                val mapName = value().jsonPrimitive.content; expect(","); maps[mapName] = mapData(); expect(")")
            } else {
                val key = if (identifier in setOf("const", "let", "var")) name() else identifier
                expect("="); bindings[key] = value()
            }
            take(";")
        }
        val option = bindings["option"] as? JsonObject ?: error("未找到完整的 option 图表配置")
        return buildJsonObject { put("option", option); put("maps", JsonObject(maps)) }
    }

    private fun mapData(): JsonElement {
        whitespace()
        val reference = Regex("[A-Za-z_$][A-Za-z0-9_$]*").find(source, index)
            ?.takeIf { it.range.first == index }?.value
        val builtin = when (reference?.lowercase()) {
            "chinageojson", "chinajson" -> "china"
            "worldgeojson", "worldjson" -> "world"
            else -> null
        }
        if (builtin != null && reference !in bindings) {
            index += requireNotNull(reference).length
            // This marker is consumed only by the map loader, never used as executable code.
            return buildJsonObject { put("rikkaBuiltinMap", builtin) }
        }
        return value()
    }
}

internal fun normalizeChartSource(source: String): String = runCatching {
    parseDiagramData(source) ?: DiagramLiteralParser(source).chart()
}.getOrElse { error -> buildJsonObject { put("rikkaRenderError", error.message ?: "图表配置不完整") } }.toString()
