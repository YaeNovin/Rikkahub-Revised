package me.rerere.rikkahub.data.files

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import me.rerere.workspace.WorkspaceFileRevision
import me.rerere.workspace.WorkspaceLocalDirectoryGrant
import me.rerere.workspace.WorkspaceCommandResult
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.concurrent.TimeUnit

data class WorkspaceLocalFileEntry(
    val grantId: String,
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val updatedAt: Long,
    val mimeType: String,
    val uri: String,
    val canWrite: Boolean,
)

data class WorkspaceLocalFilePage(
    val entries: List<WorkspaceLocalFileEntry>,
    val nextOffset: Int?,
    val totalEntries: Int,
)

data class WorkspaceLocalTextSnapshot(
    val text: String,
    val revision: WorkspaceFileRevision,
)

data class WorkspacePersistedTreePermission(
    val displayName: String,
    val canRead: Boolean,
    val canWrite: Boolean,
)

class WorkspaceSafFileSystem(context: Context) {
    private val appContext = context.applicationContext
    private val resolver = context.applicationContext.contentResolver

    fun persistTreePermission(treeUri: Uri, resultFlags: Int): WorkspacePersistedTreePermission {
        require(treeUri.scheme == ContentResolver.SCHEME_CONTENT) { "Only content URI directories are supported" }
        require(DocumentsContract.isTreeUri(treeUri)) { "The selected URI is not a document tree" }
        val requested = resultFlags and PERSISTABLE_PERMISSION_FLAGS
        require(requested and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0) {
            "The selected directory did not grant read access"
        }

        runCatching { resolver.takePersistableUriPermission(treeUri, requested) }
            .recoverCatching {
                resolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

        val persisted = resolver.persistedUriPermissions.firstOrNull {
            equivalentTreeUri(it.uri, treeUri)
        }
            ?: error("The directory permission could not be retained")
        require(persisted.isReadPermission) { "The directory does not have retained read access" }
        val root = queryDocument(rootDocumentUri(treeUri))
        return WorkspacePersistedTreePermission(
            displayName = root.name.ifBlank { treeUri.lastPathSegment ?: "Local directory" },
            canRead = persisted.isReadPermission,
            canWrite = persisted.isWritePermission,
        )
    }

    fun releaseTreePermission(treeUri: Uri) {
        val permission = resolver.persistedUriPermissions.firstOrNull {
            equivalentTreeUri(it.uri, treeUri)
        } ?: return
        var flags = 0
        if (permission.isReadPermission) flags = flags or Intent.FLAG_GRANT_READ_URI_PERMISSION
        if (permission.isWritePermission) flags = flags or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        if (flags != 0) resolver.releasePersistableUriPermission(permission.uri, flags)
    }

    fun hasPersistedPermission(grant: WorkspaceLocalDirectoryGrant, write: Boolean = false): Boolean {
        val uri = Uri.parse(grant.treeUri)
        return resolver.persistedUriPermissions.any { permission ->
            equivalentTreeUri(permission.uri, uri) &&
                permission.isReadPermission && (!write || permission.isWritePermission)
        }
    }

    private fun equivalentTreeUri(left: Uri, right: Uri): Boolean {
        if (left == right) return true
        // A few Android document providers add/remove a trailing slash when
        // persisting a tree URI. Keep the comparison semantic without changing
        // the URI used for DocumentsContract operations.
        fun normalized(uri: Uri): String = uri.normalizeScheme().toString().trimEnd('/')
        return normalized(left) == normalized(right)
    }

    fun list(
        grant: WorkspaceLocalDirectoryGrant,
        path: String,
        offset: Int = 0,
        limit: Int = DEFAULT_PAGE_SIZE,
    ): WorkspaceLocalFilePage {
        require(offset >= 0) { "List offset cannot be negative" }
        require(limit in 1..MAX_PAGE_SIZE) { "Invalid local directory page size" }
        requireReadAccess(grant)
        val normalizedPath = normalizeSafRelativePath(path)
        val directory = resolve(grant, normalizedPath)
        require(directory.isDirectory) { "Path is not a directory: $path" }
        val treeUri = Uri.parse(grant.treeUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, directory.documentId)
        val entries = mutableListOf<DocumentInfo>()
        resolver.query(childrenUri, DOCUMENT_PROJECTION, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                require(entries.size < MAX_DIRECTORY_ENTRIES) {
                    "Local directory contains more than $MAX_DIRECTORY_ENTRIES entries"
                }
                entries += cursor.toDocumentInfo(treeUri)
            }
        } ?: error("The document provider did not return a directory listing")

        val sorted = entries.sortedWith(compareBy<DocumentInfo> { !it.isDirectory }.thenBy { it.name.lowercase() })
        val page = sorted.drop(offset).take(limit).map { info ->
            info.toEntry(
                grant = grant,
                path = joinSafPath(normalizedPath, info.name),
            )
        }
        return WorkspaceLocalFilePage(
            entries = page,
            nextOffset = (offset + page.size).takeIf { it < sorted.size },
            totalEntries = sorted.size,
        )
    }

