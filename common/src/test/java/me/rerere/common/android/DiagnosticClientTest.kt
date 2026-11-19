package me.rerere.common.android

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class DiagnosticClientTest {
    @Test fun `client identity remains available in summary indexes and survives serialization`() {
        val client = DiagnosticClient("RikkaHub", "fixture.android", "2.4.8-fixture", 185, "qa")
        val log: LogEntry = LogEntry.ErrorLog(name = "fixture", summary = "failure", details = "stack",
            diagnostic = DiagnosticMetadata(client = client, errorType = "IllegalStateException"))
        val summary = log.summaryOnly()
        assertEquals(client, summary.diagnostic?.client)
        assertEquals("", (summary as LogEntry.ErrorLog).details)
        val encoded = Json.encodeToString(LogEntry.serializer(), summary)
        assertEquals(client, Json.decodeFromString(LogEntry.serializer(), encoded).diagnostic?.client)
    }

    @Test fun `legacy metadata does not fabricate a client identity`() {
        val metadata = Json.decodeFromString(DiagnosticMetadata.serializer(), """{"source":"JAVA_CRASH","severity":"ERROR"}""")
        assertNull(metadata.client)
        assertNull(metadata.errorType)
    }

    @Test fun `new metadata fields follow the existing sanitizer and bounds`() {
        val log = LogEntry.ErrorLog(name = "fixture", summary = "failure", details = "stack",
            diagnostic = DiagnosticMetadata(client = DiagnosticClient("token=SECRET_SENTINEL", "fixture.android",
                "v".repeat(300), buildType = "qa"), errorType = "Basic TYPE_SECRET"))
        val safe = log.sanitized()
        assertFalse(safe.toString().contains("SECRET_SENTINEL"))
        assertFalse(safe.toString().contains("TYPE_SECRET"))
        assertEquals(128, safe.diagnostic?.client?.versionName?.length)
    }
}
