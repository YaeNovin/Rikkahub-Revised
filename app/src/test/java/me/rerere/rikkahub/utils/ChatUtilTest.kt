package me.rerere.rikkahub.utils

import org.junit.Assert.assertTrue
import org.junit.Test

class ChatUtilTest {
    @Test
    fun `office extensions are accepted when picker returns a generic mime`() {
        assertTrue(isAllowedFileType("report.docx", "application/octet-stream"))
        assertTrue(isAllowedFileType("budget.xlsx", "application/zip"))
        assertTrue(isAllowedFileType("slides.pptx", "application/octet-stream"))
    }

    @Test
    fun `mime matching ignores case and parameters`() {
        assertTrue(
            isAllowedFileType(
                "download",
                "Application/Vnd.Openxmlformats-Officedocument.Spreadsheetml.Sheet; charset=binary",
            )
        )
    }
}
