package me.rerere.rikkahub.ui.components.richtext

import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.net.URLDecoder
import java.util.Base64

internal const val MAX_INLINE_SVG_BYTES = 512 * 1024
private const val MAX_LOGO_BYTES = 32 * 1024
private const val MAX_TOTAL_LOGO_BYTES = 128 * 1024

/** Shields uses SVG2 <image href="data:image/svg+xml;base64,..."> for vector logos.
 * AndroidSVG 1.4 sends these bytes to BitmapFactory, which cannot decode SVG.
 * Inline only bounded, embedded SVG data; never fetch remote images or resolve files. */
internal fun expandEmbeddedSvgImages(source: String): String {
    if (source.length > MAX_INLINE_SVG_BYTES || !source.contains("data:image/svg+xml", ignoreCase = true)) return source
    val document = Jsoup.parse(source, "", Parser.xmlParser())
    document.outputSettings().prettyPrint(false)
    var count = 0
    var bytesUsed = 0
    repeat(4) {
        var expanded = false
        document.getElementsByTag("image").toList().forEach { image ->
            if (count >= 32) return@forEach
            val uri = image.attr("href").ifBlank { image.attr("xlink:href") }
            val comma = uri.indexOf(',')
            if (comma < 0) return@forEach
            val metadata = uri.substring(0, comma).split(';')
            if (!metadata.first().equals("data:image/svg+xml", ignoreCase = true)) return@forEach
            val data = uri.substring(comma + 1)
            if (data.length > MAX_LOGO_BYTES * 4) return@forEach
            val xml = runCatching {
                if (metadata.any { it.equals("base64", ignoreCase = true) }) {
                    val bytes = Base64.getDecoder().decode(data.filterNot(Char::isWhitespace))
                    if (bytes.size > MAX_LOGO_BYTES) return@runCatching null
                    bytes.toString(Charsets.UTF_8)
                } else URLDecoder.decode(data.replace("+", "%2B"), "UTF-8")
            }.getOrNull() ?: return@forEach
            val byteCount = xml.toByteArray(Charsets.UTF_8).size
            if (byteCount > MAX_LOGO_BYTES || bytesUsed + byteCount > MAX_TOTAL_LOGO_BYTES) return@forEach
            val logoDocument = Jsoup.parse(xml, "", Parser.xmlParser())
            val logo = logoDocument.children().singleOrNull()?.takeIf { it.normalName() == "svg" }?.clone() ?: return@forEach
            // Keep definition references independent when several badges use the same logo.
            val ids = logo.getAllElements().filter { it.hasAttr("id") }.associate {
                it.id() to "rikka-logo-$count-${it.id()}"
            }
            logo.getAllElements().forEach { element ->
                element.attributes().toList().forEach { attribute ->
                    var value = attribute.value
                    ids.forEach { (old, new) ->
                        value = value.replace("url(#$old)", "url(#$new)")
                        if (attribute.key in setOf("href", "xlink:href") && value == "#$old") value = "#$new"
                    }
                    if (attribute.key == "id") value = ids[value] ?: value
                    element.attr(attribute.key, value)
                }
            }
            // The image element defines placement and size; the embedded SVG keeps its viewBox.
            image.attributes().forEach { attribute ->
                if (attribute.key !in setOf("href", "xlink:href", "xmlns", "xmlns:xlink")) {
                    logo.attr(attribute.key, attribute.value)
                }
            }
            image.replaceWith(logo)
            bytesUsed += byteCount
            count++
            expanded = true
        }
        if (!expanded) return if (count > 0) document.outerHtml() else source
    }
    return document.outerHtml()
}
