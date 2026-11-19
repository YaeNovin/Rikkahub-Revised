package me.rerere.rikkahub.ui.components.table

/** Distribute viewport space without breaking any column's wrapping limit. */
internal fun stretchTableColumnWidths(widths: IntArray, maximums: IntArray, viewport: Int): IntArray {
    val result = widths.copyOf()
    var remaining = (viewport.toLong() - result.sumOf { it.toLong() }).coerceAtLeast(0L)
    while (remaining > 0) {
        val active = result.indices.filter { result[it] < maximums[it] }
        if (active.isEmpty()) break
        val share = (remaining / active.size).coerceAtLeast(1)
        for (column in active) {
            val amount = minOf(share, remaining, maximums[column].toLong() - result[column]).toInt()
            result[column] += amount
            remaining -= amount
        }
    }
    return result
}
