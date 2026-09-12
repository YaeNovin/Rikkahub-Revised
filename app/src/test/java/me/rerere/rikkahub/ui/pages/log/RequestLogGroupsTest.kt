package me.rerere.rikkahub.ui.pages.log

import me.rerere.common.android.LogEntry
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class RequestLogGroupsTest {
    private fun request(method: String = "POST", time: Long = 1) = LogEntry.ProviderRequestLog(
        provider = "Provider", model = "model", channel = "OPENAI_API", operation = "STREAM_TEXT",
        method = method, timestamp = time, url = "https://example.com/v1/chat/completions",
    )

    @Test
    fun `same provider and model combine GET and POST without losing errors`() {
        val first = request()
        val latest = request("GET", 3).copy(responseCode = 502, error = "server error")
        val groups = groupRequestLogs(listOf(first, latest))
        assertEquals(1, groups.size)
        assertEquals(listOf(latest, first), groups.single().entries)
        assertEquals(1, groups.single().entries.count { it.isFailure() })
        assertEquals(groups.single().key, groupRequestLogs(listOf(first)).single().key)
    }

    @Test
    fun `distinct identities protocols and endpoints remain separate`() {
        val base = request()
        val logs = listOf(base, base.copy(id = Uuid.random(), provider = "Other"),
            base.copy(id = Uuid.random(), model = "Other"),
            base.copy(id = Uuid.random(), channel = "ANTHROPIC_API"),
            base.copy(id = Uuid.random(), url = "https://other.example.com/v1/chat/completions"))
        assertEquals(logs.size, groupRequestLogs(logs).size)
    }

    @Test
    fun `unidentified raw requests and other methods are not merged`() {
        val raw = LogEntry.RequestLog(tag = "HTTP", url = "https://example.com", method = "GET")
        val logs = listOf(raw, raw.copy(id = Uuid.random()), request("DELETE"), request("DELETE"),
            request().copy(model = ""), request().copy(model = ""))
        assertEquals(logs.size, groupRequestLogs(logs).size)
    }
}
