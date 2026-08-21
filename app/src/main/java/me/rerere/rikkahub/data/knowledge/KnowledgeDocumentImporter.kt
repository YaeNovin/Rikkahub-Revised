package me.rerere.rikkahub.data.knowledge

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.net.toFile
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.ai.provider.EmbeddingImageInput
import me.rerere.ai.provider.EmbeddingGenerationParams
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.usesVolcengineMultimodalEmbeddingApi
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.transformers.OcrTransformer
import me.rerere.document.DocxParser
import me.rerere.document.DocParser
import me.rerere.document.EpubParser
import me.rerere.document.PdfParser
import me.rerere.document.PptxParser
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.resolveEmbeddingModel
import me.rerere.rikkahub.data.db.entity.KnowledgeBaseEntity
import me.rerere.rikkahub.data.db.entity.KnowledgeChunkEntity
import me.rerere.rikkahub.data.db.entity.KnowledgeDocumentEntity
import me.rerere.rikkahub.data.repository.KnowledgeBaseRepository
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.time.Clock
import kotlin.uuid.Uuid

private const val CHUNK_SIZE = 1400
private const val CHUNK_OVERLAP = 180
private const val EMBEDDING_BATCH_SIZE = 32
private const val MAX_SOURCE_FILE_BYTES = 20L * 1024 * 1024
private const val MAX_SOURCE_FILE_SIZE_MIB = 20
private const val MAX_EXTRACTED_TEXT_CHARS = 3_000_000
private val PAGE_MARKER_REGEX = Regex("^(?:---)?Page\\s+(\\d+):\\s*$", RegexOption.IGNORE_CASE)
private val HEADING_REGEX = Regex("^(#{1,6})\\s+(.+)$")
private val SLIDE_MARKER_REGEX = Regex("^Slide\\s+(\\d+)$", RegexOption.IGNORE_CASE)
private val MIME_TYPES_BY_EXTENSION = mapOf(
    "pdf" to "application/pdf",
    "doc" to "application/msword",
    "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    "epub" to "application/epub+zip",
)
private val VISION_EMBEDDING_IMAGE_TYPES = setOf("image/jpeg", "image/png", "image/webp")

