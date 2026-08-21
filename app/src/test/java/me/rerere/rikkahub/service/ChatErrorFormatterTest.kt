package me.rerere.rikkahub.service

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatErrorFormatterTest {
    @Test
    fun `classifies common software failures without exposing raw text`() {
        assertEquals(SoftwareFailureKind.MIDI, classifySoftwareFailure("Invalid MIDI header"))
        assertEquals(SoftwareFailureKind.DOCUMENT, classifySoftwareFailure("Failed to parse DOCX document"))
        assertEquals(SoftwareFailureKind.IMAGE, classifySoftwareFailure("Failed to decode image"))
        assertEquals(SoftwareFailureKind.RESTORE, classifySoftwareFailure("Restore failed"))
        assertEquals(SoftwareFailureKind.PERMISSION, classifySoftwareFailure("Access denied"))
        assertEquals(SoftwareFailureKind.CONFIGURATION, classifySoftwareFailure("Invalid provider configuration"))
        assertEquals(SoftwareFailureKind.GENERIC, classifySoftwareFailure("Internal operation failed"))
    }

    @Test
    fun `classifies localized provider and network failures stored as text`() {
        assertEquals(SoftwareFailureKind.RATE_LIMIT, classifySoftwareFailure("HTTP 429 Too Many Requests"))
        assertEquals(SoftwareFailureKind.AUTHENTICATION, classifySoftwareFailure("status code: 401"))
        assertEquals(SoftwareFailureKind.SERVER_TEMPORARY, classifySoftwareFailure("502 Bad Gateway"))
        assertEquals(SoftwareFailureKind.NETWORK, classifySoftwareFailure("Software caused connection abort"))
        assertEquals(SoftwareFailureKind.NETWORK, classifySoftwareFailure("stream was reset: CANCEL"))
        assertEquals(SoftwareFailureKind.TIMEOUT, classifySoftwareFailure("request timed out"))
        assertEquals(SoftwareFailureKind.RATE_LIMIT, classifySoftwareFailure("请求过多，请稍后再试"))
        assertEquals(SoftwareFailureKind.AUTHENTICATION, classifySoftwareFailure("API 密钥未授权"))
        assertEquals(SoftwareFailureKind.SERVER_TEMPORARY, classifySoftwareFailure("上游服务器错误"))
        assertEquals(SoftwareFailureKind.NETWORK, classifySoftwareFailure("网络切换后连接被中止"))
    }

    @Test
    fun `classifies existing Chinese fallback messages for English localization`() {
        assertEquals(SoftwareFailureKind.FILE_READ, classifySoftwareFailure("读取文件失败"))
        assertEquals(SoftwareFailureKind.FILE_WRITE, classifySoftwareFailure("保存失败"))
        assertEquals(SoftwareFailureKind.DELETE, classifySoftwareFailure("删除失败"))
        assertEquals(SoftwareFailureKind.TOOL, classifySoftwareFailure("MCP 工具调用失败"))
    }
}
