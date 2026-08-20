package me.rerere.document

import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import javax.xml.parsers.SAXParserFactory

private data class WorkbookSheet(
    val name: String,
    val relationshipId: String,
)

/** Extracts readable, sheet-labelled TSV from an OOXML workbook without loading the workbook at once. */
object XlsxParser {
    fun parse(file: File): String = ZipFile(file).use { zip ->
        val sharedStrings = zip.getEntry(SHARED_STRINGS_PATH)?.let { entry ->
            zip.getInputStream(entry).use(::parseSharedStrings)
        }.orEmpty()
        val relationships = zip.getEntry(WORKBOOK_RELS_PATH)?.let { entry ->
            zip.getInputStream(entry).use(::parseRelationships)
        }.orEmpty()
        val workbookSheets = zip.getEntry(WORKBOOK_PATH)?.let { entry ->
            zip.getInputStream(entry).use(::parseWorkbook)
        }.orEmpty()

        val sheets = workbookSheets.mapNotNull { sheet ->
            val path = relationships[sheet.relationshipId] ?: return@mapNotNull null
            zip.getEntry(path)?.let { entry -> sheet.name to entry }
        }.ifEmpty {
            zip.entries().toList()
                .filter { it.name.matches(WORKSHEET_PATH_PATTERN) }
                .sortedBy(::worksheetNumber)
                .mapIndexed { index, entry -> "Sheet ${index + 1}" to entry }
        }

        require(sheets.isNotEmpty()) { "No worksheets found in XLSX file" }
        buildString {
            for ((sheetName, entry) in sheets) {
                if (length >= MAX_WORKBOOK_OUTPUT_CHARS) break
                if (isNotEmpty()) appendLine()
                append("## Sheet: ")
                appendLine(sheetName.replace('\n', ' ').replace('\r', ' '))
                appendLine()
                val remaining = MAX_WORKBOOK_OUTPUT_CHARS - length
                append(
                    zip.getInputStream(entry).use { stream ->
                        parseWorksheet(stream, sharedStrings, remaining)
                    }
                )
            }
            if (length >= MAX_WORKBOOK_OUTPUT_CHARS) {
                setLength(MAX_WORKBOOK_OUTPUT_CHARS)
                appendLine()
                append("[Workbook content truncated after $MAX_WORKBOOK_OUTPUT_CHARS characters]")
            }
        }.trim()
    }