    fun readTextSnapshot(
        grant: WorkspaceLocalDirectoryGrant,
        path: String,
    ): WorkspaceLocalTextSnapshot {
        val bytes = readBytes(grant, path, MAX_TEXT_READ_BYTES)
        val info = resolve(grant, path)
        return WorkspaceLocalTextSnapshot(
            text = bytes.toString(Charsets.UTF_8),
            revision = info.revision(bytes),
        )
    }

    fun readBytes(
        grant: WorkspaceLocalDirectoryGrant,
        path: String,
        maxBytes: Long = MAX_BINARY_READ_BYTES,
    ): ByteArray {
        requireReadAccess(grant)
        require(maxBytes in 1..MAX_BINARY_READ_BYTES) { "Invalid local file read limit" }
        val document = resolve(grant, path)
        require(!document.isDirectory) { "Path is not a file: $path" }
        require(document.size <= maxBytes || document.size < 0) {
            "Local file is too large to read: ${document.size} bytes"
        }
        return readDocumentBytes(document.uri, maxBytes)
    }

    private fun readDocumentBytes(uri: Uri, maxBytes: Long): ByteArray {
        return resolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var copied = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                copied = safeAdd(copied, count.toLong())
                require(copied <= maxBytes) { "Local file is too large to read (max $maxBytes bytes)" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } ?: error("The document provider could not open the file")
    }

    fun writeText(
        grant: WorkspaceLocalDirectoryGrant,
        path: String,
        text: String,
        overwrite: Boolean = true,
        expectedRevision: WorkspaceFileRevision? = null,
    ): WorkspaceLocalFileEntry {
        val bytes = text.toByteArray(Charsets.UTF_8)
        return writeBytesInternal(
            grant = grant,
            path = path,
            bytes = bytes,
            overwrite = overwrite,
            expectedRevision = expectedRevision,
            maxBytes = MAX_TEXT_WRITE_BYTES,
        )
    }

    /**
     * Writes arbitrary bytes through the authorized document provider.
     * Command-mode synchronization uses this instead of decoding files as UTF-8.
     */
    fun writeBytes(
        grant: WorkspaceLocalDirectoryGrant,
        path: String,
        bytes: ByteArray,
        overwrite: Boolean = true,
    ): WorkspaceLocalFileEntry = writeBytesInternal(
        grant = grant,
        path = path,
        bytes = bytes,
        overwrite = overwrite,
        expectedRevision = null,
        maxBytes = MAX_BINARY_WRITE_BYTES,
    )

    private fun writeBytesInternal(
        grant: WorkspaceLocalDirectoryGrant,
        path: String,
        bytes: ByteArray,
        overwrite: Boolean,
        expectedRevision: WorkspaceFileRevision?,
        maxBytes: Long,
    ): WorkspaceLocalFileEntry {
        requireWriteAccess(grant)
        val normalizedPath = normalizeSafRelativePath(path)
        require(normalizedPath.isNotBlank()) { "A local file path is required" }
        require(bytes.size.toLong() <= maxBytes) {
            "Content is too large to write: ${bytes.size} bytes"
        }

        val existing = resolveOrNull(grant, normalizedPath)
        require(existing == null || !existing.isDirectory) { "Path is not a file: $path" }
        require(existing == null || overwrite) { "File already exists: $path" }

        val previousBytes = existing?.let { document ->
            require(document.size <= maxBytes || document.size < 0) {
                "Existing local file is too large to replace safely: ${document.size} bytes"
            }
            requireDocumentWriteSupport(document, path)
            readBytes(grant, normalizedPath, maxBytes)
        }
        if (expectedRevision != null) {
            require(existing != null && previousBytes != null && existing.revision(previousBytes) == expectedRevision) {
                "File changed since it was opened; reload it before saving"
            }
        }

        val target = existing ?: createTextDocument(grant, normalizedPath)
        try {
            writeBytes(target.uri, bytes)
            val persisted = readDocumentBytes(target.uri, maxBytes)
            require(persisted.contentEquals(bytes)) {
                "The document provider did not persist the complete file content"
            }
        } catch (writeError: Throwable) {
            if (existing == null) {
                deleteDocumentBestEffort(target.uri)
            } else if (previousBytes != null) {
                runCatching { writeBytes(target.uri, previousBytes) }
                    .exceptionOrNull()?.let(writeError::addSuppressed)
            }
            throw writeError
        }
        return queryDocument(target.uri).toEntry(grant, normalizedPath)
    }

