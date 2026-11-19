package me.rerere.rikkahub.ui.pages.log

import me.rerere.common.android.LogEntry
import org.junit.Assert.*
import org.junit.Test
import java.io.StringWriter
import java.time.LocalDate
import java.time.ZoneId

class DiagnosticLogIndexTest {
    private fun log(model: String = "m", code: Int? = 200, phase: String? = "model_complete") =
        LogEntry.ProviderRequestLog(provider = "Example", model = model, channel = "CHAT", operation = "TEXT_GENERATION",
            responseCode = code, durationPhase = phase, timestamp = 0, firstTokenMs = 12.345,
            preciseTimestamp = "1970-01-01T00:00:00.123456789Z")

    @Test fun `provider drilldown keeps models and excludes other endpoints`() {
        val first = log("a").copy(url = "https://one.test/api")
        val second = log("b").copy(url = "https://one.test/other")
        val third = log("c").copy(url = "https://two.test/api")
        val index = buildDiagnosticIndex(listOf(first, second, third), first.providerKey(), DiagnosticFilter(model = "b"), DiagnosticFilter())
        assertEquals(2, index.providers.size)
        assertEquals(listOf("a", "b"), index.models)
        assertEquals(listOf(second), index.requests)
    }

    @Test fun `unknown and unfinished requests are not successes`() {
        assertFalse(log(code = null).matchesStatus(DiagnosticStatus.SUCCESS))
        assertFalse(log(phase = "headers").matchesStatus(DiagnosticStatus.SUCCESS))
        assertFalse(log(phase = "closed").matchesStatus(DiagnosticStatus.SUCCESS))
        assertTrue(log().matchesStatus(DiagnosticStatus.SUCCESS))
        assertTrue(log(code = 500).matchesStatus(DiagnosticStatus.ERROR))
    }

    @Test fun `custom dates include whole end day across DST`() {
        val zone = ZoneId.of("America/New_York")
        val day = LocalDate.of(2026, 11, 1)
        val filter = DiagnosticFilter(DiagnosticPeriod.CUSTOM, day.toEpochDay(), day.toEpochDay())
        val until = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        assertTrue(filter.matches(log().copy(timestamp = until - 1), until, zone))
        assertFalse(filter.matches(log().copy(timestamp = until), until, zone))
    }

    @Test fun `nanoseconds survive formatting and legacy gets zero padding`() {
        assertTrue(log().diagnosticTime(ZoneId.of("UTC")).contains(".123456789"))
        assertTrue(log().copy(timestamp = 123, preciseTimestamp = null).diagnosticTime(ZoneId.of("UTC")).contains(".123000000"))
    }

    @Test fun `CSV quotes newlines and neutralizes formulas even when only one entry`() {
        val writer = StringWriter()
        writeDiagnosticCsv(writer, listOf(log("=HYPERLINK(\"x\")").copy(error = "line1\nline2")), "qa", LogExportMode.FULL)
        val result = writer.toString()
        assertTrue(result.startsWith("\uFEFF\"schema_version\""))
        assertTrue(result.contains("\"'=HYPERLINK(\"\"x\"\")\""))
        assertTrue(result.contains("first_token_ms"))
    }

    @Test fun `detailed log retains stack and strips secrets`() {
        val entry = LogEntry.ErrorLog(name = "renderer", summary = "failed", details = "Error: bad\n  at draw(x:1)\nCaused by: token=abc-secret")
        val writer = StringWriter()
        writeDiagnosticDetail(writer, entry, "qa", LogExportMode.FULL)
        assertTrue(writer.toString().contains("\n  at draw(x:1)\nCaused by:"))
        assertFalse(writer.toString().contains("abc-secret"))
        assertTrue(writer.toString().contains("END OF DIAGNOSTIC LOG"))
    }
}
