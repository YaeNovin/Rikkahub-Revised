package me.rerere.tts.controller

/** Bound long unpunctuated text, while preserving surrogate pairs and speech control tags. */
class TextChunker(private val maxChunkLength: Int = 150) {
    init { require(maxChunkLength >= 8) }
    fun split(text: String): List<TtsChunk> {
        if (text.isBlank()) return emptyList()
        val protected = controlTags.findAll(text).map { it.range }.toList()
        val result = mutableListOf<TtsChunk>()
        var start = 0
        while (start < text.length) {
            while (start < text.length && text[start].isWhitespace()) start++
            if (start >= text.length) break
            var end = (start + maxChunkLength).coerceAtMost(text.length)
            if (end < text.length) {
                val natural = (end - 1 downTo start + maxChunkLength / 2).firstOrNull {
                    text[it].isWhitespace() || text[it] in "。！？!?；;，,"
                }
                if (natural != null) end = natural + 1
                protected.firstOrNull { end > it.first && end <= it.last }?.let {
                    end = if (it.first > start) it.first else it.last + 1
                }
                if (end < text.length && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
            }
            val part = text.substring(start, end).trim()
            if (part.isNotEmpty()) result.add(TtsChunk(index = result.size, text = part))
            start = end
        }
        return result
    }
    companion object {
        private val controlTags = Regex("""\[[^\]\n]{1,128}\]|\([^\)\n]{1,128}\)|<#\d+(?:\.\d+)?#>|\$\$[\s\S]*?\$\$""")
    }
}

data class TtsChunk(
    val id: java.util.UUID = java.util.UUID.randomUUID(),
    val index: Int,
    val text: String,
)
