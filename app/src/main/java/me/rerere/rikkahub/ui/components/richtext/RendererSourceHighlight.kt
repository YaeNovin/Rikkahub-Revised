package me.rerere.rikkahub.ui.components.richtext

import me.rerere.rikkahub.data.render.MAX_DECLARATIVE_SOURCE_CHARS

/** Presentation-only alias mapping. Renderer selection and source bytes stay unchanged. */
internal fun rendererSourceHighlightLanguage(language: String, source: String? = null): String = when {
    normalizeCodeFenceLanguage(language) == "jsxgraph" && geometryRenderer(language, source) == null -> "javascript"
    geometryRenderer(language) != null -> "json"
    else -> normalizeCodeFenceLanguage(language)
}

internal fun rendererSourceHighlightLimit(language: String, sourceLength: Int = 0): Int =
    if (geometryRenderer(language) != null) {
        if (sourceLength <= MAX_DECLARATIVE_SOURCE_CHARS) MAX_DECLARATIVE_SOURCE_CHARS else 0
    } else if (sourceLength <= 32768) 32768 else 0
