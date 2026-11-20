package me.rerere.rikkahub.data.ai.transforms

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class MemoryQueryBudgetTest {
    @Test fun successfulVectorsAndMissingModelsKeepTheirResults() = runBlocking {
        val result = collectMemoryQueryVectors(listOf("a", "missing", "b")) { model ->
            if (model == "missing") null else listOf(1f, 2f)
        }
        assertEquals(setOf("a", "b"), result.keys)
    }

    @Test fun oneTotalBudgetRetainsFinishedVectorsAndSkipsRemainingModels() = runBlocking {
        val called = mutableListOf<String>()
        val result = collectMemoryQueryVectors(listOf("fast", "slow", "never"), timeoutMillis = 50) { model ->
            called += model
            if (model == "slow") awaitCancellation()
            listOf(1f)
        }
        assertEquals(listOf("fast", "slow"), called)
        assertEquals(mapOf("fast" to listOf(1f)), result)
    }

    @Test fun timeoutBeforeFirstVectorLeavesLexicalFallbackAvailable() = runBlocking {
        val result = collectMemoryQueryVectors<String, List<Float>>(listOf("slow"), timeoutMillis = 30) { awaitCancellation() }
        assertTrue(result.isEmpty())
    }

    @Test fun parentCancellationIsNeverTurnedIntoSuccessfulFallback() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        var returned = false
        val task = launch {
            collectMemoryQueryVectors<String, List<Float>>(listOf("slow")) { entered.complete(Unit); awaitCancellation() }
            returned = true
        }
        entered.await()
        task.cancelAndJoin()
        assertFalse(returned)
    }
}
