package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ConversationPagingLoadTest {
    @Test
    fun `oversized pages are retried without skipping records`() = runBlocking {
        val source = (0 until 9).toList()
        val requestedPageSizes = mutableListOf<Int>()
        val loaded = mutableListOf<Int>()

        forEachAdaptivePage(
            initialPageSize = 8,
            loadPage = { limit, offset ->
                requestedPageSizes += limit
                if (limit > 2) throw IllegalStateException("cursor window is full")
                source.drop(offset).take(limit)
            },
            shouldReducePage = { it is IllegalStateException },
            consumePage = loaded::addAll,
        )

        assertEquals(listOf(8, 4, 2), requestedPageSizes.take(3))
        assertEquals(source, loaded)
    }

    @Test
    fun `single row failure is reported instead of silently skipped`() = runBlocking {
        var failure: Throwable? = null
        try {
            forEachAdaptivePage<Int>(
                initialPageSize = 2,
                loadPage = { _, _ -> throw IllegalStateException("broken row") },
                shouldReducePage = { true },
                consumePage = {},
            )
        } catch (error: Throwable) {
            failure = error
        }

        if (failure == null) fail("Expected the single-row load failure to be propagated")
        assertTrue(failure is IllegalStateException)
    }
}
