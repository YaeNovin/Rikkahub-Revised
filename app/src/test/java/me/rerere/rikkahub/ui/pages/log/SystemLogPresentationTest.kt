package me.rerere.rikkahub.ui.pages.log

import me.rerere.common.android.*
import org.junit.Assert.*
import org.junit.Test

class SystemLogPresentationTest {
    private val client = DiagnosticClient("RikkaHub", "fixture.android", "2.4.8-dev", 185, "qa")

    @Test fun `client type uses captured build type instead of guessing from version name`() {
        assertEquals("RikkaHub · 2.4.8-dev", client.clientLabel())
        assertEquals("QA 测试版", client.clientTypeLabel())
        assertEquals("正式版", client.copy(buildType = "release").clientTypeLabel())
        assertEquals("Debug 测试版", client.copy(buildType = "debug").clientTypeLabel())
        assertEquals("未记录", client.copy(buildType = null).clientTypeLabel())
        assertEquals("未记录（旧日志）", (null as DiagnosticClient?).clientLabel())
    }

    @Test fun `old stacks retain their root exception type without client metadata`() {
        val log = LogEntry.ErrorLog(name = "发送消息", summary = "IllegalStateException",
            details = "java.lang.IllegalStateException: failed\n  at run(Test.kt:1)\nCaused by: java.io.IOException: fixture")
        assertEquals("java.io.IOException", log.systemErrorType())
        assertEquals("未记录（旧日志）", log.diagnostic?.client.clientLabel())
        assertEquals(log.details, log.systemLogBody())
    }

    @Test fun `system exits retain numeric meaning units and trace availability`() {
        val log = LogEntry.ErrorLog(name = "退出", summary = "回收", details = "reason=3",
            diagnostic = DiagnosticMetadata(source = DiagnosticSource.PROCESS_EXIT,
                severity = DiagnosticSeverity.WARNING, outcome = DiagnosticOutcome.INTERRUPTED,
                client = client, attributes = linkedMapOf("pss_kb" to "81920", "rss_kb" to "131072", "trace_available" to "false")))
        assertEquals(listOf("最后采样 PSS" to "81920 KB", "最后采样 RSS" to "131072 KB", "系统追踪附件" to "未提供"), log.systemDataValues())
        assertFalse(log.isFailure())
        assertEquals("系统进程退出", log.systemErrorType())
        assertEquals("系统内存回收", log.copy(diagnostic = log.diagnostic!!.copy(attributes = mapOf("reason_code" to "3"))).systemErrorType())
    }

    @Test fun `explicit exception type survives summary-only detail loading`() {
        val log = LogEntry.ErrorLog(name = "渲染", summary = "预览失败", details = "",
            diagnostic = DiagnosticMetadata(source = DiagnosticSource.RENDERER,
                errorType = "java.lang.IllegalArgumentException", client = client))
        assertEquals("java.lang.IllegalArgumentException", log.systemErrorType())
        assertEquals("渲染器", log.systemSourceLabel())
        assertTrue(log.systemDataValues().isEmpty())
    }

    @Test fun `ordinary events and storage warnings are not labeled as application errors`() {
        assertEquals("信息事件", LogEntry.TextLog(tag = "app", message = "ready").systemErrorType())
        val warning = LogEntry.TextLog(tag = "LOG_STORAGE", message = "dropped=2",
            diagnostic = DiagnosticMetadata(source = DiagnosticSource.LOG_STORAGE, severity = DiagnosticSeverity.WARNING))
        assertEquals("日志采集告警", warning.systemErrorType())
    }
}
