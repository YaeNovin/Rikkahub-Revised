package me.rerere.document

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DocxParserTest {
    @Test
    fun `extracts text when the platform pull parser is unavailable`() {
        val document = File.createTempFile("docx-parser-", ".docx").apply { deleteOnExit() }
        ZipOutputStream(document.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("word/document.xml"))
            zip.write(
                """
                    <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                      <w:body>
                        <w:p><w:r><w:t>Project report</w:t></w:r></w:p>
                        <w:p><w:r><w:t>Second paragraph</w:t></w:r></w:p>
                      </w:body>
                    </w:document>
                """.trimIndent().toByteArray()
            )
            zip.closeEntry()
        }

        val parsed = DocxParser.parse(document)

        assertTrue(parsed.contains("Project report"))
        assertTrue(parsed.contains("Second paragraph"))
    }
}
