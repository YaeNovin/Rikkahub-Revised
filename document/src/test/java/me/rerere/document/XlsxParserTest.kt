package me.rerere.document

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class XlsxParserTest {
    @Test
    fun `parses shared strings inline values formulas and multiple sheets`() {
        val workbook = createWorkbook(
            mapOf(
                "xl/workbook.xml" to """
                    <workbook xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                      <sheets>
                        <sheet name="Summary" r:id="rId1"/>
                        <sheet name="Details" r:id="rId2"/>
                      </sheets>
                    </workbook>
                """.trimIndent(),
                "xl/_rels/workbook.xml.rels" to """
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                      <Relationship Id="rId1" Target="worksheets/sheet1.xml"/>
                      <Relationship Id="rId2" Target="worksheets/sheet2.xml"/>
                    </Relationships>
                """.trimIndent(),
                "xl/sharedStrings.xml" to """
                    <sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                      <si><t>Name</t></si>
                      <si><r><t>Total</t></r><r><t> amount</t></r></si>
                    </sst>
                """.trimIndent(),
                "xl/worksheets/sheet1.xml" to """
                    <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                      <sheetData>
                        <row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c></row>
                        <row r="2"><c r="A2" t="inlineStr"><is><t>Alpha</t></is></c><c r="B2"><f>SUM(B3:B4)</f><v>42</v></c></row>
                      </sheetData>
                    </worksheet>
                """.trimIndent(),
                "xl/worksheets/sheet2.xml" to """
                    <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                      <sheetData><row r="1"><c r="A1" t="b"><v>1</v></c></row></sheetData>
                    </worksheet>
                """.trimIndent(),
            )
        )

        val parsed = XlsxParser.parse(workbook)

        assertTrue(parsed.contains("## Sheet: Summary"))
        assertTrue(parsed.contains("Name\tTotal amount"))
        assertTrue(parsed.contains("Alpha\t=SUM(B3:B4) -> 42"))
        assertTrue(parsed.contains("## Sheet: Details"))
        assertTrue(parsed.contains("TRUE"))
    }

    @Test
    fun `falls back to worksheet entries when workbook relationships are absent`() {
        val workbook = createWorkbook(
            mapOf(
                "xl/worksheets/sheet1.xml" to """
                    <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                      <sheetData><row><c r="A1" t="inlineStr"><is><t>Visible</t></is></c></row></sheetData>
                    </worksheet>
                """.trimIndent(),
            )
        )

        val parsed = XlsxParser.parse(workbook)

        assertTrue(parsed.contains("## Sheet: Sheet 1"))
        assertTrue(parsed.contains("Visible"))
    }

    private fun createWorkbook(entries: Map<String, String>): File {
        val file = File.createTempFile("xlsx-parser-", ".xlsx").apply { deleteOnExit() }
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return file
    }
}
