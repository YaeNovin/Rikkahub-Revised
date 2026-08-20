package me.rerere.rikkahub.data.ai

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import java.io.File
import java.net.URI

internal const val HISTORICAL_LARGE_IMAGE_BYTES = 8L * 1024L * 1024L

/**
 * Keeps media on the current user turn, but avoids resending costly media that
 * was already processed in an earlier turn. Stored messages are never changed.
 */
internal fun List<UIMessage>.compactHistoricalMediaForRequest(
    largeImageBytes: Long = HISTORICAL_LARGE_IMAGE_BYTES,
    mediaSizeBytes: (String) -> Long? = { null },
): List<UIMessage> {
    val currentUserIndex = indexOfLast { it.role == MessageRole.USER }
    if (currentUserIndex < 0) return this

    return mapIndexed { messageIndex, message ->
        if (messageIndex >= currentUserIndex) return@mapIndexed message

        var changed = false
        val requestParts = message.parts.map { part ->
            val replacement = when (part) {
                is UIMessagePart.Video -> HISTORICAL_VIDEO_PLACEHOLDER
                is UIMessagePart.Audio -> HISTORICAL_AUDIO_PLACEHOLDER
                is UIMessagePart.Image -> if (
                    part.url.estimatedMediaBytes(mediaSizeBytes) >= largeImageBytes
                ) {
                    HISTORICAL_LARGE_IMAGE_PLACEHOLDER
                } else {
                    null
                }
                else -> null
            }
            if (replacement == null) {
                part
            } else {
                changed = true
                UIMessagePart.Text(replacement)
            }
        }
        if (changed) message.copy(parts = requestParts) else message
    }
}

private fun String.estimatedMediaBytes(mediaSizeBytes: (String) -> Long?): Long {
    mediaSizeBytes(this)?.takeIf { it >= 0L }?.let { return it }
    if (startsWith("data:", ignoreCase = true)) {
        val payload = substringAfter(";base64,", missingDelimiterValue = "")
        if (payload.isNotEmpty()) return payload.length.toLong() * 3L / 4L
    }
    return runCatching {
        val file = when {
            startsWith("file://", ignoreCase = true) -> File(URI(this))
            File(this).isAbsolute -> File(this)
            else -> return@runCatching null
        }
        file.takeIf(File::isFile)?.length()
    }.getOrNull() ?: 0L
}

private const val HISTORICAL_VIDEO_PLACEHOLDER =
    "[Earlier video attachment omitted after it was processed in its original turn.]"
private const val HISTORICAL_AUDIO_PLACEHOLDER =
    "[Earlier audio attachment omitted after it was processed in its original turn.]"
private const val HISTORICAL_LARGE_IMAGE_PLACEHOLDER =
    "[Earlier large image attachment omitted after it was processed in its original turn.]"
