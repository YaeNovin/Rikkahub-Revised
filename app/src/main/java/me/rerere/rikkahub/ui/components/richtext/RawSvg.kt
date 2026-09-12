package me.rerere.rikkahub.ui.components.richtext

import java.util.Base64
import org.jsoup.nodes.Element

internal const val RAW_SVG_ATTRIBUTE = "data-rikka-svg-source"

internal data class RawSvgSource(val source: String, val complete: Boolean)

internal fun rawSvgSource(element: Element): RawSvgSource? {
    if (!element.hasAttr(RAW_SVG_ATTRIBUTE)) return null
    return runCatching {
        RawSvgSource(String(Base64.getDecoder().decode(element.attr(RAW_SVG_ATTRIBUTE)), Charsets.UTF_8),
            element.attr("data-rikka-svg-complete") != "false")
    }.getOrNull()
}

/** Preserve SVG as one opaque document before Markdown can reinterpret blank
 * lines/indentation, normalize formulas, or lower-case foreign XML attributes. */
internal fun protectRawSvg(content: String): String {
    if (!content.contains("<svg", ignoreCase = true)) return content
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
        if (content.startsWith("<svg", index, ignoreCase = true) &&
            content.getOrNull(index + 4).let { it == '>' || it == '/' || it?.isWhitespace() == true }) {
            val end = svgDocumentEnd(content, index)
            val source = content.substring(index, end ?: content.length)
            output.append("<div $RAW_SVG_ATTRIBUTE=\"")
                .append(Base64.getEncoder().encodeToString(source.toByteArray(Charsets.UTF_8)))
                .append("\" data-rikka-svg-complete=\"").append(end != null).append("\"></div>")
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

private fun svgDocumentEnd(text: String, start: Int): Int? {
    var index = start
    var depth = 0
    while (index < text.length) {
        val tag = text.indexOf('<', index).takeIf { it >= 0 } ?: return null
        val end = xmlTokenEnd(text, tag) ?: return null
        val token = text.substring(tag, end)
        if (Regex("^<svg(?:\\s|/?>)", RegexOption.IGNORE_CASE).containsMatchIn(token)) {
            if (!token.trimEnd().endsWith("/>")) depth++
            else if (depth == 0) return end
        } else if (Regex("^</svg\\s*>", RegexOption.IGNORE_CASE).containsMatchIn(token)) {
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
    for (index in start + 1 until text.length) {
        val c = text[index]
        if (quote != null) { if (c == quote) quote = null }
        else if (c == '\'' || c == '"') quote = c
        else if (c == '>') return index + 1
    }
    return null
}
