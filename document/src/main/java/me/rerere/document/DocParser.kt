package me.rerere.document

import org.apache.poi.hwpf.extractor.WordExtractor
import org.apache.poi.hwpf.extractor.Word6Extractor
import java.io.File

/** Extracts readable text from OLE2-based Word 6-2003 DOC files. */
object DocParser {
    fun parse(file: File): String = runCatching {
        file.inputStream().use { input ->
            WordExtractor(input).use { extractor -> extractor.text.normalizeDocText() }
        }
    }.recoverCatching {
        file.inputStream().use { input ->
            Word6Extractor(input).use { extractor -> extractor.text.normalizeDocText() }
        }
    }.getOrThrow()

    private fun String.normalizeDocText(): String = replace("\r\n", "\n")
        .replace('\r', '\n')
        .trim()
        .ifBlank { error("No readable text found in DOC file") }
}
