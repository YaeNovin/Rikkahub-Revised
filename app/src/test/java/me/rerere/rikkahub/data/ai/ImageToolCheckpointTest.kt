package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

class ImageToolCheckpointTest {
    @Test fun `buffered flow cannot submit image before persistence acknowledgement`() = runBlocking {
        val received = CompletableDeferred<GenerationChunk.Messages>()
        var submitted = false
        val job = launch {
            flow<GenerationChunk> {
                emitImageToolCheckpoint(emptyList())
                submitted = true
            }.buffer().collect { received.complete(it as GenerationChunk.Messages) }
        }
        val checkpoint = withTimeout(5_000) { received.await() }
        yield()
        assertFalse(submitted)
        checkpoint.persisted!!.complete(Unit)
        withTimeout(5_000) { job.join() }
        assertTrue(submitted)
    }

    @Test fun `persistence failure prevents the paid request`() = runBlocking {
        var submitted = false
        val result = runCatching {
            flow<GenerationChunk> {
                emitImageToolCheckpoint(emptyList())
                submitted = true
            }.buffer().collect { (it as GenerationChunk.Messages).persisted!!.completeExceptionally(IllegalStateException("disk full")) }
        }
        assertTrue(result.isFailure)
        assertFalse(submitted)
    }

    @Test fun `cancelling while checkpoint waits does not strand generation`() = runBlocking {
        val received = CompletableDeferred<Unit>()
        var submitted = false
        val job = launch {
            flow<GenerationChunk> {
                emitImageToolCheckpoint(emptyList())
                submitted = true
            }.buffer().collect { received.complete(Unit) }
        }
        withTimeout(5_000) { received.await() }
        withTimeout(5_000) { job.cancelAndJoin() }
        assertFalse(submitted)
    }
}
