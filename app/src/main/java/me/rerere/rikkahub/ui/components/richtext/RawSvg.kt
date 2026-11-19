package me.rerere.rikkahub.ui.components.richtext

import java.util.Base64
import org.jsoup.nodes.Element

internal const val RAW_SVG_ATTRIBUTE = "data-rikka-svg-source"

internal data class RawSvgSource(val source: String, val complete: Boolean, val language: String = "svg")

internal fun rawSvgSource(element: Element): RawSvgSource? {
    if (!element.hasAttr(RAW_SVG_ATTRIBUTE)) return null
    return runCatching {
        RawSvgSource(String(Base64.getDecoder().decode(element.attr(RAW_SVG_ATTRIBUTE)), Charsets.UTF_8),
            element.attr("data-rikka-svg-complete") != "false",
            element.attr("data-rikka-source-language").takeIf { it == "xml" } ?: "svg")
    }.getOrNull()
}

/** Preserve SVG as one opaque document before Markdown can reinterpret blank
 * lines/indentation, normalize formulas, or lower-case foreign XML attributes. */
internal fun protectRawSvg(content: String): String {
    if ('<' !in content) return content
    val output = StringBuilder(content.length)
    var index = 0
    var fence: Char? = null
    var fenceLength = 0
    while (index < content.length) {
        if (index == 0 || content[index - 1] == '\n') {
            val end = content.indexOf('\n', index).let { if (it < 0) content.length else it + 1 }
            val line = content.substring(index, end)
            val marker = Regex("^(?: {0,3}> ?)* {0,3}(`{3,}|~{3,})(.*)$").find(line.trimEnd('\r', '\n'))
            if (marker != null) {
                val run = marker.groupValues[1]
                if (fence == null) { fence = run[0]; fenceLength = run.length }
                else if (run[0] == fence && run.length >= fenceLength && marker.groupValues[2].isBlank()) fence = null
                output.append(line); index = end; continue
            }
            if (fence != null || line.startsWith("    ") || line.startsWith('\t')) {
                output.append(line); index = end; continue
            }
        }
        if (content[index] == '`') {
            var end = index
            while (end < content.length && content[end] == '`') end++
            val marker = content.substring(index, end)
            val close = content.indexOf(marker, end)
            if (close >= 0) { output.append(content, index, close + marker.length); index = close + marker.length; continue }
        }
        val root = xmlRootAt(content, index)
        if (root != null) {
            val end = xmlDocumentEnd(content, root.first, root.second)
            val source = content.substring(index, end ?: content.length)
            output.append("<div $RAW_SVG_ATTRIBUTE=\"")
                .append(Base64.getEncoder().encodeToString(source.toByteArray(Charsets.UTF_8)))
                .append("\" data-rikka-svg-complete=\"").append(end != null).append('"')
            if (!root.second.equals("svg", true)) output.append(" data-rikka-source-language=\"xml\"")
            output.append("></div>")
            index = end ?: content.length
            continue
        }
        if (content[index] == '<') {
            val end = xmlTokenEnd(content, index)
            if (end != null) { output.append(content, index, end); index = end; continue }
        }
        output.append(content[index++])
    }
    return output.toString()
}

// No XML parser/entity resolution is used here: this only isolates source text.
private val xmlRootName = Regex("<([A-Za-z_][A-Za-z0-9_.:-]*)(?=[\\s/>])")
private val htmlRoots = ("html head body div span p pre code script style title meta link img a br hr " +
    "table thead tbody tr td th ul ol li section article header footer main nav aside details summary " +
    "h1 h2 h3 h4 h5 h6 blockquote strong b em i form input button video audio iframe canvas think").split(' ').toSet()

private fun xmlRootAt(text: String, start: Int): Pair<Int, String>? {
    if (text[start] != '<') return null
    var rootStart = start
    val declaration = text.startsWith("<?xml", start, ignoreCase = true)
    if (declaration) {
        rootStart = text.indexOf("?>", start + 5).takeIf { it >= 0 }?.plus(2) ?: return null
        while (rootStart < text.length) {
            while (text.getOrNull(rootStart)?.isWhitespace() == true) rootStart++
            if (text.startsWith("<!--", rootStart) || text.startsWith("<!DOCTYPE", rootStart, true)) {
                rootStart = xmlTokenEnd(text, rootStart) ?: return null
            } else break
        }
    }
    val match = xmlRootName.find(text, rootStart)?.takeIf { it.range.first == rootStart } ?: return null
    val name = match.groupValues[1]
    val lineStart = text.lastIndexOf('\n', start - 1) + 1
    val atLineStart = text.substring(lineStart, start).isBlank()
    return if (name.equals("svg", true) || declaration || atLineStart && name.lowercase() !in htmlRoots) rootStart to name else null
}

private fun xmlDocumentEnd(text: String, start: Int, rootName: String): Int? {
    var index = start
    var depth = 0
    val open = Regex("^<${Regex.escape(rootName)}(?:\\s|/?>)", RegexOption.IGNORE_CASE)
    val close = Regex("^</${Regex.escape(rootName)}\\s*>", RegexOption.IGNORE_CASE)
    while (index < text.length) {
        val tag = text.indexOf('<', index).takeIf { it >= 0 } ?: return null
        val end = xmlTokenEnd(text, tag) ?: return null
        val token = text.substring(tag, end)
        if (open.containsMatchIn(token)) {
            if (!token.trimEnd().endsWith("/>")) depth++
            else if (depth == 0) return end
        } else if (close.containsMatchIn(token)) {
            depth--
            if (depth == 0) return end
        }
        index = end
    }
    return null
}

private fun xmlTokenEnd(text: String, start: Int): Int? {
    val special = when {
        text.startsWith("<!--", start) -> "-->"
        text.startsWith("<![CDATA[", start) -> "]]>"
        else -> null
    }
    if (special != null) return text.indexOf(special, start + 2).takeIf { it >= 0 }?.plus(special.length)
    var quote: Char? = null
    var subsetDepth = 0
    for (index in start + 1 until text.length) {
        val c = text[index]
        if (quote != null) { if (c == quote) quote = null }
        else if (c == '\'' || c == '"') quote = c
        else if (c == '[') subsetDepth++
        else if (c == ']') subsetDepth = (subsetDepth - 1).coerceAtLeast(0)
        else if (c == '>' && subsetDepth == 0) return index + 1
    }
    return null
}
