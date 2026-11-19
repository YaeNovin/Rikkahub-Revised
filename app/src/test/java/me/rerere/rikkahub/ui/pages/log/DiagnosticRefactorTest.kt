package me.rerere.rikkahub.ui.pages.log

import kotlinx.serialization.json.Json
import me.rerere.common.android.*
import org.junit.Assert.*
import org.junit.Test

class DiagnosticRefactorTest {
    @Test fun `logging health warnings and unknown outcomes are never successful entries`() {
        val warning = LogEntry.TextLog(tag = "LOG_STORAGE", message = "dropped=1",
            diagnostic = DiagnosticMetadata(source = DiagnosticSource.LOG_STORAGE,
                severity = DiagnosticSeverity.WARNING, outcome = DiagnosticOutcome.UNKNOWN))
        assertEquals("警告", warning.statusLabel())
        assertFalse(warning.isFailure())
        assertFalse(warning.matchesStatus(DiagnosticStatus.SUCCESS))
        val unknown = warning.copy(diagnostic = DiagnosticMetadata(outcome = DiagnosticOutcome.UNKNOWN))
        assertEquals("未知", unknown.statusLabel())
        assertFalse(unknown.matchesStatus(DiagnosticStatus.SUCCESS))
    }
    @Test fun `system recycling and user cancellation are not software failures`() {
        val recycled=LogEntry.ErrorLog(name="exit",summary="low memory",details="reason=3",
            diagnostic=DiagnosticMetadata(source=DiagnosticSource.PROCESS_EXIT,severity=DiagnosticSeverity.WARNING,outcome=DiagnosticOutcome.INTERRUPTED))
        assertFalse(recycled.isFailure())
        assertEquals("系统退出记录",recycled.statusLabel())
        val canceled=LogEntry.ProviderRequestLog(provider="p",model="m",channel="api",operation="stream",error="socket closed",
            diagnostic=DiagnosticMetadata(outcome=DiagnosticOutcome.CANCELED))
        assertFalse(canceled.isFailure()); assertFalse(canceled.matchesStatus(DiagnosticStatus.SUCCESS))
        assertEquals("已取消",canceled.statusLabel())
    }
    @Test fun `legacy stack trace in user attached format is an error not successful information`() {
        val entry=LogEntry.TextLog(tag="ChatService",message="java.lang.IllegalStateException: item_id not found\n\tat example.run(Test.kt:1)")
        assertTrue(entry.isFailure()); assertEquals("错误",entry.statusLabel())
    }
    @Test fun `AI minimum projection excludes both bodies and credentials and remains valid JSON`() {
        val entry=LogEntry.RequestLog(tag="http",method="POST",url="https://test.invalid?key=secret",responseCode=502,
            requestBody="PROMPT_SENTINEL",responseBody="ANSWER_SENTINEL",requestHeaders=mapOf("Authorization" to "Basic SECRET_SENTINEL"),
            error="timeout",diagnostic=DiagnosticMetadata(attributes=mapOf("signature" to "SIG_SENTINEL")))
        val payload=logAnalysisPayload(entry)
        listOf("PROMPT_SENTINEL","ANSWER_SENTINEL","SECRET_SENTINEL","SIG_SENTINEL").forEach { assertFalse(it,payload.contains(it)) }
        Json.parseToJsonElement(payload); assertTrue(payload.length<=24000)
    }
    @Test fun `AI truncation never produces partial JSON and preserves root cause tail`() {
        val entry=LogEntry.ErrorLog(name="failure",summary="error",details="\"\\\n".repeat(20000)+"ROOT_CAUSE")
        val payload=logAnalysisPayload(entry)
        Json.parseToJsonElement(payload); assertTrue(payload.length<=24000); assertTrue(payload.contains("ROOT_CAUSE"))
    }
}