    private fun parseWorkbook(input: InputStream): List<WorkbookSheet> {
        val sheets = mutableListOf<WorkbookSheet>()
        parseXml(input, object : DefaultHandler() {
            override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes) {
                if (elementName(localName, qName) != "sheet") return
                val name = attributes.value("name").orEmpty()
                val relationshipId = attributes.value("id").orEmpty()
                if (name.isNotBlank() && relationshipId.isNotBlank()) {
                    sheets += WorkbookSheet(name, relationshipId)
                }
            }
        })
        return sheets
    }

    private fun parseRelationships(input: InputStream): Map<String, String> {
        val relationships = mutableMapOf<String, String>()
        parseXml(input, object : DefaultHandler() {
            override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes) {
                if (elementName(localName, qName) != "Relationship") return
                val id = attributes.value("Id")
                val target = attributes.value("Target")
                if (!id.isNullOrBlank() && !target.isNullOrBlank()) {
                    relationships[id] = normalizeWorkbookTarget(target)
                }
            }
        })
        return relationships
    }

    private fun parseSharedStrings(input: InputStream): List<String> {
        val strings = mutableListOf<String>()
        parseXml(input, object : DefaultHandler() {
            private var current: StringBuilder? = null
            private var inText = false

            override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
                when (elementName(localName, qName)) {
                    "si" -> current = StringBuilder()
                    "t" -> if (current != null) inText = true
                }
            }

            override fun characters(characters: CharArray, start: Int, length: Int) {
                if (!inText) return
                val builder = current ?: return
                val remaining = MAX_SHARED_STRING_CHARS - builder.length
                if (remaining > 0) builder.append(characters, start, minOf(length, remaining))
            }

            override fun endElement(uri: String?, localName: String?, qName: String?) {
                when (elementName(localName, qName)) {
                    "t" -> inText = false
                    "si" -> {
                        strings += current?.toString().orEmpty()
                        current = null
                    }
                }
            }
        })
        return strings
    }

    private fun parseWorksheet(
        input: InputStream,
        sharedStrings: List<String>,
        maxOutputChars: Int,
    ): String {
        val output = StringBuilder()
        var truncated = false
        parseXml(input, object : DefaultHandler() {
            private var rowCells = sortedMapOf<Int, String>()
            private var cellColumn = -1
            private var cellType = ""
            private var cellValue = StringBuilder()
            private var inlineValue = StringBuilder()
            private var formula = StringBuilder()
            private var capture: String? = null

            override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes) {
                if (truncated) return
                when (elementName(localName, qName)) {
                    "row" -> rowCells = sortedMapOf()
                    "c" -> {
                        cellColumn = columnIndex(attributes.value("r").orEmpty())
                        cellType = attributes.value("t").orEmpty()
                        cellValue = StringBuilder()
                        inlineValue = StringBuilder()
                        formula = StringBuilder()
                    }
                    "v" -> capture = "v"
                    "f" -> capture = "f"
                    "t" -> if (cellColumn >= 0) capture = "t"
                }
            }

            override fun characters(characters: CharArray, start: Int, length: Int) {
                if (truncated) return
                when (capture) {
                    "v" -> cellValue.append(characters, start, length)
                    "f" -> formula.append(characters, start, length)
                    "t" -> inlineValue.append(characters, start, length)
                }
            }

            override fun endElement(uri: String?, localName: String?, qName: String?) {
                if (truncated) return
                when (elementName(localName, qName)) {
                    "v", "f", "t" -> capture = null
                    "c" -> if (cellColumn in 0 until MAX_EXCEL_COLUMNS) {
                        rowCells[cellColumn] = formatCellValue(
                            type = cellType,
                            rawValue = cellValue.toString(),
                            inlineValue = inlineValue.toString(),
                            formula = formula.toString(),
                            sharedStrings = sharedStrings,
                        )
                    }
                    "row" -> if (rowCells.isNotEmpty()) {
                        appendRow(output, rowCells)
                        truncated = output.length >= maxOutputChars
                    }
                }
            }
        })

        if (output.length > maxOutputChars) output.setLength(maxOutputChars)
        if (truncated) {
            output.appendLine()
            output.append("[Worksheet content truncated]")
        }
        return output.toString().trimEnd()
    }

    private fun formatCellValue(
        type: String,
        rawValue: String,
        inlineValue: String,
        formula: String,
        sharedStrings: List<String>,
    ): String {
        val value = when (type) {
            "s" -> rawValue.toIntOrNull()?.let(sharedStrings::getOrNull) ?: rawValue
            "inlineStr" -> inlineValue
            "b" -> if (rawValue == "1") "TRUE" else "FALSE"
            else -> inlineValue.ifBlank { rawValue }
        }.sanitizeCell()
        val normalizedFormula = formula.sanitizeCell()
        return when {
            normalizedFormula.isBlank() -> value
            value.isBlank() -> "=$normalizedFormula"
            else -> "=$normalizedFormula -> $value"
        }
    }

    private fun appendRow(output: StringBuilder, cells: Map<Int, String>) {
        val lastColumn = cells.keys.maxOrNull() ?: return
        for (column in 0..lastColumn) {
            if (column > 0) output.append('\t')
            output.append(cells[column].orEmpty())
        }
        output.appendLine()
    }

    private fun columnIndex(reference: String): Int {
        var value = 0
        var found = false
        for (character in reference) {
            if (!character.isLetter()) break
            found = true
            value = value * 26 + (character.uppercaseChar() - 'A' + 1)
        }
        return if (found) value - 1 else -1
    }

    private fun normalizeWorkbookTarget(target: String): String {
        val raw = if (target.startsWith('/')) target.drop(1) else "xl/$target"
        val segments = ArrayDeque<String>()
        raw.split('/').forEach { segment ->
            when (segment) {
                "", "." -> Unit
                ".." -> if (segments.isNotEmpty()) segments.removeLast()
                else -> segments.addLast(segment)
            }
        }
        return segments.joinToString("/")
    }

    private fun parseXml(input: InputStream, handler: DefaultHandler) {
        val factory = SAXParserFactory.newInstance().apply {
            isNamespaceAware = true
        }
        // Android vendors expose slightly different SAX feature sets. Apply every hardening flag
        // supported by the current parser without making valid local workbooks unparseable.
        runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        factory.newSAXParser().parse(input, handler)
    }

    private fun Attributes.value(localName: String): String? {
        for (index in 0 until length) {
            if (getLocalName(index).equals(localName, ignoreCase = true) ||
                getQName(index).substringAfter(':').equals(localName, ignoreCase = true)
            ) {
                return getValue(index)
            }
        }
        return null
    }

    private fun elementName(localName: String?, qualifiedName: String?): String =
        localName?.takeIf(String::isNotBlank) ?: qualifiedName.orEmpty().substringAfter(':')

    private fun String.sanitizeCell(): String = replace('\t', ' ')
        .replace("\r\n", "\\n")
        .replace('\r', '\n')
        .replace("\n", "\\n")
        .trim()

    private fun worksheetNumber(entry: ZipEntry): Int =
        entry.name.substringAfterLast("sheet").substringBefore('.').toIntOrNull() ?: Int.MAX_VALUE

    private const val WORKBOOK_PATH = "xl/workbook.xml"
    private const val WORKBOOK_RELS_PATH = "xl/_rels/workbook.xml.rels"
    private const val SHARED_STRINGS_PATH = "xl/sharedStrings.xml"
    private val WORKSHEET_PATH_PATTERN = Regex("xl/worksheets/sheet\\d+\\.xml")
    private const val MAX_EXCEL_COLUMNS = 16_384
    private const val MAX_SHARED_STRING_CHARS = 100_000
    private const val MAX_WORKBOOK_OUTPUT_CHARS = 1_000_000
}
