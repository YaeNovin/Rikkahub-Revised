package me.rerere.common.android

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DiagnosticPolicyTest {
    @Test fun `one credential policy covers URLs Basic custom signatures and nested JSON`() {
        val source = """{"Authorization":"Basic SECRET_BASIC","X-Amz-Signature":"SIGNED_SECRET","nested":"{\"access_token\":\"NESTED_SECRET\"}","url":"https://name:USER_SECRET@example.test/api?custom=QUERY_SECRET#FRAGMENT_SECRET","temperature":0.5}"""
        val sanitized=DiagnosticSanitizer.body(source)
        listOf("SECRET_BASIC","SIGNED_SECRET","NESTED_SECRET","USER_SECRET","QUERY_SECRET","FRAGMENT_SECRET").forEach { assertFalse(it,sanitized.contains(it)) }
        assertTrue(sanitized.contains("temperature"))
        Json.parseToJsonElement(sanitized)
        assertEquals("{\"max_tokens\":4096,\"total_tokens\":10}",DiagnosticSanitizer.body("{\"max_tokens\":4096,\"total_tokens\":10}"))
    }
    @Test fun `ordinary writers do not block on IO and saturation is visible`() {
        val writer=DiagnosticWriter()
        val started=CountDownLatch(1)
        val release=CountDownLatch(1)
        writer.write { started.countDown(); release.await(5,TimeUnit.SECONDS) }
        assertTrue(started.await(2,TimeUnit.SECONDS))
        try { repeat(200) { writer.write { } }; assertTrue(writer.dropped.get()>0) }
        finally { release.countDown() }
        writer.read { Unit }
    }
    @Test fun `cancel is a separate nonerror outcome and final timing cannot be overwritten by close`() {
        Logging.clear()
        try {
            val entry=LogEntry.ProviderRequestLog(provider="p",model="m",channel="api",operation="stream",parameters=mapOf("request_id" to "r"))
            Logging.logProviderRequest(entry)
            Logging.completeModelRequest("r",null,100,canceled=true)
            Logging.updateRequestTiming(entry.id,3,"closed")
            val result=Logging.getProviderRequestLogs().single()
            assertEquals(DiagnosticOutcome.CANCELED,result.diagnostic?.outcome)
            assertNull(result.error)
            assertEquals("model_complete",result.durationPhase)
        } finally { Logging.clear() }
    }
    @Test fun `software errors redact credentials at entry instead of only on export`() {
        Logging.clear()
        try {
            Logging.logSoftwareError("test","operation",IllegalStateException("Authorization: Basic SECRET_VALUE"))
            val log=Logging.getRecentLogs().filterIsInstance<LogEntry.ErrorLog>().single()
            assertFalse(Logging.getLog(log.id).toString().contains("SECRET_VALUE"))
            assertEquals(DiagnosticSeverity.ERROR,log.diagnostic?.severity)
        } finally { Logging.clear() }
    }
}
