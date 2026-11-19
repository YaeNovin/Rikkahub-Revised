package me.rerere.rikkahub.service

import kotlinx.coroutines.*
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.uuid.Uuid

class AcceptedTurnTest {
    @Test fun `native search override prevents duplicate external search but compatible relays keep their route`() {
        val model = me.rerere.ai.provider.Model(modelId = "gemini-3.8-flash")
        val assistant = me.rerere.rikkahub.data.model.Assistant(enableWebSearch = true,
            geminiOptions = me.rerere.ai.provider.GeminiGenerationOptions(search = me.rerere.ai.provider.GeminiSearchOptions(enabled = true)))
        assertFalse(shouldUseExternalWebSearch(assistant, model, nativeGoogle = true))
        assertTrue(shouldUseExternalWebSearch(assistant, model, nativeGoogle = false))
        assertTrue(shouldUseExternalWebSearch(assistant.copy(geminiOptions = assistant.geminiOptions.copy(
            search = assistant.geminiOptions.search.copy(enabled = false))), model.copy(tools = setOf(me.rerere.ai.provider.BuiltInTools.Search)), nativeGoogle = true))
    }

    @Test fun `accepted input is visible while the previous generation still owns the durable snapshot`() = runBlocking {
        scenario { session ->
            val oldAnswer = UIMessage.assistant("old answer")
            session.state.value = session.state.value.copy(messageNodes = listOf(MessageNode.of(oldAnswer)))
            val input = UIMessage.user("new input")
            session.acceptUserMessage(input)
            val display = session.state.value.withPendingUserMessages(session.pendingUserMessages.value)
            assertEquals(listOf(oldAnswer.id, input.id), display.currentMessages.map { it.id })
            assertEquals(listOf(oldAnswer.id), session.state.value.currentMessages.map { it.id })
            session.state.value = display
            assertEquals(2, session.state.value.withPendingUserMessages(session.pendingUserMessages.value).messageNodes.size)
            session.finishUserMessageCommit(input.id)
            assertTrue(session.pendingUserMessages.value.isEmpty())
            assertEquals(display, session.state.value.withPendingUserMessages(session.pendingUserMessages.value))
        }
    }

    private suspend fun scenario(block: suspend (ConversationSession) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val id = Uuid.random()
        val session = ConversationSession(id, Conversation.ofId(id), scope, {})
        try { withTimeout(5000) { block(session) } } finally { scope.cancel() }
    }

    @Test fun `stop during slow initialization still commits accepted user input once`() = runBlocking {
        scenario { session ->
            val ready = CompletableDeferred<Unit>()
            val load = CompletableDeferred<Unit>()
            val saved = CompletableDeferred<Unit>()
            val answers = AtomicInteger()
            val job = session.launchAcceptedTurn(commit = {
                ready.complete(Unit)
                load.await()
                session.state.value = session.state.value.copy(messageNodes = listOf(MessageNode.of(UIMessage.user("accepted"))))
                saved.complete(Unit)
            }, generate = { answers.incrementAndGet() }, onError = { throw AssertionError(it) })
            ready.await()
            session.cancelGeneration()
            assertNull(session.generationJob.value)
            job.join()
            assertTrue(session.isInUse)
            load.complete(Unit)
            saved.await()
            assertEquals("accepted", session.state.value.currentMessages.single().toText())
            assertEquals(0, answers.get())
        }
    }

    @Test fun `user message is visible during a slow disk commit and survives stop`() = runBlocking {
        scenario { session ->
            val visible = CompletableDeferred<Unit>()
            val disk = CompletableDeferred<Unit>()
            val saved = CompletableDeferred<Unit>()
            val job = session.launchAcceptedTurn(commit = {
                session.state.value = session.state.value.copy(messageNodes = listOf(MessageNode.of(UIMessage.user("visible before disk"))))
                visible.complete(Unit)
                disk.await()
                saved.complete(Unit)
            }, generate = { fail("A stopped answer must not start") }, onError = { throw AssertionError(it) })
            visible.await()
            assertEquals("visible before disk", session.state.value.currentMessages.single().toText())
            session.cancelGeneration()
            job.join()
            disk.complete(Unit)
            saved.await()
            assertEquals(1, session.state.value.messageNodes.size)
        }
    }

    @Test fun `one stop acknowledges immediately while old generation finalizes and next send waits`() = runBlocking {
        scenario { session ->
            val started = CompletableDeferred<Unit>()
            val cleanup = CompletableDeferred<Unit>()
            val ended = CompletableDeferred<Unit>()
            val next = CompletableDeferred<Unit>()
            val first = session.launchAcceptedTurn({}, {
                try { started.complete(Unit); awaitCancellation() }
                finally { withContext(NonCancellable) { cleanup.await(); ended.complete(Unit) } }
            }, { throw AssertionError(it) })
            started.await()
            session.cancelGeneration()
            assertNull(session.generationJob.value)
            assertFalse(first.isCompleted)
            repeat(4) { session.cancelGeneration() }
            val second = session.launchAcceptedTurn({
                assertTrue(ended.isCompleted)
                next.complete(Unit)
            }, {}, { throw AssertionError(it) })
            assertSame(second, session.generationJob.value)
            assertFalse(next.isCompleted)
            cleanup.complete(Unit)
            next.await()
            second.join()
        }
    }

    @Test fun `rapid stopped sends commit in acceptance order without duplicates`() = runBlocking {
        scenario { session ->
            val gate = CompletableDeferred<Unit>()
            val complete = CompletableDeferred<Unit>()
            val accepted = mutableListOf<Int>()
            repeat(30) { index ->
                session.launchAcceptedTurn({
                    gate.await()
                    accepted.add(index)
                    if (index == 29) complete.complete(Unit)
                }, {}, { throw AssertionError(it) })
                session.cancelGeneration()
            }
            gate.complete(Unit)
            complete.await()
            assertEquals((0..29).toList(), accepted)
        }
    }
}
