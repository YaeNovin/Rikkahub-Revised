package me.rerere.ai.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReasoningLevelTest {
    @Test
    fun `max exposes the highest budget and effort`() {
        assertEquals(32_000, ReasoningLevel.MAX.budgetTokens)
        assertEquals("max", ReasoningLevel.MAX.effort)
        assertEquals(ReasoningLevel.MAX, ReasoningLevel.fromBudgetTokens(32_000))
    }

    @Test
    fun `effort and budget are capped for protocol limits`() {
        assertEquals("high", ReasoningLevel.MAX.cappedEffort(ReasoningLevel.HIGH))
        assertEquals("xhigh", ReasoningLevel.MAX.cappedEffort(ReasoningLevel.XHIGH))
        assertEquals(24_576, ReasoningLevel.MAX.cappedBudget(24_576))
        assertNull(ReasoningLevel.AUTO.cappedEffort(ReasoningLevel.HIGH))
        assertNull(ReasoningLevel.AUTO.cappedBudget(24_576))
    }
}