    fun createDirectory(
        grant: WorkspaceLocalDirectoryGrant,
        path: String,
    ): WorkspaceLocalFileEntry {
        requireWriteAccess(grant)
        val normalizedPath = normalizeSafRelativePath(path)
        require(normalizedPath.isNotBlank()) { "A local directory path is required" }
        require(resolveOrNull(grant, normalizedPath) == null) { "Path already exists: $path" }
        val parentPath = normalizedPath.substringBeforeLast('/', missingDelimiterValue = "")
        val name = normalizedPath.substringAfterLast('/')
        val parent = resolveOrCreateDirectory(grant, parentPath)
        require(parent.isDirectory) { "Parent path is not a directory: $parentPath" }
        requireDirectoryCreateSupport(parent, parentPath)
        val uri = DocumentsContract.createDocument(
            resolver,
            parent.uri,
            DocumentsContract.Document.MIME_TYPE_DIR,
            name,
        ) ?: error("The document provider could not create directory $name")
        return queryDocument(uri).toEntry(grant, normalizedPath)
    }

    private fun resolveOrCreateDirectory(
        grant: WorkspaceLocalDirectoryGrant,
        path: String,
    ): DocumentInfo {
        val normalized = normalizeSafRelativePath(path)
        val treeUri = Uri.parse(grant.treeUri)
        var current = queryDocument(rootDocumentUri(treeUri))
        if (normalized.isBlank()) return current
        normalized.split('/').forEach { segment ->
            require(current.isDirectory) { "Parent path is not a directory: $path" }
            current = findChild(treeUri, current.documentId, segment) ?: run {
                requireDirectoryCreateSupport(current, path)
                val uri = DocumentsContract.createDocument(
                    resolver,
                    current.uri,
                    DocumentsContract.Document.MIME_TYPE_DIR,
                    segment,
                ) ?: error("The document provider could not create directory $segment")
                queryDocument(uri)
            }
        }
        return current
    }

    fun delete(
        grant: WorkspaceLocalDirectoryGrant,
        path: String,
        recursive: Boolean = false,
    ): Boolean {
        requireWriteAccess(grant)
        val normalizedPath = normalizeSafRelativePath(path)
        require(normalizedPath.isNotBlank()) { "Refusing to delete the authorized directory root" }
        val document = resolve(grant, normalizedPath)
        if (document.isDirectory) require(recursive) { "Directory delete requires recursive = true" }
        return DocumentsContract.deleteDocument(resolver, document.uri)
    }

    fun move(
        grant: WorkspaceLocalDirectoryGrant,
        sourcePath: String,
        targetPath: String,
        overwrite: Boolean = false,
    ): WorkspaceLocalFileEntry {
        requireWriteAccess(grant)
        val sourceName = normalizeSafRelativePath(sourcePath)
        val targetName = normalizeSafRelativePath(targetPath)
        require(sourceName.isNotBlank()) { "Source path is required" }
        require(targetName.isNotBlank()) { "Target path is required" }
        require(sourceName != targetName) { "Source and target are the same path" }
        require(!targetName.startsWith("$sourceName/")) { "Cannot move a directory into itself" }
        val source = resolve(grant, sourceName)
        val sourceParentPath = sourceName.substringBeforeLast('/', missingDelimiterValue = "")
        val targetParentPath = targetName.substringBeforeLast('/', missingDelimiterValue = "")
        val sourceLeaf = sourceName.substringAfterLast('/')
        val targetLeaf = targetName.substringAfterLast('/')
        val sourceParent = resolve(grant, sourceParentPath)
        val targetParent = resolve(grant, targetParentPath)
        require(targetParent.isDirectory) { "Target parent is not a directory: $targetParentPath" }
        if (sourceParentPath != targetParentPath) {
            requireDirectoryCreateSupport(targetParent, targetParentPath)
        }
        val existing = resolveOrNull(grant, targetName)
        if (existing != null) {
            require(overwrite) { "Target already exists: $targetPath" }
            require(delete(grant, targetName, recursive = existing.isDirectory)) {
                "The document provider could not replace $targetPath"
            }
        }
        val moved = runCatching<Uri?> {
            when {
                sourceParentPath == targetParentPath ->
                    DocumentsContract.renameDocument(resolver, source.uri, targetLeaf)
                sourceLeaf == targetLeaf ->
                    DocumentsContract.moveDocument(resolver, source.uri, sourceParent.uri, targetParent.uri)
                else -> null
            }
        }.getOrElse { error ->
            if (error !is UnsupportedOperationException &&
                error !is IllegalArgumentException &&
                error !is FileNotFoundException
            ) throw error
            null
        }
        if (moved == null) {
            // A few providers do not implement moveDocument; copy then delete is the compatible fallback.
            copy(grant, sourceName, targetName, overwrite = false)
            require(delete(grant, sourceName, recursive = source.isDirectory)) {
                // Avoid reporting success when the provider copied but refused to remove the original.
                "The document provider copied the item but could not remove the source"
            }
        }
        val result = moved?.let(::queryDocument) ?: resolve(grant, targetName)
        require(result.name == targetLeaf) { "The document provider did not preserve the target name" }
        return result.toEntry(grant, targetName)
    }

