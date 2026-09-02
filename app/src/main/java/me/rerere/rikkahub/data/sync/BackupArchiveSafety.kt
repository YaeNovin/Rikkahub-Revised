package me.rerere.rikkahub.data.sync

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.zip.ZipEntry

internal class BackupRestoreBudget(
    private val maxEntries: Int = 100_000,
    private val maxEntryBytes: Long = 2L * 1024 * 1024 * 1024,
    private val maxTotalBytes: Long = 8L * 1024 * 1024 * 1024,
) {
    private var entryCount = 0
    private var currentEntryBytes = 0L
    private var totalBytes = 0L
    private val replacements = mutableListOf<Replacement>()
    private val restoredTargets = hashSetOf<String>()

    fun beginEntry(entry: ZipEntry) {
        entryCount++
        require(entryCount <= maxEntries) { "Backup contains too many entries" }
        require(entry.name.length <= MAX_ENTRY_NAME_LENGTH) { "Backup entry name is too long" }
        require(entry.size < 0 || entry.size <= maxEntryBytes) {
            "Backup entry is too large: ${entry.name}"
        }
        currentEntryBytes = 0L
    }

    fun copyCurrentEntry(input: InputStream, output: OutputStream): Long =
        copyCurrentEntry(input, output, maxEntryBytes)

    private fun copyCurrentEntry(input: InputStream, output: OutputStream, readLimit: Long): Long {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            currentEntryBytes = checkedAdd(currentEntryBytes, read.toLong())
            totalBytes = checkedAdd(totalBytes, read.toLong())
            require(currentEntryBytes <= maxEntryBytes) { "Backup entry exceeds the size limit" }
            require(currentEntryBytes <= readLimit) { "Backup metadata entry exceeds the memory limit" }
            require(totalBytes <= maxTotalBytes) { "Backup extracted data exceeds the size limit" }
            output.write(buffer, 0, read)
        }
        return currentEntryBytes
    }

    fun readCurrentEntry(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        copyCurrentEntry(input, output, MAX_IN_MEMORY_ENTRY_BYTES)
        return output.toByteArray()
    }

    fun restoreCurrentEntry(input: InputStream, target: File) {
        target.parentFile?.mkdirs()
        require(!target.exists() || target.isFile) { "Backup target is not a file: ${target.path}" }
        val targetKey = target.canonicalPath
        require(restoredTargets.add(targetKey)) { "Backup contains duplicate target: ${target.path}" }
        val temp = File(target.parentFile, ".rikkahub-${target.name}-${UUID.randomUUID()}.tmp")
        val backup = if (target.exists()) {
            File(target.parentFile, ".rikkahub-${target.name}-${UUID.randomUUID()}.restore-bak")
        } else null
        try {
            FileOutputStream(temp).use { output ->
                copyCurrentEntry(input, output)
                output.fd.sync()
            }
            if (backup != null) moveFile(target, backup, replace = false)
            try {
                moveFile(temp, target, replace = false)
                replacements += Replacement(target, backup)
            } catch (error: Throwable) {
                if (!target.exists() && backup?.exists() == true) {
                    runCatching { moveFile(backup, target, replace = false) }
                }
                restoredTargets.remove(targetKey)
                throw error
            }
        } finally {
            temp.delete()
        }
    }

    fun commit() {
        replacements.forEach { it.backup?.delete() }
        replacements.clear()
        restoredTargets.clear()
    }

    fun rollback() {
        var rollbackFailure: Throwable? = null
        replacements.asReversed().forEach { replacement ->
            runCatching {
                if (replacement.target.exists()) {
                    require(replacement.target.deleteRecursively()) {
                        "Failed to remove partially restored file: ${replacement.target.path}"
                    }
                }
                replacement.backup?.takeIf { it.exists() }?.let { backup ->
                    moveFile(backup, replacement.target, replace = false)
                }
            }.onFailure { failure ->
                val previousFailure = rollbackFailure
                if (previousFailure == null) rollbackFailure = failure else previousFailure.addSuppressed(failure)
            }
        }
        replacements.clear()
        restoredTargets.clear()
        rollbackFailure?.let { throw IllegalStateException("Backup rollback was incomplete", it) }
    }

    private fun checkedAdd(left: Long, right: Long): Long {
        require(right >= 0 && left <= Long.MAX_VALUE - right) { "Backup size overflow" }
        return left + right
    }

    private fun moveFile(source: File, target: File, replace: Boolean) {
        val options = if (replace) {
            arrayOf(StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } else {
            arrayOf(StandardCopyOption.ATOMIC_MOVE)
        }
        try {
            Files.move(source.toPath(), target.toPath(), *options)
            return
        } catch (_: AtomicMoveNotSupportedException) {
            val fallback = if (replace) {
                arrayOf(StandardCopyOption.REPLACE_EXISTING)
            } else {
                emptyArray()
            }
            Files.move(source.toPath(), target.toPath(), *fallback)
        }
    }

    private data class Replacement(val target: File, val backup: File?)

    private companion object {
        const val MAX_ENTRY_NAME_LENGTH = 4_096
        const val MAX_IN_MEMORY_ENTRY_BYTES = 16L * 1024 * 1024
    }
}

internal fun File.isSafeBackupChild(root: File): Boolean {
    if (Files.isSymbolicLink(toPath())) return false
    val rootPath = root.canonicalFile.path
    val targetPath = canonicalFile.path
    return targetPath == rootPath || targetPath.startsWith(rootPath + File.separator)
}
