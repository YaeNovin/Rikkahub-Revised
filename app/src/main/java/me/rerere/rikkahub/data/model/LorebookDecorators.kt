package me.rerere.rikkahub.data.model

/** Typed V3 decorator subset for chat contexts, including ordered fallback chains. */
internal fun parseLorebookDecorators(content: String): Map<String, List<String>> = synchronized(decoratorCache) {
    decoratorCache.getOrPut(content) {
        val result = linkedMapOf<String, MutableList<String>>()
        var chainAccepted = true
        var fallbackCount = 0
        content.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (!line.startsWith("@@")) { chainAccepted = true; return@forEach }
            val fallback = line.startsWith("@@@")
            if (!fallback) { chainAccepted = false; fallbackCount = 0 }
            else if (chainAccepted || ++fallbackCount > 8) return@forEach
            val instruction = line.removePrefix(if (fallback) "@@@" else "@@")
            val split = instruction.indexOfFirst(Char::isWhitespace)
            val name = if (split < 0) instruction else instruction.take(split)
            val value = if (split < 0) "" else instruction.substring(split).trim()
            val valid = when (name) {
                "activate", "dont_activate", "keep_activate_after_match", "dont_activate_after_match" -> value.isEmpty()
                "activate_only_after", "scan_depth" -> value.toIntOrNull() in 0..1000
                "activate_only_every" -> value.toIntOrNull() in 1..1000
                "depth" -> value.toIntOrNull() != null
                "role" -> value in setOf("system", "user", "assistant")
                "position" -> value in setOf("before_desc", "after_desc", "personality", "scenario")
                "additional_keys", "exclude_keys" -> value.isNotBlank()
                else -> false // Instruct-only/UI-control decorators are not executed in chat.
            }
            if (valid) {
                chainAccepted = true
                if (name == "additional_keys" || name !in result) result.getOrPut(name) { mutableListOf() }.add(value.ifEmpty { "true" })
            }
        }
        result.mapValues { it.value.toList() }
    }
}

private val decoratorCache = object : LinkedHashMap<String, Map<String, List<String>>>(32, .75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Map<String, List<String>>>?) = size > 64
}
