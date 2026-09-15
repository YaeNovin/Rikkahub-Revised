package me.rerere.asr

/** Reconcile partial/final events by item identity, never by network completion order. */
internal class TranscriptAccumulator {
    private data class Item(var previous: String? = null, var text: String = "", var final: Boolean = false)
    private val items = linkedMapOf<String, Item>()

    @Synchronized fun clear() = items.clear()

    @Synchronized fun register(id: String, previous: String? = null) {
        val item = items.getOrPut(id) { Item() }
        if (!previous.isNullOrBlank()) item.previous = previous
    }

    @Synchronized fun update(id: String, text: String, append: Boolean = false, final: Boolean = false) {
        val item = items.getOrPut(id) { Item() }
        if (item.final && !final) return
        item.text = if (append) item.text + text else text
        item.final = final
    }

    @Synchronized fun hasPending(): Boolean = items.values.any { !it.final }

    @Synchronized fun text(): String {
        val ordered = linkedSetOf<String>()
        fun visit(id: String, visiting: MutableSet<String>) {
            if (id in ordered || !visiting.add(id)) return
            items[id]?.previous?.takeIf { it in items }?.let { visit(it, visiting) }
            ordered.add(id)
        }
        items.keys.forEach { visit(it, mutableSetOf()) }
        return ordered.mapNotNull { items[it]?.text?.trim()?.takeIf(String::isNotEmpty) }.joinToString(" ")
    }
}
