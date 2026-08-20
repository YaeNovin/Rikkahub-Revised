package me.rerere.rikkahub.data.ai.transformers

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.document.DocxParser
import me.rerere.document.EpubParser
import me.rerere.document.PdfParser
import me.rerere.document.PptxParser
import me.rerere.document.XlsxParser
import java.io.File
import java.net.URI
import java.util.LinkedHashMap

object DocumentAsPromptTransformer : InputMessageTransformer {
    private data class CacheKey(
        val path: String,
        val size: Long,
        val lastModified: Long,
    )

    private val contentCache = object : LinkedHashMap<CacheKey, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CacheKey, String>?): Boolean =
            size > MAX_CACHE_ENTRIES
    }

    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> = transformDocumentContents(messages)

    /** Used by rolling-context accounting so it sees the same text later sent to the provider. */
    suspend fun transformDocumentContents(messages: List<UIMessage>): List<UIMessage> =
        withContext(Dispatchers.IO) {
            messages.map { message ->
                message.copy(
                    parts = message.parts.toMutableList().apply {
                        val documents = filterIsInstance<UIMessagePart.Document>()
                        documents.asReversed().forEach { document ->
                            val content = readDocumentContent(document)
                            val path = resolveWorkspacePath(document)
                            val pathAttr = path?.let { " path=\"${it.escapeXmlAttribute()}\"" } ?: ""
                            val prompt = """
                                <UploadFile name="${document.fileName.escapeXmlAttribute()}"$pathAttr>
                                ```
                                $content
                                ```
                                </UploadFile>
                            """.trimIndent()
                            add(0, UIMessagePart.Text(prompt))
                        }
                    }
                )
            }
        }

    private fun parsePdfAsText(file: File): String {
        return PdfParser.parserPdf(file)
    }

    private fun parseDocxAsText(file: File): String {
        return DocxParser.parse(file)
    }

    private fun parsePptxAsText(file: File): String {
        return PptxParser.parse(file)
    }

    private fun parseXlsxAsText(file: File): String {
        return XlsxParser.parse(file)
    }

    private fun parseEpubAsText(file: File): String {
        return EpubParser.parse(file)
    }

    // 上传文件保存在 filesDir/upload 下, 该目录通过 proot 挂载到 workspace 的 /upload
    // 返回文件在 workspace 内的绝对路径, 便于 AI 用 workspace 工具直接读取原始文件
    private fun resolveWorkspacePath(document: UIMessagePart.Document): String? {
        val file = document.resolveLocalFile() ?: return null
        if (file.parentFile?.name != "upload") return null
        return "/upload/${file.name}"
    }

    private fun readDocumentContent(document: UIMessagePart.Document): String {
        val file = document.resolveLocalFile()
            ?: return "[ERROR, invalid file uri: ${document.fileName}]"
        if (!file.exists() || !file.isFile) {
            return "[ERROR, file not found: ${document.fileName}]"
        }
        val cacheKey = CacheKey(file.absolutePath, file.length(), file.lastModified())
        synchronized(contentCache) { contentCache[cacheKey] }?.let { return it }

        val content = runCatching {
            val extension = document.fileName.substringAfterLast('.', "").lowercase()
            val mime = document.mime.substringBefore(';').trim().lowercase()
            when {
                extension == "pdf" || mime == "application/pdf" -> parsePdfAsText(file)
                extension == "docx" || mime == DOCX_MIME -> parseDocxAsText(file)
                extension == "xlsx" || mime == XLSX_MIME -> parseXlsxAsText(file)
                extension == "pptx" || mime == PPTX_MIME -> parsePptxAsText(file)
                extension == "epub" || mime == EPUB_MIME -> parseEpubAsText(file)
                else -> file.readText()
            }
        }.getOrElse {
            "[ERROR, failed to read file: ${document.fileName}; ${it.message.orEmpty()}]"
        }
        if (!content.startsWith("[ERROR,")) {
            synchronized(contentCache) { contentCache[cacheKey] = content }
        }
        return content
    }

    private fun UIMessagePart.Document.resolveLocalFile(): File? = runCatching {
        when {
            url.startsWith("file:", ignoreCase = true) -> File(URI(url))
            else -> File(url)
        }
    }.getOrNull()

    private fun String.escapeXmlAttribute(): String = replace("&", "&amp;")
        .replace("\"", "&quot;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    private const val MAX_CACHE_ENTRIES = 16
    private const val DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    private const val XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    private const val PPTX_MIME = "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    private const val EPUB_MIME = "application/epub+zip"
}
