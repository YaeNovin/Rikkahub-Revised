package me.rerere.rikkahub.utils

import org.junit.Assert.*
import org.junit.Test

class ProcessExitReporterTest {
    @Test fun `system and ROM memory kills are recorded without treating ordinary force stops as crashes`() {
        assertEquals("系统内存不足回收", processExitCategory(3, null))
        assertEquals("ROM 内存清理", processExitCategory(10, "stop due to low memory"))
        assertNull(processExitCategory(10, "stop due to single-cleaner"))
        assertNull(processExitCategory(16, "package update"))
        assertEquals("输入响应超时（ANR）", processExitCategory(6, "Input dispatching timed out"))
        assertEquals("原生崩溃", processExitCategory(5, null))
    }
}
