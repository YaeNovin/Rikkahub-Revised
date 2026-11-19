package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.FlowCollector
import me.rerere.ai.ui.UIMessage

/** A buffered emission is not a database commit. The collector must acknowledge persistence. */
internal suspend fun FlowCollector<GenerationChunk>.emitImageToolCheckpoint(messages: List<UIMessage>) {
    val persisted = CompletableDeferred<Unit>()
    emit(GenerationChunk.Messages(messages, persisted))
    persisted.await()
}
