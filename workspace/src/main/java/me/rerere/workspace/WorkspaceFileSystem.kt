package me.rerere.workspace

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.CopyOption
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import kotlin.io.path.name

class WorkspaceFileSystem(
    private val config: WorkspaceConfig = WorkspaceConfig(),
) {
    fun list(root: File, path: String = ""): List<WorkspaceFileEntry> =
        listPage(root, path).entries

    fun listPage(
        root: File,
        path: String = "",
        offset: Int = 0,
        limit: Int = config.maxListEntries,
    ): WorkspaceFilePage {
        require(offset >= 0) { "List offset cannot be negative" }
        require(limit in 1..config.maxListEntries) { "Invalid list page size: $limit" }
        val dir = resolvePath(root, path)
        require(dir.exists()) { "Path does not exist: $path" }
        require(dir.isDirectory) { "Path is not a directory: $path" }
        val children = dir.listFiles()
            .orEmpty()
            .filter { !it.name.startsWith(".l2s.") }
            .filter { !it.name.startsWith(INTERNAL_PREFIX) }
            .sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
        val entries = children
            .drop(offset)
            .take(limit)
            .map { it.toEntry(root) }
        return WorkspaceFilePage(
            entries = entries,
            nextOffset = (offset + entries.size).takeIf { it < children.size },
            totalEntries = children.size,
        )
    }

    fun readText(root: File, path: String, charset: Charset = StandardCharsets.UTF_8): String {
        val file = resolvePath(root, path)
        require(file.exists()) { "File does not exist: $path" }
        require(file.isFile) { "Path is not a file: $path" }
        require(file.length() <= config.maxReadBytes) {
            "File is too large to read: ${file.length()} bytes"
        }
        return file.readText(charset)
    }

    fun readTextSnapshot(
        root: File,
        path: String,
        charset: Charset = StandardCharsets.UTF_8,
    ): WorkspaceTextSnapshot {
        val file = resolvePath(root, path)
        require(file.exists()) { "File does not exist: $path" }
        require(file.isFile) { "Path is not a file: $path" }
        require(file.length() <= config.maxReadBytes) {
            "File is too large to read: ${file.length()} bytes"
        }
        val bytes = file.readBytes()
        return WorkspaceTextSnapshot(
            text = bytes.toString(charset),
            revision = file.revision(bytes),
        )
    }

    fun writeText(
        root: File,
        path: String,
        text: String,
        overwrite: Boolean = true,
        charset: Charset = StandardCharsets.UTF_8,
        expectedRevision: WorkspaceFileRevision? = null,
    ): WorkspaceFileEntry {
        val bytes = text.toByteArray(charset)
        require(bytes.size <= config.maxWriteBytes) {
            "Content is too large to write: ${bytes.size} bytes"
        }
        val file = resolvePath(root, path)
        require(!file.exists() || overwrite) { "File already exists: $path" }
        require(!file.exists() || file.isFile) { "Path is not a file: $path" }
        file.parentFile?.mkdirs()
        ensureCapacity(root, bytes.size.toLong() - file.length())
        writeAtomically(file, bytes, expectedRevision)
        return file.toEntry(root)
    }

    fun importBytes(root: File, path: String, inputStream: InputStream): WorkspaceFileEntry {
        val file = resolvePath(root, path)
        file.parentFile?.mkdirs()
        val target = if (!file.exists()) file else resolveConflict(file)
        val temp = temporarySibling(target)
        try {
            val copied = inputStream.use { input ->
                FileOutputStream(temp).use { output ->
                    val count = input.copyToLimited(output, config.maxImportBytes)
                    output.fd.sync()
                    count
                }
            }
            require(directoryStats(root).first <= config.maxFilesAreaBytes) {
                "Workspace file capacity exceeded (max ${config.maxFilesAreaBytes} bytes)"
            }
            moveIntoPlace(temp, target, overwrite = false)
        } finally {
            temp.delete()
        }
        return target.toEntry(root)
    }

    fun createDirectory(root: File, path: String): WorkspaceFileEntry {
        require(path.isNotBlank() && path != ".") { "Directory path is required" }
        val directory = resolvePath(root, path)
        require(!directory.exists()) { "Path already exists: $path" }
        val parent = directory.parentFile
        require(parent == null || parent.isDirectory || parent.mkdirs()) {
            "Parent path is not a directory: ${parent?.path}"
        }
        require(directory.mkdirs()) { "Could not create directory: $path" }
        return directory.toEntry(root)
    }

    private fun resolveConflict(file: File): File {
        val stem = file.nameWithoutExtension
        val ext = file.extension.let { if (it.isNotEmpty()) ".$it" else "" }
        var n = 1
        var candidate: File
        do { candidate = File(file.parentFile, "$stem ($n)$ext"); n++ } while (candidate.exists())
        return candidate
    }

    fun delete(root: File, path: String, recursive: Boolean = false): Boolean {
        require(path.isNotBlank() && path != ".") { "Refusing to delete workspace root" }
        val file = resolvePath(root, path)
        if (!file.exists()) return false
        return if (file.isDirectory) {
            require(recursive) { "Directory delete requires recursive = true" }
            file.deleteRecursively()
        } else {
            file.delete()
        }
    }

    fun moveToTrash(
        root: File,
        path: String,
        recursive: Boolean,
        trashRoot: File,
    ): Pair<String, Long>? {
        require(path.isNotBlank() && path != ".") { "Refusing to delete workspace root" }
        val file = resolvePath(root, path)
        if (!file.exists()) return null
        if (file.isDirectory) require(recursive) { "Directory delete requires recursive = true" }

        val deletedAt = System.currentTimeMillis()
        val token = "${deletedAt}-${UUID.randomUUID()}"
        val destination = File(File(trashRoot, token), "data")
        destination.parentFile?.mkdirs()
        try {
            moveIntoPlace(file, destination, overwrite = false)
        } catch (error: Throwable) {
            destination.parentFile?.deleteRecursively()
            throw error
        }
        return token to deletedAt
    }

    fun restoreFromTrash(
        root: File,
        path: String,
        trashRoot: File,
        token: String,
    ): WorkspaceFileEntry {
        require(token.matches(TRASH_TOKEN_REGEX)) { "Invalid trash token" }
        val sourceDir = File(trashRoot, token)
        val source = File(sourceDir, "data")
        require(source.exists()) { "Deleted file is no longer available" }
        val target = resolvePath(root, path)
        require(!target.exists()) { "Cannot restore because the original path is in use: $path" }
        target.parentFile?.mkdirs()
        moveIntoPlace(source, target, overwrite = false)
        sourceDir.deleteRecursively()
        return target.toEntry(root)
    }

    fun move(root: File, source: String, target: String, overwrite: Boolean = false): WorkspaceFileEntry {
        require(source.isNotBlank() && source != ".") { "Refusing to move workspace root" }
        val sourceFile = resolvePath(root, source)
        val targetFile = resolvePath(root, target)
        require(sourceFile.exists()) { "Source does not exist: $source" }
        require(!Files.isSymbolicLink(sourceFile.toPath())) { "Symbolic links are not supported" }
        if (targetFile.exists()) {
            require(overwrite) { "Target already exists: $target" }
        }
        require(!targetFile.canonicalPath.startsWith(sourceFile.canonicalPath + File.separator)) {
            "Cannot move a directory into itself"
        }
        targetFile.parentFile?.mkdirs()
        moveIntoPlace(sourceFile, targetFile, overwrite)
        return targetFile.toEntry(root)
    }

    fun copy(root: File, source: String, target: String, overwrite: Boolean = false): WorkspaceFileEntry {
        require(source.isNotBlank() && source != ".") { "Refusing to copy workspace root" }
        val sourceFile = resolvePath(root, source)
        val targetFile = resolvePath(root, target)
        require(sourceFile.exists()) { "Source does not exist: $source" }
        require(!Files.isSymbolicLink(sourceFile.toPath())) { "Symbolic links are not supported" }
        if (targetFile.exists()) require(overwrite) { "Target already exists: $target" }
        require(!targetFile.canonicalPath.startsWith(sourceFile.canonicalPath + File.separator)) {
            "Cannot copy a directory into itself"
        }
        val sourceStats = copyStats(sourceFile)
        require(sourceStats.bytes <= config.maxCopyBytes) {
            "Copy exceeds ${config.maxCopyBytes} byte limit"
        }
        // The temporary copy exists alongside both source and target until replacement succeeds.
        ensureCapacity(root, sourceStats.bytes)
        targetFile.parentFile?.mkdirs()
        val temporary = temporarySibling(targetFile, suffix = ".copy.tmp")
        try {
            val copied = CopyStats()
            copyRecursively(sourceFile, temporary, copied, depth = 0)
            require(copied == sourceStats) { "Source changed while it was being copied" }
            moveIntoPlace(temporary, targetFile, overwrite)
        } finally {
            if (temporary.exists()) temporary.deleteRecursively()
        }
        return targetFile.toEntry(root)
    }

    private data class CopyStats(var bytes: Long = 0L, var entries: Int = 0)

    private fun copyStats(source: File): CopyStats {
        val stats = CopyStats()
        collectCopyStats(source, stats, depth = 0)
        return stats
    }

    private fun collectCopyStats(source: File, stats: CopyStats, depth: Int) {
        require(depth <= config.maxCopyDepth) { "Copy exceeds maximum directory depth" }
        require(!Files.isSymbolicLink(source.toPath())) { "Symbolic links are not supported" }
        stats.entries++
        require(stats.entries <= config.maxCopyEntries) { "Copy contains too many entries" }
        if (source.isDirectory) {
            val children = source.listFiles()
                ?: error("Could not list source directory: ${source.path}")
            children.forEach { collectCopyStats(it, stats, depth + 1) }
        } else {
            require(source.isFile) { "Unsupported source path: ${source.path}" }
            stats.bytes = safeAdd(stats.bytes, source.length())
            require(stats.bytes <= config.maxCopyBytes) {
                "Copy exceeds ${config.maxCopyBytes} byte limit"
            }
        }
    }

    private fun copyRecursively(source: File, target: File, stats: CopyStats, depth: Int) {
        require(depth <= config.maxCopyDepth) { "Copy exceeds maximum directory depth" }
        require(!Files.isSymbolicLink(source.toPath())) { "Symbolic links are not supported" }
        stats.entries++
        require(stats.entries <= config.maxCopyEntries) { "Copy contains too many entries" }
        if (source.isDirectory) {
            require(target.mkdirs() || target.isDirectory) { "Could not create directory: ${target.path}" }
            val children = source.listFiles()
                ?: error("Could not list source directory: ${source.path}")
            children.forEach { child ->
                copyRecursively(child, File(target, child.name), stats, depth + 1)
            }
            return
        }
        require(source.isFile) { "Unsupported source path: ${source.path}" }
        target.parentFile?.mkdirs()
        source.inputStream().use { input ->
            target.outputStream().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    stats.bytes = safeAdd(stats.bytes, count.toLong())
                    require(stats.bytes <= config.maxCopyBytes) {
                        "Copy exceeds ${config.maxCopyBytes} byte limit"
                    }
                    output.write(buffer, 0, count)
                }
                output.flush()
            }
        }
    }

    fun glob(root: File, pattern: String, path: String = ""): List<WorkspaceFileEntry> {
        require(pattern.isNotBlank()) { "Glob pattern is required" }
        val start = resolvePath(root, path)
        require(start.exists()) { "Path does not exist: $path" }
        val matcher = FileSystems.getDefault().getPathMatcher("glob:$pattern")
        return walk(start) { paths ->
            val matches = paths
                .filter { Files.isRegularFile(it) || Files.isDirectory(it) }
                .filter { !it.toFile().name.startsWith(".l2s.") }
                .filter { !it.toFile().name.startsWith(INTERNAL_PREFIX) }
                .filter { matcher.matches(root.toPath().relativize(it).normalizeForMatch()) }
                .take(config.maxListEntries + 1)
                .map { it.toFile().toEntry(root) }
                .toList()
            require(matches.size <= config.maxListEntries) {
                "Glob has more than ${config.maxListEntries} matches; narrow the pattern"
            }
            matches
        }
    }

    fun grep(
        root: File,
        query: String,
        path: String = "",
        regex: Boolean = false,
        ignoreCase: Boolean = true,
        includeGlob: String? = null,
    ): List<WorkspaceSearchMatch> {
        require(query.isNotBlank()) { "Search query is required" }
        val start = resolvePath(root, path)
        require(start.exists()) { "Path does not exist: $path" }
        val options = if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
        val matcher = if (regex) Regex(query, options) else Regex(Regex.escape(query), options)
        val includeMatcher = includeGlob
            ?.takeIf { it.isNotBlank() }
            ?.let { FileSystems.getDefault().getPathMatcher("glob:$it") }

        val results = mutableListOf<WorkspaceSearchMatch>()
        walk(start) { paths ->
            paths
                .filter { Files.isRegularFile(it) }
                .filter { !it.toFile().name.startsWith(".l2s.") }
                .filter { !it.toFile().name.startsWith(INTERNAL_PREFIX) }
                .forEach { path ->
                    if (results.size >= config.maxSearchResults) return@forEach
                    if (includeMatcher != null &&
                        !includeMatcher.matches(root.toPath().relativize(path).normalizeForMatch())
                    ) {
                        return@forEach
                    }
                    val file = path.toFile()
                    if (file.length() > config.maxReadBytes) return@forEach
                    file.useLines(StandardCharsets.UTF_8) { lines ->
                        lines.forEachIndexed { index, line ->
                            if (results.size >= config.maxSearchResults) return@useLines
                            if (matcher.containsMatchIn(line)) {
                                results += WorkspaceSearchMatch(
                                    path = file.relativePath(root),
                                    line = index + 1,
                                    text = line,
                                )
                            }
                        }
                    }
                }
        }
        return results
    }

    private fun <T> walk(start: File, block: (Sequence<Path>) -> T): T =
        Files.walk(start.toPath(), config.maxWalkDepth).use { stream ->
            var visited = 0
            val paths = stream.iterator().asSequence().onEach {
                visited++
                require(visited <= config.maxWalkEntries) {
                    "Workspace traversal exceeded ${config.maxWalkEntries} entries"
                }
            }
            block(paths)
        }

    fun directoryStats(root: File): Pair<Long, Int> {
        if (!root.exists()) return 0L to 0
        var bytes = 0L
        var files = 0
        var visited = 0
        Files.walk(root.toPath(), config.maxStatsDepth).use { stream ->
            stream.forEach {
                visited++
                require(visited <= config.maxStatsEntries) {
                    "Workspace statistics exceeded ${config.maxStatsEntries} entries"
                }
                if (Files.isRegularFile(it)) {
                    bytes = safeAdd(bytes, Files.size(it))
                    files++
                }
            }
        }
        return bytes to files
    }

    fun cleanupTrash(trashRoot: File, now: Long = System.currentTimeMillis()) {
        trashRoot.listFiles().orEmpty().forEach { entry ->
            if (now - entry.lastModified() >= config.trashRetentionMillis) {
                entry.deleteRecursively()
            }
        }
    }

    private fun resolvePath(root: File, path: String): File {
        root.mkdirs()
        val normalized = path
            .replace('\\', '/')
            .trim()
            .trimStart('/')
            .ifBlank { "." }
        require(!normalized.contains('\u0000')) { "Path contains invalid character" }

        val rootFile = root.canonicalFile
        val target = if (normalized == ".") rootFile else File(rootFile, normalized).canonicalFile
        val rootPath = rootFile.path
        val targetPath = target.path
        require(targetPath == rootPath || targetPath.startsWith(rootPath + File.separator)) {
            "Path escapes workspace root: $path"
        }
        return target
    }

    fun resolve(root: File, path: String): File = resolvePath(root, path)

    private fun writeAtomically(
        target: File,
        bytes: ByteArray,
        expectedRevision: WorkspaceFileRevision?,
    ) {
        val temp = temporarySibling(target)
        try {
            FileOutputStream(temp).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            if (expectedRevision != null) {
                require(target.exists() && target.isFile && target.revision() == expectedRevision) {
                    "File changed since it was opened; reload it before saving"
                }
            }
            moveIntoPlace(temp, target, overwrite = true)
        } finally {
            temp.delete()
        }
    }

    private fun moveIntoPlace(source: File, target: File, overwrite: Boolean) {
        require(source.exists()) { "Source does not exist: ${source.path}" }
        require(overwrite || !target.exists()) { "Target already exists: ${target.path}" }
        val options = if (overwrite) {
            arrayOf<CopyOption>(StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } else {
            arrayOf<CopyOption>(StandardCopyOption.ATOMIC_MOVE)
        }
        if (!(overwrite && target.exists() && (source.isDirectory || target.isDirectory))) {
            try {
                Files.move(source.toPath(), target.toPath(), *options)
                return
            } catch (_: IOException) {
                // Fall through to a rollback-capable replacement.
            }
        }

        val backup = if (overwrite && target.exists()) temporarySibling(target, suffix = ".bak") else null
        try {
            if (backup != null) Files.move(target.toPath(), backup.toPath())
            Files.move(source.toPath(), target.toPath())
            backup?.deleteRecursively()
        } catch (error: Throwable) {
            if (!target.exists() && backup?.exists() == true) {
                runCatching { Files.move(backup.toPath(), target.toPath()) }
            }
            throw error
        } finally {
            backup?.let { if (it.exists() && target.exists()) it.deleteRecursively() }
        }
    }

    private fun ensureCapacity(root: File, deltaBytes: Long) {
        if (deltaBytes <= 0) return
        val current = directoryStats(root).first
        require(current <= config.maxFilesAreaBytes - deltaBytes) {
            "Workspace file capacity exceeded (max ${config.maxFilesAreaBytes} bytes)"
        }
    }

    private fun InputStream.copyToLimited(output: FileOutputStream, maxBytes: Long): Long {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var copied = 0L
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            copied = safeAdd(copied, read.toLong())
            require(copied <= maxBytes) { "Imported file is too large (max $maxBytes bytes)" }
            output.write(buffer, 0, read)
        }
        return copied
    }

    private fun File.revision(bytes: ByteArray? = null): WorkspaceFileRevision = WorkspaceFileRevision(
        sizeBytes = length(),
        updatedAt = lastModified(),
        sha256 = sha256(bytes ?: readBytes()),
    )

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun temporarySibling(target: File, suffix: String = ".tmp"): File =
        File(target.parentFile, "$INTERNAL_PREFIX${target.name}-${UUID.randomUUID()}$suffix")

    private fun safeAdd(left: Long, right: Long): Long {
        require(right >= 0 && left <= Long.MAX_VALUE - right) { "File size overflow" }
        return left + right
    }

    private fun File.toEntry(root: File): WorkspaceFileEntry = WorkspaceFileEntry(
        path = relativePath(root),
        name = name,
        isDirectory = isDirectory,
        sizeBytes = if (isFile) length() else 0L,
        updatedAt = lastModified(),
    )

    private fun File.relativePath(root: File): String {
        val rootCanonical = root.canonicalFile
        val parentCanonical = (parentFile ?: rootCanonical).canonicalFile
        return File(parentCanonical, name).relativeTo(rootCanonical).path.replace(File.separatorChar, '/')
    }

    private fun Path.normalizeForMatch(): Path =
        FileSystems.getDefault().getPath(relativeToString())

    private fun Path.relativeToString(): String =
        joinToString("/") { it.name }

    private companion object {
        const val INTERNAL_PREFIX = ".rikkahub-"
        val TRASH_TOKEN_REGEX = Regex("[0-9]+-[0-9a-fA-F-]+")
    }
}
