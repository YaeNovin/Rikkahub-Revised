package me.rerere.rikkahub.data.ai.transformers

import kotlinx.coroutines.runBlocking
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.context.createRollingContextPlan
import me.rerere.rikkahub.ui.components.ai.calculateChatContextUsage
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DocumentAsPromptTransformerTest {
    @Test
    fun `xlsx extension selects workbook parser when mime is generic`() = runBlocking {
        val workbook = File.createTempFile("generic-mime-", ".xlsx").apply { deleteOnExit() }
        ZipOutputStream(workbook.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
            zip.write(
                """
                    <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                      <sheetData><row><c r="A1" t="inlineStr"><is><t>Quarterly revenue</t></is></c></row></sheetData>
                    </worksheet>
                """.trimIndent().toByteArray()
            )
            zip.closeEntry()
        }
        val message = UIMessage(
            role = me.rerere.ai.core.MessageRole.USER,
            parts = listOf(
                UIMessagePart.Document(
                    url = workbook.toURI().toString(),
                    fileName = "financial-report.xlsx",
                    mime = "application/octet-stream",
                )
            ),
        )

        val transformed = DocumentAsPromptTransformer.transformDocumentContents(listOf(message))
        val prompt = transformed.single().parts.filterIsInstance<UIMessagePart.Text>().single().text

        assertTrue(prompt.contains("## Sheet: Sheet 1"))
        assertTrue(prompt.contains("Quarterly revenue"))
        assertTrue(prompt.contains("<UploadFile name=\"financial-report.xlsx\""))
    }

    @Test
    fun `parsed document text contributes to display and automatic compression`() = runBlocking {
        val document = File.createTempFile("context-accounting-", ".txt").apply {
            deleteOnExit()
            writeText("document body ".repeat(2_000))
        }
        val sourceMessages = listOf(
            UIMessage(
                role = me.rerere.ai.core.MessageRole.USER,
                parts = listOf(
                    UIMessagePart.Document(
                        url = document.toURI().toString(),
                        fileName = "large-notes.txt",
                        mime = "text/plain",
                    )
                ),
            ),
            UIMessage.assistant("I have read the file."),
            UIMessage.user("Summarize it."),
        )

        val transformed = DocumentAsPromptTransformer.transformDocumentContents(sourceMessages)
        val usage = calculateChatContextUsage(
            messages = transformed,
            capacityTokens = 32_000,
        )

        assertTrue(usage.usedTokens > 5_000)
        assertNotNull(
            createRollingContextPlan(
                messages = transformed,
                storedSummary = null,
                thresholdTokens = 4_000,
            )
        )
    }
}
