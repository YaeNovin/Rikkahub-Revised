package me.rerere.rikkahub.data.ai.transforms

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

internal const val MEMORY_QUERY_VECTOR_BUDGET_MS = 1_200L

/** One shared network budget, retaining completed vectors and lexical fallback on timeout. */
internal suspend fun <K, V : Any> collectMemoryQueryVectors(
    modelIds: Iterable<K>,
    timeoutMillis: Long = MEMORY_QUERY_VECTOR_BUDGET_MS,
    generate: suspend (K) -> V?,
): Map<K, V> {
    currentCoroutineContext().ensureActive()
    val vectors = mutableMapOf<K, V>()
    withTimeoutOrNull(timeoutMillis) {
        for (modelId in modelIds) generate(modelId)?.let { vectors[modelId] = it }
    }
    return vectors
}
