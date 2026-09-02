package me.rerere.workspace

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.UUID

class WorkspaceManager(
    private val baseDir: File,
    private val config: WorkspaceConfig = WorkspaceConfig(),
    private val shellRunner: WorkspaceShellRunner = HostShellRunner(),
    private val bindMounts: List<WorkspaceBindMount> = emptyList(),
) {
    private val fileSystem = WorkspaceFileSystem(config)

    // 按 target 长度降序, 保证 /a/b 优先于 /a 匹配
    private val sortedBindMounts = bindMounts.sortedByDescending { it.target.trimEnd('/').length }

    fun configuredBindMounts(): List<WorkspaceBindMount> = bindMounts.toList()

    init {
        baseDir.mkdirs()
    }

    fun ensureWorkspace(root: String): File {
        val dir = workspaceDir(root)
        filesDir(root).mkdirs()
        linuxDir(root).mkdirs()
        tempDir(root).mkdirs()
        return dir
    }

    fun workspaceDir(root: String): File {
        requireValidRoot(root)
        return File(baseDir, root)
    }

    fun filesDir(root: String): File = File(workspaceDir(root), FILES_DIR)

    fun linuxDir(root: String): File = File(workspaceDir(root), LINUX_DIR)

    fun tempDir(root: String): File = File(workspaceDir(root), TEMP_DIR)

    fun trashDir(root: String, area: WorkspaceStorageArea): File =
        File(File(workspaceDir(root), TRASH_DIR), area.name.lowercase())

    fun hasRootfs(root: String): Boolean = linuxDir(root).hasShellEntryPoint()

    fun deleteWorkspace(root: String): Boolean = workspaceDir(root).deleteRecursively()

    fun listFiles(
        root: String,
        path: String = "",
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
    ): List<WorkspaceFileEntry> =
        fileSystem.list(areaDir(root, area), path)

    fun listFilePage(
        root: String,
        path: String = "",
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
        offset: Int = 0,
    ): WorkspaceFilePage = fileSystem.listPage(areaDir(root, area), path, offset)

    fun readText(
        root: String,
        path: String,
        charset: Charset = StandardCharsets.UTF_8,
    ): String = fileSystem.readText(filesDir(root), path, charset)

    fun readTextSnapshot(
        root: String,
        path: String,
        charset: Charset = StandardCharsets.UTF_8,
    ): WorkspaceTextSnapshot = fileSystem.readTextSnapshot(filesDir(root), path, charset)

    fun writeText(
        root: String,
        path: String,
        text: String,
        overwrite: Boolean = true,
        charset: Charset = StandardCharsets.UTF_8,
        expectedRevision: WorkspaceFileRevision? = null,
    ): WorkspaceFileEntry = fileSystem.writeText(
        filesDir(root), path, text, overwrite, charset, expectedRevision
    )

    fun importFile(
        root: String,
        destinationPath: String,
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
        fileName: String,
        inputStream: InputStream,
    ): WorkspaceFileEntry {
        val areaRoot = areaDir(root, area)
        val targetPath = if (destinationPath.isBlank()) fileName else "$destinationPath/$fileName"
        return fileSystem.importBytes(areaRoot, targetPath, inputStream)
    }

    fun fileSize(
        root: String,
        path: String,
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
    ): Long {
        val file = fileSystem.resolve(areaDir(root, area), path)
        require(file.exists()) { "File does not exist: $path" }
        require(file.isFile) { "Path is not a file: $path" }
        return file.length()
    }

    fun exportFile(
        root: String,
        path: String,
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
        outputStream: OutputStream,
    ) {
        val file = fileSystem.resolve(areaDir(root, area), path)
        require(file.exists()) { "File does not exist: $path" }
        require(file.isFile) { "Path is not a file: $path" }
        outputStream.use { out -> file.inputStream().use { it.copyTo(out) } }
    }

    /**
     * 把 Rootfs 内的绝对路径映射到宿主机上的真实文件。
     *
     * bind mount 的 source 本身就是 Android 侧的普通目录, 因此 /skills 这类挂载路径
     * 可以直接用文件 IO 访问, 无需经过 PRoot; 只是 Rootfs 目录里对应位置是个空挂载点,
     * 按 [WorkspaceStorageArea.LINUX] 解析必然落空。
     */
    fun resolveRootfsPath(root: String, path: String): RootfsLocation {
        val trimmed = path.trim().trimEnd('/').ifBlank { "/" }
        require(trimmed.startsWith("/")) { "Rootfs path must be absolute: $path" }

        sortedBindMounts.forEach { mount ->
            val target = mount.target.trimEnd('/')
            if (trimmed == target) return RootfsLocation(mount.source, "")
            if (trimmed.startsWith("$target/")) {
                return RootfsLocation(mount.source, trimmed.removePrefix("$target/"))
            }
        }

        if (trimmed == ROOTFS_WORKSPACE_DIR || trimmed.startsWith("$ROOTFS_WORKSPACE_DIR/")) {
            return RootfsLocation(
                rootDir = filesDir(root),
                relativePath = trimmed.removePrefix(ROOTFS_WORKSPACE_DIR).trimStart('/'),
            )
        }

        // 内核伪文件系统: 显式拒绝, 而不是回落到一个必然读不到的物理路径
        KERNEL_FS_MOUNTS.firstOrNull { trimmed == it || trimmed.startsWith("$it/") }?.let {
            error("$it is a kernel filesystem and cannot be read as a file, use workspace_shell instead")
        }

        return RootfsLocation(linuxDir(root), trimmed.trimStart('/'))
    }

    fun rootfsFileSize(root: String, path: String): Long =
        resolveRootfsFile(root, path).also { it.requireReadableFile(path) }.length()

    fun exportRootfsFile(root: String, path: String, outputStream: OutputStream) {
        val file = resolveRootfsFile(root, path)
        file.requireReadableFile(path)
        outputStream.use { out -> file.inputStream().use { it.copyTo(out) } }
    }

    fun writeRootfsText(
        root: String,
        path: String,
        text: String,
        overwrite: Boolean = true,
    ): WorkspaceFileEntry {
        val location = resolveRootfsPath(root, path)
        require(location.relativePath.isNotBlank()) { "Rootfs path must refer to a file: $path" }
        return fileSystem.writeText(
            root = location.rootDir,
            path = location.relativePath,
            text = text,
            overwrite = overwrite,
        ).copy(
            path = path,
            name = path.trimEnd('/').substringAfterLast('/').ifBlank { "/" },
        )
    }

    fun createWorkspaceDirectory(root: String, path: String): WorkspaceFileEntry =
        fileSystem.createDirectory(filesDir(root), workspaceRelativePath(path))

    fun moveWorkspacePath(
        root: String,
        source: String,
        target: String,
        overwrite: Boolean = false,
    ): WorkspaceFileEntry = fileSystem.move(
        filesDir(root),
        workspaceRelativePath(source),
        workspaceRelativePath(target),
        overwrite,
    )

    fun copyWorkspacePath(
        root: String,
        source: String,
        target: String,
        overwrite: Boolean = false,
    ): WorkspaceFileEntry = fileSystem.copy(
        filesDir(root),
        workspaceRelativePath(source),
        workspaceRelativePath(target),
        overwrite,
    )

    fun moveWorkspacePathToTrash(
        root: String,
        path: String,
        recursive: Boolean = false,
    ): WorkspaceDeletedFile? = fileSystem.moveToTrash(
        root = filesDir(root),
        path = workspaceRelativePath(path),
        recursive = recursive,
        trashRoot = trashDir(root, WorkspaceStorageArea.FILES),
    )?.let { (token, deletedAt) ->
        WorkspaceDeletedFile(
            token = token,
            originalPath = workspaceRelativePath(path),
            area = WorkspaceStorageArea.FILES,
            deletedAt = deletedAt,
        )
    }

    private fun workspaceRelativePath(path: String): String {
        val normalized = path.replace('\\', '/').trim().trimEnd('/')
        require(normalized == ROOTFS_WORKSPACE_DIR || normalized.startsWith("$ROOTFS_WORKSPACE_DIR/")) {
            "Direct file operations are limited to $ROOTFS_WORKSPACE_DIR: $path"
        }
        val relative = normalized.removePrefix(ROOTFS_WORKSPACE_DIR).trimStart('/')
        require(relative.isNotBlank()) { "Path must refer to an item inside $ROOTFS_WORKSPACE_DIR" }
        return relative
    }

    private fun resolveRootfsFile(root: String, path: String): File {
        val location = resolveRootfsPath(root, path)
        return fileSystem.resolve(location.rootDir, location.relativePath)
    }

    private fun File.requireReadableFile(path: String) {
        require(exists()) { "File does not exist: $path" }
        require(isFile) { "Path is not a file: $path" }
    }

    fun deleteFile(
        root: String,
        path: String,
        recursive: Boolean = false,
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
    ): Boolean =
        fileSystem.delete(areaDir(root, area), path, recursive)

    fun moveFileToTrash(
        root: String,
        path: String,
        recursive: Boolean = false,
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
    ): WorkspaceDeletedFile? = fileSystem.moveToTrash(
        root = areaDir(root, area),
        path = path,
        recursive = recursive,
        trashRoot = trashDir(root, area),
    )?.let { (token, deletedAt) ->
        WorkspaceDeletedFile(token, path, area, deletedAt)
    }

    fun restoreDeletedFile(root: String, deletedFile: WorkspaceDeletedFile): WorkspaceFileEntry =
        fileSystem.restoreFromTrash(
            root = areaDir(root, deletedFile.area),
            path = deletedFile.originalPath,
            trashRoot = trashDir(root, deletedFile.area),
            token = deletedFile.token,
        )

    fun moveFile(root: String, source: String, target: String, overwrite: Boolean = false): WorkspaceFileEntry =
        fileSystem.move(filesDir(root), source, target, overwrite)

    fun copyFile(root: String, source: String, target: String, overwrite: Boolean = false): WorkspaceFileEntry =
        fileSystem.copy(filesDir(root), source, target, overwrite)

    fun glob(root: String, pattern: String, path: String = ""): List<WorkspaceFileEntry> =
        fileSystem.glob(filesDir(root), pattern, path)

    fun grep(
        root: String,
        query: String,
        path: String = "",
        regex: Boolean = false,
        ignoreCase: Boolean = true,
        includeGlob: String? = null,
    ): List<WorkspaceSearchMatch> =
        fileSystem.grep(filesDir(root), query, path, regex, ignoreCase, includeGlob)

    fun executeCommand(
        root: String,
        command: String,
        cwd: String = "",
        timeoutMillis: Long = DEFAULT_COMMAND_TIMEOUT_MS,
        stdin: ByteArray? = null,
    ): WorkspaceCommandResult {
        require(command.isNotBlank()) { "Command is required" }
        require(!command.contains('\u0000')) { "Command contains an invalid character" }
        require(command.length <= MAX_COMMAND_LENGTH) {
            "Command is too long (max $MAX_COMMAND_LENGTH characters)"
        }
        require(timeoutMillis in 1L..MAX_COMMAND_TIMEOUT_MS) {
            "Command timeout must be between 1 and ${MAX_COMMAND_TIMEOUT_MS / 1_000} seconds"
        }
        val workingDir = fileSystem.resolve(filesDir(root), cwd)
        require(workingDir.exists()) { "Working directory does not exist: $cwd" }
        require(workingDir.isDirectory) { "Working path is not a directory: $cwd" }

        return shellRunner.execute(
            WorkspaceShellContext(
                root = root,
                command = command,
                cwd = cwd,
                filesDir = filesDir(root),
                linuxDir = linuxDir(root),
                tempDir = tempDir(root),
                workingDir = workingDir,
                timeoutMillis = timeoutMillis,
                stdin = stdin,
                bindMounts = bindMounts,
            )
        )
    }

    private fun requireValidRoot(root: String) {
        require(root.matches(ROOT_NAME_REGEX)) {
            "Invalid workspace root name: $root"
        }
    }

    private fun areaDir(root: String, area: WorkspaceStorageArea): File = when (area) {
        WorkspaceStorageArea.FILES -> filesDir(root)
        WorkspaceStorageArea.LINUX -> linuxDir(root)
    }

    fun cleanupAllTempDirs() {
        val roots = baseDir.listFiles()?.filter { it.isDirectory } ?: return
        for (dir in roots) {
            val root = dir.name
            if (!root.matches(ROOT_NAME_REGEX)) continue
            recoverInterruptedRootfsSwap(root)
            // PRoot temp files
            tempDir(root).let { if (it.exists()) it.deleteRecursively() }
            // Rootfs /tmp and /var/tmp
            File(linuxDir(root), "tmp").let { if (it.exists()) it.deleteRecursively() }
            File(linuxDir(root), "var/tmp").let { if (it.exists()) it.deleteRecursively() }
            WorkspaceStorageArea.entries.forEach { area ->
                fileSystem.cleanupTrash(trashDir(root, area))
            }
        }
    }

    private fun recoverInterruptedRootfsSwap(root: String) {
        val linux = linuxDir(root)
        val backup = File(tempDir(root), "rootfs-backup")
        if (!backup.exists()) return
        if (!linux.hasShellEntryPoint() && backup.hasShellEntryPoint()) {
            linux.deleteRecursively()
            runCatching {
                try {
                    Files.move(backup.toPath(), linux.toPath(), StandardCopyOption.ATOMIC_MOVE)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(backup.toPath(), linux.toPath())
                }
            }.onFailure {
                recordAudit(root, "rootfs_recovery", success = false, detail = it.message.orEmpty())
            }.onSuccess {
                recordAudit(root, "rootfs_recovery", success = true)
            }
        }
    }

    fun storageStats(root: String): WorkspaceStorageStats {
        val (filesBytes, fileCount) = fileSystem.directoryStats(filesDir(root))
        val rootfsBytes = fileSystem.directoryStats(linuxDir(root)).first
        val trashBytes = WorkspaceStorageArea.entries.sumOf { area ->
            fileSystem.directoryStats(trashDir(root, area)).first
        }
        return WorkspaceStorageStats(filesBytes, rootfsBytes, trashBytes, fileCount)
    }

    fun filesAreaStats(root: String): Pair<Long, Int> =
        fileSystem.directoryStats(filesDir(root))

    fun readAccessPolicy(root: String): WorkspaceAccessPolicy {
        val policyFile = File(metadataDir(root), POLICY_FILE)
        if (!policyFile.isFile) return WorkspaceAccessPolicy()
        return runCatching {
            val properties = Properties().apply {
                policyFile.inputStream().use(::load)
            }
            val roots = properties.getProperty("allowedWriteRoots")
                ?.split('|')
                ?.map(String::trim)
                ?.filter { it.startsWith("/") }
                ?.distinct()
                .orEmpty()
                .ifEmpty { listOf("/") }
            WorkspaceAccessPolicy(
                readOnly = properties.getProperty("readOnly").toBooleanStrictOrNull() ?: false,
                shellEnabled = properties.getProperty("shellEnabled").toBooleanStrictOrNull() ?: true,
                allowedWriteRoots = roots,
            )
        }.getOrDefault(WorkspaceAccessPolicy())
    }

    fun writeAccessPolicy(root: String, policy: WorkspaceAccessPolicy) {
        require(policy.allowedWriteRoots.isNotEmpty()) { "At least one writable path is required" }
        val roots = policy.allowedWriteRoots.map { candidate ->
            val normalized = candidate.replace('\\', '/').trim().trimEnd('/').ifBlank { "/" }
            require(normalized.startsWith("/") && !normalized.contains('\u0000')) {
                "Writable paths must be absolute Rootfs paths"
            }
            require(normalized.split('/').none { it == ".." }) { "Writable path cannot contain .." }
            normalized
        }.distinct()
        val properties = Properties().apply {
            setProperty("readOnly", policy.readOnly.toString())
            setProperty("shellEnabled", policy.shellEnabled.toString())
            setProperty("allowedWriteRoots", roots.joinToString("|"))
        }
        val target = File(metadataDir(root), POLICY_FILE)
        val temp = File(target.parentFile, ".${target.name}-${UUID.randomUUID()}.tmp")
        try {
            temp.outputStream().use { output ->
                properties.store(output, "RikkaHub workspace security policy")
            }
            replaceMetadataFile(temp, target)
        } finally {
            temp.delete()
        }
    }

    fun readLocalDirectoryGrants(root: String): List<WorkspaceLocalDirectoryGrant> {
        val grantsFile = File(metadataDir(root), LOCAL_DIRECTORY_GRANTS_FILE)
        if (!grantsFile.isFile) return emptyList()
        return runCatching {
            val properties = Properties().apply {
                grantsFile.inputStream().use(::load)
            }
            val count = properties.getProperty("count")?.toIntOrNull()
                ?.coerceIn(0, MAX_LOCAL_DIRECTORY_GRANTS)
                ?: 0
            buildList {
                repeat(count) { index ->
                    val prefix = "grant.$index."
                    val id = properties.getProperty("${prefix}id")?.trim().orEmpty()
                    val treeUri = properties.getProperty("${prefix}treeUri")?.trim().orEmpty()
                    val displayName = properties.getProperty("${prefix}displayName")?.trim().orEmpty()
                    val createdAt = properties.getProperty("${prefix}createdAt")?.toLongOrNull() ?: 0L
                    if (id.isNotBlank() && treeUri.isNotBlank() && displayName.isNotBlank()) {
                        add(
                            WorkspaceLocalDirectoryGrant(
                                id = id,
                                treeUri = treeUri,
                                displayName = displayName,
                                canRead = properties.getProperty("${prefix}canRead")
                                    ?.toBooleanStrictOrNull() ?: false,
                                canWrite = properties.getProperty("${prefix}canWrite")
                                    ?.toBooleanStrictOrNull() ?: false,
                                createdAt = createdAt,
                            )
                        )
                    }
                }
            }.distinctBy { it.id }
        }.getOrDefault(emptyList())
    }

    fun writeLocalDirectoryGrants(root: String, grants: List<WorkspaceLocalDirectoryGrant>) {
        require(grants.size <= MAX_LOCAL_DIRECTORY_GRANTS) {
            "A workspace can authorize at most $MAX_LOCAL_DIRECTORY_GRANTS local directories"
        }
        require(grants.map { it.id }.distinct().size == grants.size) {
            "Local directory grant IDs must be unique"
        }
        require(grants.map { it.treeUri }.distinct().size == grants.size) {
            "The same local directory cannot be authorized twice"
        }
        grants.forEach { grant ->
            require(grant.id.isNotBlank()) { "Local directory grant ID is required" }
            require(grant.treeUri.isNotBlank()) { "Local directory URI is required" }
            require(grant.displayName.isNotBlank()) { "Local directory name is required" }
        }

        val target = File(metadataDir(root), LOCAL_DIRECTORY_GRANTS_FILE)
        if (grants.isEmpty()) {
            target.delete()
            return
        }
        val properties = Properties().apply {
            setProperty("count", grants.size.toString())
            grants.forEachIndexed { index, grant ->
                val prefix = "grant.$index."
                setProperty("${prefix}id", grant.id)
                setProperty("${prefix}treeUri", grant.treeUri)
                setProperty("${prefix}displayName", grant.displayName)
                setProperty("${prefix}canRead", grant.canRead.toString())
                setProperty("${prefix}canWrite", grant.canWrite.toString())
                setProperty("${prefix}createdAt", grant.createdAt.toString())
            }
        }
        val temp = File(target.parentFile, ".${target.name}-${UUID.randomUUID()}.tmp")
        try {
            temp.outputStream().use { output ->
                properties.store(output, "RikkaHub workspace SAF directory grants")
            }
            replaceMetadataFile(temp, target)
        } finally {
            temp.delete()
        }
    }

    @Synchronized
    fun recordAudit(
        root: String,
        action: String,
        target: String = "",
        success: Boolean = true,
        detail: String = "",
    ) {
        val auditFile = File(metadataDir(root), AUDIT_FILE)
        if (auditFile.length() >= MAX_AUDIT_BYTES) {
            val previous = File(auditFile.parentFile, "$AUDIT_FILE.1")
            previous.delete()
            auditFile.renameTo(previous)
        }
        val fields = listOf(
            System.currentTimeMillis().toString(),
            sanitizeAudit(action),
            sanitizeAudit(target),
            success.toString(),
            sanitizeAudit(detail),
        )
        auditFile.appendText(fields.joinToString("\t", postfix = "\n"), Charsets.UTF_8)
    }

    fun readAudit(root: String, limit: Int = 20): List<WorkspaceAuditEntry> {
        if (limit <= 0) return emptyList()
        val auditFile = File(metadataDir(root), AUDIT_FILE)
        if (!auditFile.isFile) return emptyList()
        return auditFile.useLines(Charsets.UTF_8) { lines ->
            lines.mapNotNull { line ->
                val fields = line.split('\t', limit = 5)
                if (fields.size != 5) return@mapNotNull null
                WorkspaceAuditEntry(
                    timestamp = fields[0].toLongOrNull() ?: return@mapNotNull null,
                    action = fields[1],
                    target = fields[2],
                    success = fields[3].toBooleanStrictOrNull() ?: return@mapNotNull null,
                    detail = fields[4],
                )
            }.toList().takeLast(limit).asReversed()
        }
    }

    fun integrityReport(root: String): WorkspaceIntegrityReport {
        val issues = mutableListOf<String>()
        val workspace = runCatching { workspaceDir(root) }.getOrElse {
            return WorkspaceIntegrityReport(false, listOf("Invalid workspace root"))
        }
        val files = filesDir(root)
        if (!workspace.isDirectory) issues += "Workspace directory is missing"
        if (!files.isDirectory) issues += "Files directory is missing"
        if (files.exists() && !files.canRead()) issues += "Files directory is not readable"
        if (files.exists() && !files.canWrite()) issues += "Files directory is not writable"
        if (File(tempDir(root), "rootfs-staging").exists()) issues += "Incomplete Rootfs staging directory exists"
        if (File(tempDir(root), "rootfs-backup").exists()) issues += "Rootfs rollback directory requires cleanup"
        return WorkspaceIntegrityReport(issues.isEmpty(), issues)
    }

    fun repairWorkspace(root: String): WorkspaceIntegrityReport {
        ensureWorkspace(root)
        recoverInterruptedRootfsSwap(root)
        val staging = File(tempDir(root), "rootfs-staging")
        val backup = File(tempDir(root), "rootfs-backup")
        if (backup.exists() &&
            (linuxDir(root).hasShellEntryPoint() || !backup.hasShellEntryPoint())
        ) {
            backup.deleteRecursively()
        }
        if (staging.exists() && !backup.exists()) staging.deleteRecursively()
        WorkspaceStorageArea.entries.forEach { area ->
            fileSystem.cleanupTrash(trashDir(root, area))
        }
        return integrityReport(root)
    }

    private fun metadataDir(root: String): File =
        File(workspaceDir(root), METADATA_DIR).apply { mkdirs() }

    private fun replaceMetadataFile(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun sanitizeAudit(value: String): String =
        value.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ').take(MAX_AUDIT_FIELD_LENGTH)

    private fun File.hasShellEntryPoint(): Boolean = listOf(
        "bin/sh",
        "usr/bin/sh",
        "bin/bash",
        "usr/bin/bash",
    ).any { File(this, it).isFile }

    companion object {
        private const val FILES_DIR = "files"
        private const val LINUX_DIR = "linux"
        private const val TEMP_DIR = "tmp"
        private const val TRASH_DIR = ".rikkahub-trash"
        private const val METADATA_DIR = ".rikkahub"
        private const val POLICY_FILE = "policy.properties"
        private const val LOCAL_DIRECTORY_GRANTS_FILE = "local-directories.properties"
        private const val AUDIT_FILE = "audit.tsv"
        private const val MAX_LOCAL_DIRECTORY_GRANTS = 16
        private const val MAX_AUDIT_BYTES = 2L * 1024 * 1024
        private const val MAX_AUDIT_FIELD_LENGTH = 1_024
        const val DEFAULT_COMMAND_TIMEOUT_MS = 30_000L
        const val MAX_COMMAND_TIMEOUT_MS = 10 * 60 * 1_000L
        const val MAX_COMMAND_LENGTH = 32 * 1024

        /** Rootfs 内工作区文件区的挂载点 */
        const val ROOTFS_WORKSPACE_DIR = "/workspace"

        /** 由宿主机透传的内核伪文件系统, 只能通过 shell 访问 */
        val KERNEL_FS_MOUNTS = listOf("/dev", "/proc", "/sys")

        private val ROOT_NAME_REGEX = Regex("[A-Za-z0-9._-]+")
    }
}

/** Rootfs 内绝对路径在宿主机上的落点 */
data class RootfsLocation(
    val rootDir: File,
    val relativePath: String,
)
