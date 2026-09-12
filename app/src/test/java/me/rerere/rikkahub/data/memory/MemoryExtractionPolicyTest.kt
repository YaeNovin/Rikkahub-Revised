package me.rerere.rikkahub.data.memory

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.entity.MemoryExtractionCheckpointEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryExtractionPolicyTest {
    private val assistantId = "assistant"
    private val now = 1_000_000L

    @Test
    fun `regenerating the same user turn is skipped`() {
        val user = UIMessage.user("Keep replies concise")
        val messages = listOf(user, UIMessage.assistant("Understood"))
        val initial = planMemoryExtraction(
            messages = messages,
            checkpoint = null,
            assistantId = assistantId,
            now = now,
            idleWindowElapsed = true,
        ) as MemoryExtractionPlan.Run
        val checkpoint = checkpointFor(initial)

        val regenerated = listOf(user, UIMessage.assistant("Sure"))
        val plan = planMemoryExtraction(
            messages = regenerated,
            checkpoint = checkpoint,
            assistantId = assistantId,
            now = now + 1_000L,
            idleWindowElapsed = true,
        )

        assertEquals(MemoryExtractionPlan.Skip, plan)
    }

    @Test
    fun `one ordinary turn waits for the idle window`() {
        val plan = planMemoryExtraction(
            messages = completedTurn("What is Kotlin?"),
            checkpoint = null,
            assistantId = assistantId,
            now = now,
            idleWindowElapsed = false,
        )

        assertTrue(plan is MemoryExtractionPlan.Wait)
        plan as MemoryExtractionPlan.Wait
        assertEquals(MEMORY_EXTRACTION_IDLE_DELAY_MS, plan.delayMillis)
        assertEquals(MemoryExtractionWaitReason.IDLE_BATCH, plan.reason)
    }

    @Test
    fun `three new user turns run as one batch`() {
        val messages = listOf(
            UIMessage.user("First"),
            UIMessage.assistant("One"),
            UIMessage.user("Second"),
            UIMessage.assistant("Two"),
            UIMessage.user("Third"),
            UIMessage.assistant("Three"),
        )

        val plan = planMemoryExtraction(
            messages = messages,
            checkpoint = null,
            assistantId = assistantId,
            now = now,
            idleWindowElapsed = false,
        )

        assertTrue(plan is MemoryExtractionPlan.Run)
        assertEquals(3, (plan as MemoryExtractionPlan.Run).pendingUserTurns)
    }

    @Test
    fun `explicit memory request runs immediately`() {
        val plan = planMemoryExtraction(
            messages = completedTurn("请记住我更喜欢简洁回答"),
            checkpoint = null,
            assistantId = assistantId,
            now = now,
            idleWindowElapsed = false,
        )

        assertTrue(plan is MemoryExtractionPlan.Run)
    }

    @Test
    fun `editing a processed user turn is evaluated again`() {
        val original = UIMessage.user("My preferred editor is A")
        val initial = planMemoryExtraction(
            messages = listOf(original, UIMessage.assistant("Noted")),
            checkpoint = null,
            assistantId = assistantId,
            now = now,
            idleWindowElapsed = true,
        ) as MemoryExtractionPlan.Run
        val edited = original.copy(parts = listOf(UIMessagePart.Text("My preferred editor is B")))

        val plan = planMemoryExtraction(
            messages = listOf(edited, UIMessage.assistant("Updated")),
            checkpoint = checkpointFor(initial),
            assistantId = assistantId,
            now = now + MEMORY_EXTRACTION_SMALL_BATCH_INTERVAL_MS + 1L,
            idleWindowElapsed = true,
        )

        assertTrue(plan is MemoryExtractionPlan.Run)
        assertEquals(edited.id.toString(), (plan as MemoryExtractionPlan.Run).latestUserMessageId)
    }

    @Test
    fun `failed attempt enters cooldown`() {
        val messages = completedTurn("A durable preference")
        val checkpoint = MemoryExtractionCheckpointEntity(
            conversationId = "conversation",
            assistantId = assistantId,
            lastAttemptAt = now - 1_000L,
            lastCompletedAt = 0L,
        )

        val plan = planMemoryExtraction(
            messages = messages,
            checkpoint = checkpoint,
            assistantId = assistantId,
            now = now,
            idleWindowElapsed = true,
        )

        assertTrue(plan is MemoryExtractionPlan.Wait)
        plan as MemoryExtractionPlan.Wait
        assertEquals(MemoryExtractionWaitReason.FAILURE_COOLDOWN, plan.reason)
        assertEquals(MEMORY_EXTRACTION_FAILURE_COOLDOWN_MS - 1_000L, plan.delayMillis)
    }

    @Test
    fun `explicit retry bypasses the failed attempt cooldown`() {
        val messages = completedTurn("A durable preference")
        val checkpoint = MemoryExtractionCheckpointEntity(
            conversationId = "conversation",
            assistantId = assistantId,
            lastUserMessageId = messages.first().id.toString(),
            lastUserContentHash = memoryHashFromPlan(
                planMemoryExtraction(
                    messages = messages,
                    checkpoint = null,
                    assistantId = assistantId,
                    now = now,
                    idleWindowElapsed = true,
                ) as MemoryExtractionPlan.Run
            ),
            lastAttemptAt = now - 1_000L,
            lastCompletedAt = 0L,
        )

        val plan = planMemoryExtraction(
            messages = messages,
            checkpoint = checkpoint,
            assistantId = assistantId,
            now = now,
            idleWindowElapsed = false,
            forceRetry = true,
        )

        assertTrue(plan is MemoryExtractionPlan.Run)
    }

    @Test
    fun `failed attempt from another branch does not block the current branch`() {
        val oldUser = UIMessage.user("Old branch preference")
        val currentUser = UIMessage.user("Current branch preference")
        val oldPlan = planMemoryExtraction(
            messages = listOf(oldUser, UIMessage.assistant("Old reply")),
            checkpoint = null,
            assistantId = assistantId,
            now = now,
            idleWindowElapsed = true,
        ) as MemoryExtractionPlan.Run
        val checkpoint = MemoryExtractionCheckpointEntity(
            conversationId = "conversation",
            assistantId = assistantId,
            lastUserMessageId = oldUser.id.toString(),
            lastUserContentHash = oldPlan.latestUserContentHash,
            lastAttemptAt = now - 1_000L,
            lastCompletedAt = 0L,
        )

        val plan = planMemoryExtraction(
            messages = listOf(currentUser, UIMessage.assistant("Current reply")),
            checkpoint = checkpoint,
            assistantId = assistantId,
            now = now,
            idleWindowElapsed = true,
        )

        assertTrue(plan is MemoryExtractionPlan.Run)
    }

    @Test
    fun `only turns after the checkpoint are sent to extraction`() {
        val firstUser = UIMessage.user("Old preference")
        val secondUser = UIMessage.user("New preference")
        val messages = listOf(
            firstUser,
            UIMessage.assistant("Old reply"),
            secondUser,
            UIMessage.assistant("New reply"),
        )
        val checkpoint = MemoryExtractionCheckpointEntity(
            conversationId = "conversation",
            assistantId = assistantId,
            lastUserMessageId = firstUser.id.toString(),
            lastUserContentHash = memoryHashFromPlan(
                planMemoryExtraction(
                    messages = listOf(firstUser, UIMessage.assistant("Old reply")),
                    checkpoint = null,
                    assistantId = assistantId,
                    now = now,
                    idleWindowElapsed = true,
                ) as MemoryExtractionPlan.Run
            ),
            lastCompletedAt = now - MEMORY_EXTRACTION_SMALL_BATCH_INTERVAL_MS,
        )

        val plan = planMemoryExtraction(
            messages = messages,
            checkpoint = checkpoint,
            assistantId = assistantId,
            now = now,
            idleWindowElapsed = true,
        ) as MemoryExtractionPlan.Run

        assertEquals(listOf(secondUser.id, messages.last().id), plan.messages.map { it.id })
    }

    private fun completedTurn(userText: String): List<UIMessage> = listOf(
        UIMessage.user(userText),
        UIMessage.assistant("Reply"),
    )

    private fun checkpointFor(plan: MemoryExtractionPlan.Run) = MemoryExtractionCheckpointEntity(
        conversationId = "conversation",
        assistantId = assistantId,
        lastUserMessageId = plan.latestUserMessageId,
        lastUserContentHash = plan.latestUserContentHash,
        lastAttemptAt = now,
        lastCompletedAt = now,
    )

    private fun memoryHashFromPlan(plan: MemoryExtractionPlan.Run): String =
        plan.latestUserContentHash
}