    fun copy(
        grant: WorkspaceLocalDirectoryGrant,
        sourcePath: String,
        targetPath: String,
        overwrite: Boolean = false,
    ): WorkspaceLocalFileEntry {
        requireReadAccess(grant)
        requireWriteAccess(grant)
        val sourceName = normalizeSafRelativePath(sourcePath)
        val targetName = normalizeSafRelativePath(targetPath)
        require(sourceName.isNotBlank()) { "Source path is required" }
        require(targetName.isNotBlank()) { "Target path is required" }
        require(sourceName != targetName) { "Source and target are the same path" }
        require(!targetName.startsWith("$sourceName/")) { "Cannot copy a directory into itself" }
        val source = resolve(grant, sourceName)
        val targetParentPath = targetName.substringBeforeLast('/', missingDelimiterValue = "")
        val targetLeaf = targetName.substringAfterLast('/')
        val targetParent = resolve(grant, targetParentPath)
        require(targetParent.isDirectory) { "Target parent is not a directory: $targetParentPath" }
        requireDirectoryCreateSupport(targetParent, targetParentPath)
        val existing = resolveOrNull(grant, targetName)
        if (existing != null) {
            require(overwrite) { "Target already exists: $targetPath" }
            require(delete(grant, targetName, recursive = existing.isDirectory)) {
                "The document provider could not replace $targetPath"
            }
        }
        val stats = CopyStats()
        try {
            copyDocumentTree(grant, source, targetParent, targetLeaf, stats, depth = 0)
        } catch (error: Throwable) {
            runCatching { delete(grant, targetName, recursive = true) }
            throw error
        }
        return resolve(grant, targetName).toEntry(grant, targetName)
    }

    /**
     * Runs an Android host shell command against a temporary mirror of the
     * authorized SAF root and synchronizes the resulting tree back to SAF.
     *
     * SAF URIs are not filesystem paths and must never be passed to a shell.
     * Mirroring also gives us a bounded, reviewable change set: only files
     * below the selected grant can be written back after the command exits.
     */
    fun executeCommand(
        grant: WorkspaceLocalDirectoryGrant,
        command: String,
        cwd: String = "",
        timeoutMillis: Long = DEFAULT_COMMAND_TIMEOUT_MS,
    ): WorkspaceCommandResult {
        require(command.isNotBlank()) { "Command is required" }
        require(!command.contains('\u0000')) { "Command contains an invalid character" }
        require(command.length <= MAX_COMMAND_LENGTH) {
            "Command is too long (max $MAX_COMMAND_LENGTH characters)"
        }
        require(timeoutMillis in 1L..MAX_COMMAND_TIMEOUT_MS) {
            "Command timeout must be between 1 and ${MAX_COMMAND_TIMEOUT_MS / 1_000} seconds"
        }
        requireWriteAccess(grant)
        val normalizedCwd = normalizeSafRelativePath(cwd)
        val tempRoot = File(
            appContext.cacheDir,
            "saf-command-${grant.id}-${System.nanoTime().toString(16)}",
        )
        require(tempRoot.mkdirs()) { "Could not create a temporary command directory" }
        try {
            val before = mirrorSafTree(grant, tempRoot)
            val workingDirectory = if (normalizedCwd.isBlank()) {
                tempRoot
            } else {
                File(tempRoot, normalizedCwd)
            }
            require(workingDirectory.isDirectory) { "Working directory does not exist: $cwd" }
            val result = runHostShell(command, workingDirectory, timeoutMillis)
            // A timed-out command may have only partially written files. Drop
            // the mirror instead of committing an indeterminate partial tree.
            if (!result.timedOut) {
                val after = snapshotLocalTree(tempRoot)
                syncSafTree(grant, before, after)
            }
            return result
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    private fun mirrorSafTree(
        grant: WorkspaceLocalDirectoryGrant,
        destination: File,
    ): Map<String, LocalMirrorEntry> {
        val result = linkedMapOf<String, LocalMirrorEntry>(
            "" to LocalMirrorEntry(directory = true, digest = "", bytes = null),
        )
        var totalBytes = 0L
        val pending = ArrayDeque<Pair<String, File>>()
        pending.addLast("" to destination)
        while (pending.isNotEmpty()) {
            val (path, targetDirectory) = pending.removeFirst()
            var offset = 0
            while (true) {
                val page = list(grant, path, offset, MAX_COMMAND_PAGE_SIZE)
                page.entries.forEach { entry ->
                    val relative = normalizeSafRelativePath(entry.path)
                    require(result.size < MAX_COMMAND_TREE_ENTRIES) {
                        "Authorized directory contains too many entries"
                    }
                    val target = File(targetDirectory, entry.name)
                    if (entry.isDirectory) {
                        require(target.mkdirs() || target.isDirectory) {
                            "Could not create temporary directory: $relative"
                        }
                        result[relative] = LocalMirrorEntry(directory = true, digest = "", bytes = null)
                        pending.addLast(relative to target)
                    } else {
                        val bytes = readBytes(grant, relative, MAX_COMMAND_FILE_BYTES)
                        totalBytes += bytes.size.toLong()
                        require(totalBytes <= MAX_COMMAND_TREE_BYTES) {
                            "Authorized directory is too large to run a command (max ${MAX_COMMAND_TREE_BYTES / 1024 / 1024} MB)"
                        }
                        target.parentFile?.mkdirs()
                        target.outputStream().use { it.write(bytes) }
                        result[relative] = LocalMirrorEntry(
                            directory = false,
                            digest = sha256(bytes),
                            bytes = null,
                        )
                    }
                }
                val next = page.nextOffset ?: break
                require(next > offset) { "The document provider returned an invalid page offset" }
                offset = next
            }
        }
        return result
    }

    private fun snapshotLocalTree(root: File): Map<String, LocalMirrorEntry> {
        val result = linkedMapOf<String, LocalMirrorEntry>(
            "" to LocalMirrorEntry(directory = true, digest = "", bytes = null),
        )
        var totalBytes = 0L
        root.walkTopDown()
            // Do not descend through a symlink before the entry can be rejected.
            .onEnter { directory -> !Files.isSymbolicLink(directory.toPath()) }
            .forEach { file ->
            if (file == root) return@forEach
            val path = file.toPath()
            require(!Files.isSymbolicLink(path)) {
                "Symbolic links are not supported in SAF command mode: ${file.name}"
            }
            val relative = root.toPath()
                .relativize(path)
                .toString()
                .replace(File.separatorChar, '/')
            val normalized = normalizeSafRelativePath(relative)
            require(result.size < MAX_COMMAND_TREE_ENTRIES) {
                "Command created too many files"
            }
            if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                result[normalized] = LocalMirrorEntry(directory = true, digest = "", bytes = null)
            } else {
                require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    "Unsupported filesystem entry in command result: $normalized"
                }
                val bytes = readLocalBytes(file, MAX_COMMAND_FILE_BYTES)
                totalBytes += bytes.size.toLong()
                require(totalBytes <= MAX_COMMAND_TREE_BYTES) {
                    "Command result is too large to synchronize (max ${MAX_COMMAND_TREE_BYTES / 1024 / 1024} MB)"
                }
                result[normalized] = LocalMirrorEntry(
                    directory = false,
                    digest = sha256(bytes),
                    bytes = bytes,
                )
            }
        }
        return result
    }

