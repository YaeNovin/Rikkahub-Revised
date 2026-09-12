package me.rerere.rikkahub.ui.components.richtext

import android.content.Context
import androidx.compose.material3.ColorScheme
import me.rerere.rikkahub.utils.base64Encode
import me.rerere.rikkahub.utils.toCssHex
import me.rerere.rikkahub.utils.escapeHtml

/**
 * Build HTML page for markdown preview with support for:
 * - Offline Markdown rendering via markdown-it
 * - LaTeX math via KaTeX
 * - Mermaid diagrams
 * - Syntax highlighting via highlight.js
 */
fun buildMarkdownPreviewHtml(context: Context, markdown: String, colorScheme: ColorScheme): String {
    val htmlTemplate = context.assets.open("html/mark.html").bufferedReader().use { it.readText() }
    return buildMarkdownPreviewHtml(htmlTemplate, markdown, colorScheme)
}

internal fun buildMarkdownPreviewHtml(htmlTemplate: String, markdown: String, colorScheme: ColorScheme): String {
    return htmlTemplate
        .replace("{{BACKGROUND_COLOR}}", colorScheme.background.toCssHex())
        .replace("{{ON_BACKGROUND_COLOR}}", colorScheme.onBackground.toCssHex())
        .replace("{{SURFACE_COLOR}}", colorScheme.surface.toCssHex())
        .replace("{{ON_SURFACE_COLOR}}", colorScheme.onSurface.toCssHex())
        .replace("{{SURFACE_VARIANT_COLOR}}", colorScheme.surfaceVariant.toCssHex())
        .replace("{{ON_SURFACE_VARIANT_COLOR}}", colorScheme.onSurfaceVariant.toCssHex())
        .replace("{{PRIMARY_COLOR}}", colorScheme.primary.toCssHex())
        .replace("{{OUTLINE_COLOR}}", colorScheme.outline.toCssHex())
        .replace("{{OUTLINE_VARIANT_COLOR}}", colorScheme.outlineVariant.toCssHex())
        .replace("{{MARKDOWN_BASE64}}", markdown.base64Encode())
        .replace("{{MARKDOWN_FALLBACK}}", markdown.escapeHtml())
}
