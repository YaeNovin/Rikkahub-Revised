package me.rerere.rikkahub.data.files

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

internal object AtomicMediaFileStore {
    fun cleanupStalePartials(directory: File, now: Long = System.currentTimeMillis()): Int =
        directory.listFiles().orEmpty().count { file ->
            file.isFile && file.name.endsWith(".part") &&
                now - file.lastModified() > 24L * 60 * 60 * 1000 && file.delete()
        }

    const val DEFAULT_MAX_VIDEO_BYTES: Long = 2L * 1024L * 1024L * 1024L
    private const val COPY_BUFFER_BYTES = 256 * 1024
    private const val MIN_FREE_SPACE_BYTES = 32L * 1024L * 1024L

    fun write(
        input: InputStream,
        target: File,
        expectedSizeBytes: Long? = null,
        maxBytes: Long = DEFAULT_MAX_VIDEO_BYTES,
        onProgress: (bytesWritten: Long, expectedSizeBytes: Long?) -> Unit = { _, _ -> },
    ): Long {
        require(maxBytes > 0L) { "Maximum file size must be positive" }
        require(expectedSizeBytes == null || expectedSizeBytes in 1..maxBytes) {
            "Media file size exceeds the configured limit"
        }
        val parent = requireNotNull(target.parentFile) { "Media target must have a parent directory" }
        check(parent.exists() || parent.mkdirs()) { "Unable to create media directory" }
        val requiredSpace = expectedSizeBytes?.plus(MIN_FREE_SPACE_BYTES)
        if (requiredSpace != null) {
            check(parent.usableSpace >= requiredSpace) { "Not enough storage space for generated media" }
        }
        val partial = File(parent, "${target.name}.part")
        check(!target.exists() && !partial.exists()) { "Media target already exists" }

        try {
            var written = 0L
            input.use { source ->
                FileOutputStream(partial).use { output ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    while (true) {
                        val count = source.read(buffer)
                        if (count == -1) break
                        if (count == 0) continue
                        written += count
                        check(written <= maxBytes) { "Generated media exceeds the configured size limit" }
                        output.write(buffer, 0, count)
                        onProgress(written, expectedSizeBytes)
                    }
                    output.fd.sync()
                }
            }
            check(written > 0L) { "Generated media is empty" }
            check(expectedSizeBytes == null || written == expectedSizeBytes) {
                "Generated media download is incomplete"
            }
            check(partial.renameTo(target)) { "Unable to finalize generated media" }
            return written
        } catch (error: Throwable) {
            partial.delete()
            target.delete()
            throw error
        }
    }
}
