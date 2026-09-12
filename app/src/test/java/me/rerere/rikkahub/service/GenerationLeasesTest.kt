package me.rerere.rikkahub.service

import org.junit.Assert.assertEquals
import org.junit.Test

class GenerationLeasesTest {
    @Test fun `old completion cannot release another generation`() {
        val leases = GenerationLeases()
        val old = leases.acquire()
        val next = leases.acquire()
        assertEquals(2, leases.activeCount.value)
        old()
        old()
        assertEquals(1, leases.activeCount.value)
        next()
        assertEquals(0, leases.activeCount.value)
    }

    @Test fun `parallel completion releases every lease exactly once`() {
        val leases = GenerationLeases()
        val releases = List(40) { leases.acquire() }
        val threads = releases.map { release -> Thread { repeat(10) { release() } }.apply { start() } }
        threads.forEach { it.join() }
        assertEquals(0, leases.activeCount.value)
    }
}