class KnowledgeDocumentImporter(
    private val context: Context,
    private val repository: KnowledgeBaseRepository,
    private val providerManager: ProviderManager,
    private val settingsStore: SettingsStore,
) {
    suspend fun importDocument(base: KnowledgeBaseEntity, uri: Uri): KnowledgeDocumentEntity = withContext(Dispatchers.IO) {
        val documentId = Uuid.random().toString()
        val now = Clock.System.now().toEpochMilliseconds()
        val title = resolveDisplayName(uri)
        val mimeType = resolveMimeType(uri, title)
        val document = KnowledgeDocumentEntity(
            id = documentId,
            knowledgeBaseId = base.id,
            title = title,
            sourceUri = uri.toString(),
            mimeType = mimeType,
            // The final hash is written after parsing. This placeholder makes every failed import visible.
            contentHash = sha256("$uri:$now"),
            status = KnowledgeDocumentEntity.STATUS_INDEXING,
            createdAt = now,
            updatedAt = now,
        )
        repository.insertDocumentWithChunks(document, emptyList())
        var temporaryFile: File? = null

        try {
            persistReadPermission(uri)
            temporaryFile = File.createTempFile("knowledge-", ".source", context.cacheDir)
            copyToTemporaryFile(uri, temporaryFile)
            val settings = settingsStore.settingsFlow.value
            val model = base.embeddingModelId
                ?.let { runCatching { Uuid.parse(it) }.getOrNull() }
                ?.let { settings.resolveEmbeddingModel(it) }
                ?: settings.resolveEmbeddingModel()
            val visualImage = temporaryFile.toVisionEmbeddingImage(mimeType, model?.usesVolcengineMultimodalEmbeddingApi() == true)
            val content = when {
                visualImage != null -> "[图片文档：$title]"
                mimeType.startsWith("image/") && model == null -> "[图片文档：$title]"
                else -> readContent(temporaryFile, mimeType, settings)
            }
            require(content.isNotBlank()) { "Document has no readable text" }
            require(content.length <= MAX_EXTRACTED_TEXT_CHARS) {
                "Extracted text is too large. Limit is $MAX_EXTRACTED_TEXT_CHARS characters"
            }

            val drafts = chunkContent(content)
            val chunks = drafts.mapIndexed { index, draft ->
                KnowledgeChunkEntity(
                    id = Uuid.random().toString(),
                    documentId = documentId,
                    knowledgeBaseId = base.id,
                    ordinal = index,
                    content = draft.content,
                    pageStart = draft.pageStart,
                    pageEnd = draft.pageEnd,
                    sectionPath = draft.sectionPath,
                    charStart = draft.charStart,
                    charEnd = draft.charEnd,
                )
            }
            val indexedDocument = document.copy(
                contentHash = sha256(content),
                pageCount = drafts.mapNotNull { it.pageEnd }.maxOrNull(),
            )
            repository.insertDocumentWithChunks(indexedDocument, chunks)

            if (model == null) {
                repository.updateDocumentStatus(
                    documentId,
                    KnowledgeDocumentEntity.STATUS_READY_WITHOUT_EMBEDDING,
                )
                return@withContext indexedDocument.copy(status = KnowledgeDocumentEntity.STATUS_READY_WITHOUT_EMBEDDING)
            }

            val providerSetting = model.findProvider(settings.providers)
                ?: error("Embedding provider not found for ${model.modelId}")
            val provider = providerManager.getProviderByType(providerSetting)
            require(visualImage == null || chunks.size == 1) {
                "An image document must produce exactly one embedding chunk"
            }
            val embeddingBatchSize = if (model.usesVolcengineMultimodalEmbeddingApi()) {
                1
            } else {
                EMBEDDING_BATCH_SIZE
            }
            chunks.chunked(embeddingBatchSize).forEach { batch ->
                val result = provider.generateEmbedding(
                    providerSetting = providerSetting,
                    params = EmbeddingGenerationParams(
                        model = model,
                        input = if (visualImage == null) batch.map { it.content } else emptyList(),
                        images = visualImage?.let(::listOf).orEmpty(),
                        customHeaders = model.customHeaders,
                        customBody = model.customBodies,
                    )
                )
                require(result.embeddings.size == batch.size) {
                    "Embedding provider returned ${result.embeddings.size} vectors for ${batch.size} chunks"
                }
                val dimensions = result.embeddings.map { it.size }.distinct()
                require(dimensions.size == 1 && dimensions.single() > 0) {
                    "Embedding provider returned inconsistent vector dimensions: $dimensions"
                }
                require(result.embeddings.flatten().all(Float::isFinite)) {
                    "Embedding provider returned a non-finite vector"
                }
                repository.updateChunks(batch.zip(result.embeddings).map { (chunk, vector) ->
                    chunk.copy(
                        embedding = vector.toByteArray(),
                        embeddingModelId = model.id.toString(),
                        embeddingDimension = vector.size,
                    )
                })
            }
            repository.updateDocumentStatus(documentId, KnowledgeDocumentEntity.STATUS_READY)
            indexedDocument.copy(status = KnowledgeDocumentEntity.STATUS_READY)
        } catch (error: Throwable) {
            repository.updateDocumentStatus(
                id = documentId,
                status = KnowledgeDocumentEntity.STATUS_FAILED,
                errorMessage = error.message?.take(500) ?: error.javaClass.simpleName,
            )
            throw error
        } finally {
            temporaryFile?.delete()
        }
    }

    private fun persistReadPermission(uri: Uri) {
        if (uri.scheme != "content") return
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
    }

    private fun copyToTemporaryFile(uri: Uri, target: File) {
        val input = if (uri.scheme == "file") {
            uri.toFile().inputStream()
        } else {
            context.contentResolver.openInputStream(uri)
                ?: error("Cannot open document $uri")
        }
        input.use { source ->
            target.outputStream().use { destination ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var totalBytes = 0L
                while (true) {
                    val read = source.read(buffer)
                    if (read <= 0) break
                    totalBytes += read
                    require(totalBytes <= MAX_SOURCE_FILE_BYTES) {
                        "File is too large. Limit is $MAX_SOURCE_FILE_SIZE_MIB MiB"
                    }
                    destination.write(buffer, 0, read)
                }
            }
        }
    }

    private suspend fun readContent(file: File, mimeType: String, settings: me.rerere.rikkahub.data.datastore.Settings): String = when (mimeType) {
        "application/pdf" -> PdfParser.parserPdf(file)
        "application/msword" -> DocParser.parse(file)
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> DocxParser.parse(file)
        "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> PptxParser.parse(file)
        "application/epub+zip" -> EpubParser.parse(file)
        else -> when {
            mimeType.startsWith("image/") -> readImageContent(file, mimeType, settings)
            mimeType.startsWith("text/") || mimeType in setOf("application/json", "application/xml") -> file.readText()
            else -> error("Unsupported document type: $mimeType")
        }
    }

    private suspend fun readImageContent(
        file: File,
        mimeType: String,
        settings: me.rerere.rikkahub.data.datastore.Settings,
    ): String {
        require(mimeType in VISION_EMBEDDING_IMAGE_TYPES) {
            "Unsupported image type: $mimeType. Supported image types are PNG, JPEG, and WebP"
        }
        require(settings.findModelById(settings.ocrModelId) != null) {
            "Image import requires a vision embedding model or a configured OCR model"
        }
        val result = OcrTransformer.performOcr(UIMessagePart.Image(file.toUri().toString()))
        require(result != "[Image]" && !result.startsWith("[ERROR,")) {
            "OCR failed while extracting text from $mimeType"
        }
        return result
    }

    private fun File.toVisionEmbeddingImage(
        mimeType: String,
        useVisionEmbedding: Boolean,
    ): EmbeddingImageInput? {
        if (!mimeType.startsWith("image/") || !useVisionEmbedding) return null
        require(mimeType in VISION_EMBEDDING_IMAGE_TYPES) {
            "Unsupported image type: $mimeType. Vision embedding supports PNG, JPEG, and WebP"
        }
        return EmbeddingImageInput(
            mimeType = mimeType,
            base64 = android.util.Base64.encodeToString(readBytes(), android.util.Base64.NO_WRAP),
        )
    }

    private fun resolveDisplayName(uri: Uri): String {
        val providerName = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    cursor.takeIf { it.moveToFirst() }
                        ?.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        ?.takeIf { it >= 0 }
                        ?.let(cursor::getString)
                }
        }.getOrNull()
        return providerName
            ?: uri.lastPathSegment?.substringAfterLast('/')
            ?.ifBlank { "Document" }
            ?: "Document"
    }

    private fun resolveMimeType(uri: Uri, title: String): String {
        val extension = title.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        return MIME_TYPES_BY_EXTENSION[extension]
            ?: context.contentResolver.getType(uri)
            ?.takeUnless { it.equals("application/octet-stream", ignoreCase = true) }
            ?: if (extension in setOf("txt", "md", "csv", "json", "xml")) "text/plain" else "application/octet-stream"
    }

    private data class ChunkDraft(
        val content: String,
        val pageStart: Int?,
        val pageEnd: Int?,
        val sectionPath: String,
        val charStart: Int,
        val charEnd: Int,
    )

    private fun chunkContent(content: String): List<ChunkDraft> {
        val result = mutableListOf<ChunkDraft>()
        val sectionPath = ArrayDeque<String>()
        var page: Int? = null
        var paragraph = StringBuilder()
        var paragraphStart = 0
        var paragraphPageStart: Int? = null
        var paragraphPageEnd: Int? = null
        var paragraphSection = ""
        var offset = 0

        fun flushParagraph() {
            val text = paragraph.toString().trim()
            if (text.isBlank()) return
            val start = paragraphStart
            val end = (start + text.length).coerceAtMost(content.length)
            val section = paragraphSection
            var cursor = 0
            while (cursor < text.length) {
                val sliceEnd = (cursor + CHUNK_SIZE).coerceAtMost(text.length)
                val slice = text.substring(cursor, sliceEnd)
                result += ChunkDraft(
                    content = slice,
                    pageStart = paragraphPageStart,
                    pageEnd = paragraphPageEnd,
                    sectionPath = section,
                    charStart = start + cursor,
                    charEnd = (start + sliceEnd).coerceAtMost(end),
                )
                if (sliceEnd == text.length) break
                cursor = (sliceEnd - CHUNK_OVERLAP).coerceAtLeast(cursor + 1)
            }
            paragraph = StringBuilder()
        }

        content.lineSequence().forEach { rawLine ->
            val line = rawLine.trimEnd()
            val pageMatch = PAGE_MARKER_REGEX.find(line)
            if (pageMatch != null) {
                flushParagraph()
                page = pageMatch.groupValues[1].toIntOrNull()
                offset += rawLine.length + 1
                return@forEach
            }
            val heading = HEADING_REGEX.find(line)
            if (heading != null) {
                flushParagraph()
                val level = heading.groupValues[1].length
                while (sectionPath.size >= level) sectionPath.removeLast()
                val headingText = heading.groupValues[2].trim()
                sectionPath.addLast(headingText)
                SLIDE_MARKER_REGEX.matchEntire(headingText)?.groupValues?.get(1)?.toIntOrNull()?.let {
                    page = it
                }
            }
            if (paragraph.isEmpty()) {
                paragraphStart = offset
                paragraphPageStart = page
                paragraphSection = sectionPath.joinToString(" / ")
            }
            if (line.isBlank()) {
                flushParagraph()
            } else {
                if (paragraph.isNotEmpty()) paragraph.append('\n')
                paragraph.append(line)
                paragraphPageEnd = page
            }
            offset += rawLine.length + 1
        }
        flushParagraph()
        return result
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
}

private fun List<Float>.toByteArray(): ByteArray {
    val buffer = ByteBuffer.allocate(size * 4).order(ByteOrder.LITTLE_ENDIAN)
    forEach(buffer::putFloat)
    return buffer.array()
}
