package me.rerere.rikkahub.ui.components.webview

import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

internal object WebViewContentCache {
    private const val DIRECTORY_NAME = "webview_content"
    private const val HASH_LENGTH = 64
    private val maxAgeMillis = TimeUnit.DAYS.toMillis(7)
    private val hexDigits = "0123456789abcdef".toCharArray()
    internal const val MAX_CONTENT_BYTES = 8 * 1024 * 1024
    private const val MAX_CACHE_BYTES = 48L * 1024 * 1024
    private const val MAX_CACHE_ENTRIES = 128

    @Synchronized
    fun store(cacheDir: File, content: String): String {
        val bytes = content.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_CONTENT_BYTES) { "Preview content is too large" }
        val id = content.sha256()
        val directory = File(cacheDir, DIRECTORY_NAME)
        check(directory.isDirectory || directory.mkdirs()) {
            "Unable to create WebView content cache directory"
        }

        val file = File(directory, id)
        if (!file.isFile || file.length() != bytes.size.toLong() || runCatching { file.readText() != content }.getOrDefault(true)) {
            val temporary = File.createTempFile("preview-", ".tmp", directory)
            try {
                temporary.outputStream().use { it.write(bytes); it.fd.sync() }
                try {
                    java.nio.file.Files.move(temporary.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
                } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                    java.nio.file.Files.move(temporary.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                }
            } finally { temporary.delete() }
        }
        file.setLastModified(System.currentTimeMillis())

        removeExpiredFiles(directory, id)
        return id
    }

    @Synchronized
    fun load(cacheDir: File, id: String): String? {
        if (!id.isSha256()) return null

        val file = File(File(cacheDir, DIRECTORY_NAME), id)
        if (!file.isFile || file.length() > MAX_CONTENT_BYTES) return null

        return runCatching {
            file.readText().takeIf { it.sha256() == id }?.also {
                file.setLastModified(System.currentTimeMillis())
            }
        }.getOrNull()
    }

    private fun removeExpiredFiles(directory: File, protectedId: String) {
        val expirationTime = System.currentTimeMillis() - maxAgeMillis
        directory.listFiles()?.forEach { file ->
            if (file.isFile && file.lastModified() < expirationTime) {
                file.delete()
            }
        }
        val files = directory.listFiles()?.filter { it.isFile && it.name.isSha256() }.orEmpty().sortedBy { it.lastModified() }
        var total = files.sumOf { it.length() }
        var count = files.size
        files.forEach { file ->
            if ((total > MAX_CACHE_BYTES || count > MAX_CACHE_ENTRIES) && file.name != protectedId) {
                val size = file.length()
                if (file.delete()) { total -= size; count-- }
            }
        }
    }

    private fun String.sha256(): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(toByteArray())
        return CharArray(bytes.size * 2).also { result ->
            bytes.forEachIndexed { index, byte ->
                val value = byte.toInt() and 0xff
                result[index * 2] = hexDigits[value ushr 4]
                result[index * 2 + 1] = hexDigits[value and 0x0f]
            }
        }.concatToString()
    }

    private fun String.isSha256(): Boolean =
        length == HASH_LENGTH && all { it in '0'..'9' || it in 'a'..'f' }
}
