package me.rerere.rikkahub.ui.pages.log

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.common.android.LogEntry
import org.junit.Assert.*
import org.junit.Test
import java.io.StringWriter

class LogExportTest {
    @Test fun `full export preserves conversation bodies while redacted export omits them`() {
        val log = LogEntry.RequestLog(tag = "http", url = "https://example.com", method = "POST",
            requestBody = """{"prompt":"private prompt","api_key":"secret-value","tools":[{"name":"lookup"}]}""",
            responseBody = """{"content":"private answer","token":"response-secret"}""")
        val full = StringWriter().also { writeLogExport(it, listOf(log), "test", LogExportMode.FULL) }.toString()
        val safe = StringWriter().also { writeLogExport(it, listOf(log), "test") }.toString()
        assertTrue(full.contains("private prompt")); assertTrue(full.contains("private answer"))
        assertFalse(safe.contains("private prompt")); assertFalse(safe.contains("private answer"))
        assertFalse(full.contains("secret-value")); assertFalse(full.contains("response-secret"))
        assertTrue(safe.contains("requestBody")); assertTrue(safe.contains("responseBody"))
    }
    @Test
    fun `request and software sections are disjoint and include every record`() {
        val request = LogEntry.RequestLog(tag = "http", url = "https://example.com", method = "GET")
        val provider = LogEntry.ProviderRequestLog(provider = "Google", model = "gemini", channel = "GOOGLE_AI_STUDIO", operation = "STREAM_TEXT")
        val crash = LogEntry.ErrorLog(tag = "CrashHandler", name = "crash", summary = "error", details = "trace")
        val software = LogEntry.TextLog(tag = "FilesManager", message = "sync")
        val logs = listOf(request, provider, crash, software)
        assertEquals(listOf(request, provider), logs.filter { it.section() == LogSection.REQUESTS })
        val local = logs.filter { it.section() == LogSection.SOFTWARE }
        assertEquals(listOf(crash, software), local)
        val report = StringWriter().also { writeLogExport(it, local, "test") }.toString()
        assertEquals(2, Json.parseToJsonElement(report).jsonObject.getValue("logs").jsonArray.size)
    }

    @Test
    fun `analysis excludes successful requests and plain diagnostics`() {
        val success = LogEntry.RequestLog(tag = "http", url = "https://example.com", method = "GET", responseCode = 200)
        assertThrows(IllegalArgumentException::class.java) { logAnalysisPayload(success) }
        assertThrows(IllegalArgumentException::class.java) { logAnalysisPayload(LogEntry.TextLog(tag = "info", message = "ok")) }
        assertTrue(logAnalysisPayload(success.copy(responseCode = 429)).contains("429"))
    }

    @Test
    fun `analysis payload is redacted and bounded with root cause preserved`() {
        val log = LogEntry.ErrorLog(name = "Failure", summary = "timeout", details =
            "Bearer secret-value\n" + "trace line\n".repeat(4000) + "ROOT_CAUSE")
        val payload = logAnalysisPayload(log)
        assertFalse(payload.contains("secret-value"))
        assertTrue(payload.contains("ROOT_CAUSE"))
        assertTrue(payload.length < 24_100)
        assertTrue(payload.contains("truncated"))
    }

    @Test
    fun `failure filter includes protocol errors without error descriptions`() {
        val success = LogEntry.RequestLog(tag = "http", url = "https://example.com", method = "GET", responseCode = 200)
        val failed = success.copy(responseCode = 502)
        val provider = LogEntry.ProviderRequestLog(provider = "Gemini", model = "gemini", channel = "GOOGLE_AI_STUDIO",
            operation = "STREAM_TEXT", responseCode = 404)
        val timeout = success.copy(responseCode = null, error = "timeout")
        val logs = listOf(success, failed, provider, timeout)
        assertEquals(setOf(failed, provider, timeout), filterLogs(logs, LogFilter.ERRORS, "").toSet())
        assertEquals(listOf(provider), filterLogs(logs, LogFilter.ERRORS, "  GEMINI  "))
        assertEquals(listOf(failed), filterLogs(logs, LogFilter.REQUESTS, "502"))
    }

    @Test
    fun `search includes full reasons and text diagnostics`() {
        val error = LogEntry.ErrorLog(name = "Error", summary = "Failed", reason = "磁盘空间不足", details = "完整堆栈")
        val text = LogEntry.TextLog(tag = "memory", message = "checkpoint retry")
        assertEquals(listOf(error), filterLogs(listOf(error, text), LogFilter.ALL, "空间"))
        assertEquals(listOf(text), filterLogs(listOf(error, text), LogFilter.TEXT, "retry"))
        assertTrue(filterLogs(listOf(error), LogFilter.TEXT, "").isEmpty())
    }

    @Test
    fun `export retains all log types and full diagnostics with unicode`() {
        val longText = "完整诊断内容".repeat(2000)
        val logs = listOf(
            LogEntry.ErrorLog(name = "错误", summary = "摘要", reason = "原因", details = longText),
            LogEntry.TextLog(tag = "memory", message = "提取中"),
            LogEntry.RequestLog(tag = "http", url = "https://example.com", method = "POST", responseCode = 429),
            LogEntry.ProviderRequestLog(provider = "Anthropic", model = "claude", channel = "ANTHROPIC_API", operation = "STREAM_TEXT"),
        )
        val output = StringWriter().also { writeLogExport(it, logs, "test") }.toString()
        val report = Json.parseToJsonElement(output).jsonObject
        assertEquals("4", report.getValue("count").jsonPrimitive.content)
        assertEquals(longText, report.getValue("logs").jsonArray[0].jsonObject.getValue("details").jsonPrimitive.content)
        assertTrue(report.getValue("logs").jsonArray.all { "time" in it.jsonObject })
    }

    @Test
    fun `export redacts credentials in headers urls and nested JSON without changing source`() {
        val log = LogEntry.RequestLog(
            tag = "http", url = "https://example.com?key=url-secret&model=gemini", method = "POST",
            requestHeaders = mapOf("Authorization" to "Bearer header-secret", "x-api-key" to "key-secret"),
            responseHeaders = mapOf("Set-Cookie" to "cookie-secret"),
            requestBody = """{"api_key":"body-secret","temperature":0.5}""",
            error = "Bearer error-secret",
        )
        val output = StringWriter().also { writeLogExport(it, listOf(log), "test") }.toString()
        listOf("url-secret", "header-secret", "key-secret", "cookie-secret", "body-secret", "error-secret").forEach {
            assertFalse(it, output.contains(it))
        }
        assertTrue(output.contains("temperature"))
        assertTrue(output.contains("model=gemini"))
        assertEquals("key-secret", log.requestHeaders["x-api-key"])
    }
}
