package me.rerere.rikkahub.data.files

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.provider.MediaInputSupport
import me.rerere.ai.provider.providers.openai.validateResponsesMediaAttachments
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.utils.isMidiFileType

/** Import raw media off the UI thread; rollback a failed batch without changing the draft. */
internal suspend fun importChatMedia(
    context: Context,
    files: FilesManager,
    uris: List<Uri>,
    audio: Boolean,
    support: MediaInputSupport,
    existing: List<UIMessagePart>,
): List<UIMessagePart> {
    // Keep ownership outside withContext so cancellation while delivering the IO result also rolls back.
    val importedIds = mutableListOf<Long>()
    try {
        return withContext(Dispatchers.IO) {
            require(if (audio) support.audio else support.video) { "当前模型或协议未启用此类音视频附件。" }
            val existingSize = existing.sumOf { part ->
                val url = when (part) {
                    is UIMessagePart.Audio -> part.url
                    is UIMessagePart.Video -> part.url
                    else -> return@sumOf 0L
                }
                FileUtils.getFileSize(context, url.toUri()) ?: 0L
            }
            var remaining = support.maxInlineBytes?.minus(existingSize)
            val parts = uris.map { source ->
                val name = files.getFileNameFromUri(source) ?: "file"
                val mime = files.getFileMimeType(source) ?: "application/octet-stream"
                val midi = audio && isMidiFileType(name, mime)
                if (!midi) remaining?.let { budget ->
                    require(budget > 0 && (FileUtils.getFileSize(context, source) ?: 0L) <= budget) {
                        "本次音视频原文件合计超过 14 MiB，请缩短文件或减少附件。"
                    }
                }
                val saved = files.saveManagedFromUri(
                    folder = FileFolders.UPLOAD, uri = source, displayName = name, mimeType = mime,
                    maxBytes = if (midi) null else remaining,
                )
                importedIds += saved.id
                val file = files.getFile(saved)
                val url = file.toUri().toString()
                if (midi) {
                    UIMessagePart.Document(url, name, mime)
                } else {
                    remaining = remaining?.minus(file.length())
                    val metadata = buildJsonObject {
                        put("file_name", name)
                        if (mime != "application/octet-stream") put("mime_type", mime)
                    }
                    if (audio) UIMessagePart.Audio(url, metadata) else UIMessagePart.Video(url, metadata)
                }
            }
            if (support.responsesExtensions) validateResponsesMediaAttachments(existing + parts, support)
            parts
        }
    } catch (error: Throwable) {
        withContext(NonCancellable + Dispatchers.IO) {
            importedIds.forEach { id ->
                runCatching { files.delete(id) }.onFailure { error.addSuppressed(it) }
            }
        }
        throw error
    }
}
