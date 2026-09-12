package me.rerere.rikkahub.ui.components.ai

import me.rerere.ai.core.TokenUsage
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.*
import me.rerere.rikkahub.data.ai.context.*
import org.junit.Assert.*
import org.junit.Test

class RequestContextAccountingTest {
    @Test fun `compression and message template changes invalidate request scope`() {
        val assistant = me.rerere.rikkahub.data.model.Assistant()
        val conversation = me.rerere.rikkahub.data.model.Conversation(assistantId = assistant.id, messageNodes = emptyList())
        val settings = me.rerere.rikkahub.data.datastore.Settings(assistants = listOf(assistant))
        val model = Model()
        val before = requestContextScopeKey(conversation, settings, model)
        assertNotEquals(before, requestContextScopeKey(conversation, settings.copy(assistants = listOf(assistant.copy(enableRollingContextCompression = false))), model))
        assertNotEquals(before, requestContextScopeKey(conversation, settings.copy(assistants = listOf(assistant.copy(messageTemplate = "changed"))), model))
    }

    @Test fun `latest measured input uses request time rather than message position`() {
        val earlierMessage = UIMessage.assistant("regenerated").copy(requestContext = RequestContextSnapshot("model", "", null, emptyMap(), emptyMap(), measuredPromptTokens = 300, measuredAt = 20))
        val laterMessage = UIMessage.assistant("old later answer").copy(requestContext = RequestContextSnapshot("model", "", null, emptyMap(), emptyMap(), measuredPromptTokens = 100, measuredAt = 10))
        assertEquals(300, calculateChatContextUsage(listOf(earlierMessage, laterMessage), capacityTokens = 1000).measuredInputTokens)
    }

    @Test fun `actual request includes system injections tools and backend calibration`() {
        val user = UIMessage.user("Hello")
        val model = Model()
        val tool = Tool("ask_user", "Ask a question", execute = { emptyList() })
        val snapshot = makeRequestContextSnapshot(listOf(user), listOf(UIMessage.system("Long hidden system and memory prompt"), user),
            listOf(tool), model, "scope", null).copy(measuredPromptTokens = 900)
        val reply = UIMessage.assistant("OK").copy(usage = TokenUsage(completionTokens = 10000), requestContext = snapshot)
        val usage = calculateChatContextUsage(listOf(user, reply), capacityTokens = 32000, scopeKey = "scope", modelId = model.id.toString())
        assertEquals(905, usage.usedTokens)
        assertTrue(usage.calibrated)
        assertTrue(usage.breakdown.containsKey(ContextTokenCategory.TOOL_SCHEMA))
        assertTrue(usage.breakdown.containsKey(ContextTokenCategory.SYSTEM))
        assertEquals(usage.usedTokens, usage.breakdown.values.sum())
    }

    @Test fun `changed history or configuration invalidates measured calibration`() {
        val user = UIMessage.user("Before")
        val model = Model()
        val snapshot = makeRequestContextSnapshot(listOf(user), listOf(user), emptyList(), model, "old", null).copy(measuredPromptTokens = 900)
        val reply = UIMessage.assistant("OK").copy(requestContext = snapshot)
        val edited = user.copy(parts = listOf(UIMessagePart.Text("After")))
        assertFalse(calculateChatContextUsage(listOf(edited, reply), capacityTokens = 32000, scopeKey = "old").calibrated)
        assertFalse(calculateChatContextUsage(listOf(user, reply), capacityTokens = 32000, scopeKey = "new").calibrated)
        val finalized = reply.copy(requestContext = snapshot.copy(responseFingerprint = contextHistoryFingerprint(listOf(reply))))
        val editedReply = finalized.copy(
            parts = listOf(UIMessagePart.Text("edited answer")),
        )
        assertFalse(calculateChatContextUsage(listOf(user, editedReply), capacityTokens = 32000, scopeKey = "old").calibrated)
    }

    @Test fun `tool continuation does not count the already sent assistant twice`() {
        val user = UIMessage.user("Hello")
        val prior = UIMessage.assistant("First")
        val snapshot = makeRequestContextSnapshot(listOf(user, prior), listOf(user, prior), emptyList(), Model(), null, prior.id).copy(measuredPromptTokens = 100)
        val usage = calculateChatContextUsage(listOf(user, prior.copy(requestContext = snapshot)), capacityTokens = 1000)
        assertEquals(100, usage.usedTokens)
    }

    @Test fun `downward calibration preserves an exact category sum`() {
        val scaled = calibrateRequestCategories(mapOf("TEXT" to 600, "IMAGE" to 300, "SYSTEM" to 100), 51)
        assertEquals(51, scaled.values.sum())
        assertTrue(scaled.values.all { it >= 0 })
        assertNull(ChatContextUsage(0, 0, true).percentage)
    }

    @Test fun `hidden reasoning is not added as input while tool media is categorized separately`() {
        val parts = listOf(UIMessagePart.Reasoning("x".repeat(10000)), UIMessagePart.Tool("id", "tool", "{}",
            output = listOf(UIMessagePart.Image("file:///image.png"))))
        val values = estimateRequestCategories(listOf(UIMessage.assistant("").copy(parts = parts)))
        assertFalse(values.containsKey("REASONING"))
        assertTrue(values.containsKey("IMAGE"))
        assertTrue(estimateRequestCategories(listOf(UIMessage.assistant("").copy(parts = parts)), includeReasoning = true).containsKey("REASONING"))
    }
}
