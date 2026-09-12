package me.rerere.rikkahub.service

import kotlinx.coroutines.Job
import org.junit.Assert.*
import org.junit.Test

class SuggestionGenerationGateTest {
    @Test fun `repeated refresh shares the running request until completion`() {
        val gate = SuggestionGenerationGate()
        val first = Job()
        val token = gate.begin(first)!!
        assertNull(gate.begin(Job()))
        assertEquals(SuggestionGenerationState.GENERATING, gate.state.value)
        gate.finish(token, false)
        assertEquals(SuggestionGenerationState.IDLE, gate.state.value)
        assertNotNull(gate.begin(Job()))
    }

    @Test fun `dismiss or new message cancels obsolete request and stale completion cannot clear new state`() {
        val gate = SuggestionGenerationGate()
        val first = Job()
        val old = gate.begin(first)!!
        gate.invalidate()
        assertTrue(first.isCancelled)
        assertFalse(gate.isCurrent(old))
        val next = gate.begin(Job())!!
        gate.finish(old, true)
        assertEquals(SuggestionGenerationState.GENERATING, gate.state.value)
        gate.finish(next, true)
        assertEquals(SuggestionGenerationState.FAILED, gate.state.value)
        gate.invalidate()
        assertEquals(SuggestionGenerationState.IDLE, gate.state.value)
    }
}
