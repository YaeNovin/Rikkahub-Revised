package me.rerere.rikkahub.ui.components.richtext

import android.content.Context
import androidx.compose.material3.ColorScheme
import me.rerere.rikkahub.utils.base64Encode
import me.rerere.rikkahub.utils.toCssHex
import me.rerere.rikkahub.utils.escapeHtml
import me.rerere.rikkahub.data.render.LinguisticRenderingSettings
import kotlinx.serialization.json.*
import org.jsoup.Jsoup

/**
 * Build HTML page for markdown preview with support for:
 * - Offline Markdown rendering via markdown-it
 * - LaTeX math via KaTeX
 * - Mermaid diagrams
 * - Syntax highlighting via highlight.js
 */
fun buildMarkdownPreviewHtml(context: Context, markdown: String, colorScheme: ColorScheme,
    linguistic: LinguisticRenderingSettings = LinguisticRenderingSettings(), math: Boolean = true): String {
    val htmlTemplate = context.assets.open("html/mark.html").bufferedReader().use { it.readText() }
    return buildMarkdownPreviewHtml(htmlTemplate, markdown, colorScheme, linguistic, math)
}

internal fun buildMarkdownPreviewHtml(htmlTemplate: String, markdown: String, colorScheme: ColorScheme,
    linguistic: LinguisticRenderingSettings = LinguisticRenderingSettings(), math: Boolean = true): String {
    val prepared = decorateInlineIpa(protectMarkdownMath(normalizeMarkdownLatex(protectAcademicProse(protectProseLiteralHtml(markdown)))))
    val prefix = "RIKKAACADEMIC" + java.util.UUID.randomUUID().toString().replace("-", "")
    val fragments = linkedMapOf<String, String>()
    val encoded = Regex("""<(span|div) $ACADEMIC_SOURCE="[A-Za-z0-9+/=]+"></\1>""").replace(prepared) { match ->
        val token = prefix + fragments.size + "END"
        fragments[token] = academicProseHtml(Jsoup.parseBodyFragment(match.value).body().childNodes(), linguistic, math)
        if (match.groupValues[1] == "div") "\n\n$token\n\n" else token
    }
    val payload = buildJsonObject { fragments.forEach { (token, html) -> put(token, html) } }.toString().toJavaScriptStringLiteral()
    return htmlTemplate
        .replace("{{ACADEMIC_FRAGMENTS_JSON}}", payload)
        .replace("{{BACKGROUND_COLOR}}", colorScheme.background.toCssHex())
        .replace("{{ON_BACKGROUND_COLOR}}", colorScheme.onBackground.toCssHex())
        .replace("{{SURFACE_COLOR}}", colorScheme.surface.toCssHex())
        .replace("{{ON_SURFACE_COLOR}}", colorScheme.onSurface.toCssHex())
        .replace("{{SURFACE_VARIANT_COLOR}}", colorScheme.surfaceVariant.toCssHex())
        .replace("{{ON_SURFACE_VARIANT_COLOR}}", colorScheme.onSurfaceVariant.toCssHex())
        .replace("{{PRIMARY_COLOR}}", colorScheme.primary.toCssHex())
        .replace("{{OUTLINE_COLOR}}", colorScheme.outline.toCssHex())
        .replace("{{OUTLINE_VARIANT_COLOR}}", colorScheme.outlineVariant.toCssHex())
        .replace("{{MARKDOWN_BASE64}}", normalizeMarkdownProseLinks(encoded, linkEmails = false).base64Encode())
        .replace("{{MARKDOWN_FALLBACK}}", markdown.escapeHtml())
}
