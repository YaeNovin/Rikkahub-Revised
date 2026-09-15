package me.rerere.common.android

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.uuid.Uuid

class RequestLogStoreTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun `request and provider response updates survive a new store instance`() {
        val folder = temp.newFolder()
        val store = RequestLogStore(folder)
        val request = LogEntry.RequestLog(tag = "HTTP", method = "POST", url = "https://example.test/v1", requestBody = "prompt", responseCode = 502)
        val provider = LogEntry.ProviderRequestLog(provider = "Google", model = "gemini", channel = "api", operation = "stream", parameters = mapOf("body.custom" to "true"))
        store.save(request)
        store.save(provider)
        store.save(provider.copy(responseBody = "data: response\n\n"))
        val restored = RequestLogStore(folder).read()
        assertEquals(2, restored.size)
        assertEquals(request, restored.first { it.id == request.id })
        assertEquals("data: response\n\n", (restored.first { it.id == provider.id } as LogEntry.ProviderRequestLog).responseBody)
    }

    @Test fun `corrupt entry and interrupted temporary file do not erase good logs`() {
        val folder = temp.newFolder()
        val entry = LogEntry.RequestLog(tag = "HTTP", url = "https://example.test", method = "GET")
        RequestLogStore(folder).save(entry)
        File(folder, "${Uuid.random()}.json").writeText("broken")
        File(folder, ".${entry.id}.json.tmp").writeText("partial")
        val failures = mutableListOf<Exception>()
        assertEquals(listOf(entry), RequestLogStore(folder, onFailure = failures::add).read())
        assertEquals(1, failures.size)
    }

    @Test fun `retention is bounded and clear survives restart without deleting unrelated files`() {
        val folder = temp.newFolder()
        val store = RequestLogStore(folder, maxEntries = 3, maxBytes = 1600)
        repeat(12) { index -> store.save(LogEntry.RequestLog(tag = "HTTP", url = "https://example.test/$index", method = "GET")) }
        assertTrue(store.read().size <= 3)
        assertTrue(folder.listFiles()!!.sumOf { it.length() } <= 1600)
        val unrelated = File(folder, "notes.txt").apply { writeText("keep") }
        store.clear()
        assertTrue(RequestLogStore(folder).read().isEmpty())
        assertEquals("keep", unrelated.readText())
    }

    @Test fun `failed oversized replacement leaves previous valid log intact`() {
        val folder = temp.newFolder()
        val failures = mutableListOf<Exception>()
        val store = RequestLogStore(folder, maxBytes = 1024, onFailure = failures::add)
        val entry = LogEntry.RequestLog(tag = "HTTP", url = "https://example.test", method = "GET", responseBody = "old")
        store.save(entry)
        store.save(entry.copy(responseBody = "x".repeat(2048)))
        assertEquals(listOf(entry), RequestLogStore(folder).read())
        assertEquals(1, failures.size)
    }
}