    private fun syncSafTree(
        grant: WorkspaceLocalDirectoryGrant,
        before: Map<String, LocalMirrorEntry>,
        after: Map<String, LocalMirrorEntry>,
    ) {
        // Remove deepest paths first so a directory can be deleted safely.
        (before.keys - after.keys)
            .filter { it.isNotBlank() }
            .sortedByDescending { it.count { char -> char == '/' } }
            .forEach { path ->
                val entry = before[path] ?: return@forEach
                require(delete(grant, path, recursive = entry.directory)) {
                    "The document provider could not delete $path"
                }
            }

        after.entries
            .filter { it.key.isNotBlank() && it.value.directory }
            .sortedBy { it.key.count { char -> char == '/' } }
            .forEach { (path, entry) ->
                val previous = before[path]
                if (previous?.directory == true) return@forEach
                if (previous != null) {
                    require(delete(grant, path, recursive = false)) {
                        "The document provider could not replace $path"
                    }
                }
                createDirectory(grant, path)
            }

        after.entries
            .filter { it.key.isNotBlank() && !it.value.directory }
            .forEach { (path, entry) ->
                val previous = before[path]
                if (previous?.directory == true) {
                    require(delete(grant, path, recursive = true)) {
                        "The document provider could not replace $path"
                    }
                }
                if (previous == null || previous.digest != entry.digest || previous.directory) {
                    writeBytes(grant, path, entry.bytes ?: error("Missing command result bytes"))
                }
            }
    }

    private fun runHostShell(
        command: String,
        workingDirectory: File,
        timeoutMillis: Long,
    ): WorkspaceCommandResult {
        val process = ProcessBuilder("/system/bin/sh", "-c", command)
            .directory(workingDirectory)
            .redirectErrorStream(false)
            .start()
        val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
        val stdout = executor.submit<StreamCapture> { captureStream(process.inputStream) }
        val stderr = executor.submit<StreamCapture> { captureStream(process.errorStream) }
        return try {
            val completed = try {
                process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
            } catch (interrupted: InterruptedException) {
                process.destroyForcibly()
                throw interrupted
            }
            val timedOut = !completed
            if (timedOut) {
                process.destroy()
                if (!process.waitFor(250, TimeUnit.MILLISECONDS)) process.destroyForcibly()
            }
            val out = stdout.get(STREAM_CAPTURE_WAIT_SECONDS, TimeUnit.SECONDS)
            val err = stderr.get(STREAM_CAPTURE_WAIT_SECONDS, TimeUnit.SECONDS)
            WorkspaceCommandResult(
                exitCode = if (timedOut) -1 else process.exitValue(),
                stdout = out.text,
                stderr = err.text,
                timedOut = timedOut,
                truncated = out.truncated || err.truncated,
            )
        } finally {
            if (process.isAlive) process.destroyForcibly()
            executor.shutdownNow()
        }
    }

    private fun captureStream(input: InputStream): StreamCapture {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        var truncated = false
        input.use { stream ->
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                if (total < MAX_COMMAND_OUTPUT_BYTES) {
                    val keep = minOf(count.toLong(), MAX_COMMAND_OUTPUT_BYTES - total).toInt()
                    output.write(buffer, 0, keep)
                }
                total += count.toLong()
                if (total > MAX_COMMAND_OUTPUT_BYTES) truncated = true
            }
        }
        return StreamCapture(output.toString(Charsets.UTF_8.name()), truncated)
    }

    private fun readLocalBytes(file: File, maxBytes: Long): ByteArray {
        require(file.length() <= maxBytes) {
            "Command file is too large: ${file.length()} bytes (max $maxBytes bytes)"
        }
        return file.inputStream().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count.toLong()
                require(total <= maxBytes) { "Command file is too large (max $maxBytes bytes)" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private data class LocalMirrorEntry(
        val directory: Boolean,
        val digest: String,
        val bytes: ByteArray?,
    )

    private data class StreamCapture(val text: String, val truncated: Boolean)

    private data class CopyStats(var entries: Int = 0, var bytes: Long = 0L)

    private fun copyDocumentTree(
        grant: WorkspaceLocalDirectoryGrant,
        source: DocumentInfo,
        targetParent: DocumentInfo,
        targetName: String,
        stats: CopyStats,
        depth: Int,
    ): DocumentInfo {
        require(depth <= MAX_COPY_DEPTH) { "Copy exceeds maximum directory depth" }
        requireValidSafName(targetName)
        requireDirectoryCreateSupport(targetParent, targetName)
        stats.entries++
        require(stats.entries <= MAX_COPY_ENTRIES) { "Copy contains too many entries" }
        val createdUri = DocumentsContract.createDocument(resolver, targetParent.uri, source.mimeType, targetName)
            ?: error("The document provider could not create $targetName")
        val created = queryDocument(createdUri)
        require(created.name == targetName) { "The document provider changed the target name" }
        if (source.isDirectory) {
            children(Uri.parse(grant.treeUri), source).forEach { child ->
                copyDocumentTree(grant, child, created, child.name, stats, depth + 1)
            }
        } else {
            copyDocumentBytes(source.uri, created.uri, stats)
        }
        return created
    }

    private fun children(treeUri: Uri, directory: DocumentInfo): List<DocumentInfo> {
        require(directory.isDirectory) { "Path is not a directory" }
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri,
            directory.documentId,
        )
        val result = mutableListOf<DocumentInfo>()
        resolver.query(childrenUri, DOCUMENT_PROJECTION, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                require(result.size < MAX_DIRECTORY_ENTRIES) {
                    "Local directory contains more than $MAX_DIRECTORY_ENTRIES entries"
                }
                result += cursor.toDocumentInfo(treeUri)
            }
        } ?: error("The document provider did not return a directory listing")
        return result
    }

    private fun copyDocumentBytes(sourceUri: Uri, targetUri: Uri, stats: CopyStats) {
        val input = resolver.openInputStream(sourceUri)
            ?: error("The document provider could not open the source file")
        val output = try {
            resolver.openOutputStream(targetUri, "rwt")
                ?: throw FileNotFoundException("The document provider could not open the destination file")
        } catch (_: FileNotFoundException) {
            resolver.openOutputStream(targetUri, "wt")
                ?: throw FileNotFoundException("The document provider could not open the destination file")
        } catch (_: IllegalArgumentException) {
            resolver.openOutputStream(targetUri, "wt")
                ?: throw FileNotFoundException("The document provider could not open the destination file")
        } catch (_: UnsupportedOperationException) {
            resolver.openOutputStream(targetUri, "wt")
                ?: throw FileNotFoundException("The document provider could not open the destination file")
        }
        var copied = 0L
        input.use { source -> output.use { target ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = source.read(buffer)
                if (count < 0) break
                copied = safeAdd(copied, count.toLong())
                stats.bytes = safeAdd(stats.bytes, count.toLong())
                require(stats.bytes <= MAX_COPY_BYTES) {
                    "Copy exceeds $MAX_COPY_BYTES byte limit"
                }
                target.write(buffer, 0, count)
            }
            target.flush()
        } }
        if (copied > 0L) {
            // Some providers acknowledge create/open but leave a zero-byte
            // document when the output stream implementation is unsupported.
            // A one-byte read catches that case without buffering the whole copy.
            val hasContent = resolver.openInputStream(targetUri)?.use { it.read() >= 0 } == true
            require(hasContent) { "The document provider created an empty destination file" }
        }
    }

    private fun safeAdd(left: Long, right: Long): Long {
        require(right >= 0 && left <= Long.MAX_VALUE - right) { "File size overflow" }
        return left + right
    }

    fun documentUri(grant: WorkspaceLocalDirectoryGrant, path: String): Uri {
        requireReadAccess(grant)
        return resolve(grant, path).uri
    }

    private fun createTextDocument(
        grant: WorkspaceLocalDirectoryGrant,
        path: String,
    ): DocumentInfo {
        val parentPath = path.substringBeforeLast('/', missingDelimiterValue = "")
        val name = path.substringAfterLast('/')
        requireValidSafName(name)
        val parent = resolveOrCreateDirectory(grant, parentPath)
        require(parent.isDirectory) { "Parent path is not a directory: $parentPath" }
        requireDirectoryCreateSupport(parent, parentPath)
        val extension = name.substringAfterLast('.', "").lowercase()
        val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "text/plain"
        val uri = DocumentsContract.createDocument(resolver, parent.uri, mimeType, name)
            ?: error("The document provider could not create $name")
        return queryDocument(uri)
    }

    private fun writeBytes(uri: Uri, bytes: ByteArray) {
        try {
            writeBytesWithMode(uri, bytes, "rwt")
        } catch (_: FileNotFoundException) {
            writeBytesWithTruncateMode(uri, bytes)
        } catch (_: IllegalArgumentException) {
            writeBytesWithTruncateMode(uri, bytes)
        } catch (_: UnsupportedOperationException) {
            writeBytesWithTruncateMode(uri, bytes)
        }
    }

    private fun writeBytesWithTruncateMode(uri: Uri, bytes: ByteArray) {
        writeBytesWithMode(uri, bytes, "wt")
    }

    private fun writeBytesWithMode(uri: Uri, bytes: ByteArray, mode: String) {
        val output = resolver.openOutputStream(uri, mode)
            ?: throw FileNotFoundException("The document provider could not open the file for writing")
        output.use {
            it.write(bytes)
            it.flush()
        }
    }

    /**
     * Providers are inconsistent about throwing when a persisted grant is read-only.
     * When capability flags are present, fail before creating/truncating a document so
     * the user never gets a misleading success with a zero-byte file.
     */
    private fun requireDocumentWriteSupport(document: DocumentInfo, path: String) {
        if (document.flags != 0 &&
            document.flags and DocumentsContract.Document.FLAG_SUPPORTS_WRITE == 0
        ) {
            error("Local file is read-only: $path")
        }
    }

    private fun requireDirectoryCreateSupport(document: DocumentInfo, path: String) {
        if (document.flags != 0 &&
            document.flags and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE == 0
        ) {
            error("Local directory does not allow creating items: $path")
        }
    }

    private fun deleteDocumentBestEffort(uri: Uri) {
        runCatching { DocumentsContract.deleteDocument(resolver, uri) }
            .onFailure { runCatching { resolver.delete(uri, null, null) } }
    }

    private fun resolve(grant: WorkspaceLocalDirectoryGrant, path: String): DocumentInfo =
        resolveOrNull(grant, path) ?: error("Local path does not exist: $path")

    private fun resolveOrNull(grant: WorkspaceLocalDirectoryGrant, path: String): DocumentInfo? {
        val normalized = normalizeSafRelativePath(path)
        val treeUri = Uri.parse(grant.treeUri)
        var current = queryDocument(rootDocumentUri(treeUri))
        if (normalized.isBlank()) return current
        normalized.split('/').forEach { segment ->
            if (!current.isDirectory) return null
            current = findChild(treeUri, current.documentId, segment) ?: return null
        }
        return current
    }

    private fun findChild(treeUri: Uri, parentDocumentId: String, name: String): DocumentInfo? {
        requireValidSafName(name)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
        resolver.query(childrenUri, DOCUMENT_PROJECTION, null, null, null)?.use { cursor ->
            var scanned = 0
            while (cursor.moveToNext()) {
                scanned++
                require(scanned <= MAX_DIRECTORY_ENTRIES) {
                    "Local directory contains more than $MAX_DIRECTORY_ENTRIES entries"
                }
                val child = cursor.toDocumentInfo(treeUri)
                if (child.name == name) return child
            }
        }
        return null
    }

    private fun queryDocument(uri: Uri): DocumentInfo {
        resolver.query(uri, DOCUMENT_PROJECTION, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.toDocumentInfo(uri)
        }
        error("The document provider could not resolve the selected path")
    }

    private fun Cursor.toDocumentInfo(treeOrDocumentUri: Uri): DocumentInfo {
        val documentId = string(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            ?: error("The document provider omitted the document ID")
        val mimeType = string(DocumentsContract.Document.COLUMN_MIME_TYPE) ?: "application/octet-stream"
        val documentUri = if (DocumentsContract.isTreeUri(treeOrDocumentUri)) {
            DocumentsContract.buildDocumentUriUsingTree(treeOrDocumentUri, documentId)
        } else {
            treeOrDocumentUri
        }
        return DocumentInfo(
            documentId = documentId,
            uri = documentUri,
            name = string(DocumentsContract.Document.COLUMN_DISPLAY_NAME).orEmpty(),
            mimeType = mimeType,
            size = long(DocumentsContract.Document.COLUMN_SIZE) ?: -1L,
            updatedAt = long(DocumentsContract.Document.COLUMN_LAST_MODIFIED) ?: 0L,
            flags = long(DocumentsContract.Document.COLUMN_FLAGS)?.toInt() ?: 0,
        )
    }

    private fun Cursor.string(column: String): String? {
        val index = getColumnIndex(column)
        return if (index >= 0 && !isNull(index)) getString(index) else null
    }

    private fun Cursor.long(column: String): Long? {
        val index = getColumnIndex(column)
        return if (index >= 0 && !isNull(index)) getLong(index) else null
    }

    private fun requireReadAccess(grant: WorkspaceLocalDirectoryGrant) {
        require(grant.canRead && hasPersistedPermission(grant)) {
            "Local directory permission is unavailable; authorize the directory again"
        }
    }

    private fun requireWriteAccess(grant: WorkspaceLocalDirectoryGrant) {
        require(grant.canWrite && hasPersistedPermission(grant, write = true)) {
            "Local directory is read-only or its write permission is unavailable"
        }
    }

    private fun rootDocumentUri(treeUri: Uri): Uri =
        DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))

    private data class DocumentInfo(
        val documentId: String,
        val uri: Uri,
        val name: String,
        val mimeType: String,
        val size: Long,
        val updatedAt: Long,
        val flags: Int,
    ) {
        val isDirectory: Boolean
            get() = mimeType == DocumentsContract.Document.MIME_TYPE_DIR

        fun revision(bytes: ByteArray): WorkspaceFileRevision = WorkspaceFileRevision(
            sizeBytes = bytes.size.toLong(),
            updatedAt = updatedAt,
            sha256 = MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { "%02x".format(it) },
        )

        fun toEntry(grant: WorkspaceLocalDirectoryGrant, path: String): WorkspaceLocalFileEntry =
            WorkspaceLocalFileEntry(
                grantId = grant.id,
                path = path,
                name = name,
                isDirectory = isDirectory,
                sizeBytes = size.coerceAtLeast(0L),
                updatedAt = updatedAt,
                mimeType = mimeType,
                uri = uri.toString(),
                // COLUMN_FLAGS is optional for custom providers. Treat an omitted
                // flag set as unknown and let the actual operation report a
                // provider-specific denial instead of falsely hiding write access.
                canWrite = grant.canWrite && (flags == 0 || if (isDirectory) {
                    flags and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE != 0
                } else {
                    flags and DocumentsContract.Document.FLAG_SUPPORTS_WRITE != 0
                }),
            )
    }

    companion object {
        const val MAX_TEXT_READ_BYTES = 1024L * 1024
        const val MAX_TEXT_WRITE_BYTES = 2L * 1024 * 1024
        const val MAX_BINARY_READ_BYTES = 8L * 1024 * 1024
        const val MAX_BINARY_WRITE_BYTES = 8L * 1024 * 1024
        const val DEFAULT_COMMAND_TIMEOUT_MS = 30_000L
        const val MAX_COMMAND_TIMEOUT_MS = 600_000L
        private const val MAX_COMMAND_LENGTH = 64 * 1024
        private const val MAX_COMMAND_OUTPUT_BYTES = 256L * 1024
        private const val MAX_COMMAND_FILE_BYTES = MAX_BINARY_READ_BYTES
        private const val MAX_COMMAND_TREE_BYTES = 64L * 1024 * 1024
        private const val MAX_COMMAND_TREE_ENTRIES = 20_000
        private const val MAX_COMMAND_PAGE_SIZE = 200
        private const val STREAM_CAPTURE_WAIT_SECONDS = 5L
        private const val DEFAULT_PAGE_SIZE = 100
        private const val MAX_PAGE_SIZE = 200
        private const val MAX_DIRECTORY_ENTRIES = 5_000
        private const val MAX_COPY_ENTRIES = 20_000
        private const val MAX_COPY_DEPTH = 64
        private const val MAX_COPY_BYTES = 64L * 1024 * 1024
        private const val PERSISTABLE_PERMISSION_FLAGS =
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        private val DOCUMENT_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_FLAGS,
        )
    }
}

internal fun normalizeSafRelativePath(path: String): String {
    val normalized = path.replace('\\', '/').trim().trim('/')
    require(!normalized.contains('\u0000')) { "Path contains an invalid character" }
    if (normalized.isBlank()) return ""
    normalized.split('/').forEach(::requireValidSafName)
    return normalized
}

internal fun joinSafPath(parent: String, name: String): String {
    requireValidSafName(name)
    val normalizedParent = normalizeSafRelativePath(parent)
    return if (normalizedParent.isBlank()) name else "$normalizedParent/$name"
}

private fun requireValidSafName(name: String) {
    require(name.isNotBlank() && name != "." && name != "..") { "Invalid local path segment" }
    require(!name.contains('/') && !name.contains('\\') && !name.contains('\u0000')) {
        "Invalid local path segment: $name"
    }
}
