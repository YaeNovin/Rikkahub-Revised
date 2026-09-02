package me.rerere.workspace

data class Workspace(
    val id: String,
    val name: String,
    val root: String,
    val shellStatus: WorkspaceShellStatus = WorkspaceShellStatus.DISABLED,
    val createdAt: Long,
    val updatedAt: Long,
    val lastAccessAt: Long? = null,
)

enum class WorkspaceShellStatus {
    DISABLED,
    INSTALLING,
    READY,
    BROKEN,
}

enum class WorkspaceStorageArea {
    FILES,
    LINUX,
}

enum class RootfsInstallStage {
    DOWNLOADING,
    EXTRACTING,
    INSTALLED,
}

data class RootfsInstallProgress(
    val stage: RootfsInstallStage,
    val bytesRead: Long = 0,
    val totalBytes: Long? = null,
    val entriesExtracted: Int = 0,
    val currentEntry: String? = null,
)

data class WorkspaceConfig(
    val maxReadBytes: Long = 512 * 1024,
    val maxWriteBytes: Long = 2 * 1024 * 1024,
    val maxImportBytes: Long = 64 * 1024 * 1024,
    val maxFilesAreaBytes: Long = 2L * 1024 * 1024 * 1024,
    val maxListEntries: Int = 500,
    val maxSearchResults: Int = 100,
    val maxWalkEntries: Int = 20_000,
    val maxWalkDepth: Int = 64,
    val maxStatsEntries: Int = 500_000,
    val maxStatsDepth: Int = 128,
    /** Direct copy operations are intentionally bounded; shell cp remains available for advanced cases. */
    val maxCopyBytes: Long = 64L * 1024 * 1024,
    val maxCopyEntries: Int = 20_000,
    val maxCopyDepth: Int = 64,
    val trashRetentionMillis: Long = 7L * 24 * 60 * 60 * 1000,
)

data class WorkspaceFileEntry(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val updatedAt: Long,
)

data class WorkspaceFilePage(
    val entries: List<WorkspaceFileEntry>,
    val nextOffset: Int?,
    val totalEntries: Int,
)

data class WorkspaceFileRevision(
    val sizeBytes: Long,
    val updatedAt: Long,
    val sha256: String,
)

data class WorkspaceTextSnapshot(
    val text: String,
    val revision: WorkspaceFileRevision,
)

data class WorkspaceDeletedFile(
    val token: String,
    val originalPath: String,
    val area: WorkspaceStorageArea,
    val deletedAt: Long,
)

data class WorkspaceStorageStats(
    val filesBytes: Long,
    val rootfsBytes: Long,
    val trashBytes: Long,
    val fileCount: Int,
)

data class WorkspaceAccessPolicy(
    val readOnly: Boolean = false,
    val shellEnabled: Boolean = true,
    val allowedWriteRoots: List<String> = listOf("/"),
) {
    fun allowsWrite(path: String): Boolean {
        if (readOnly) return false
        val normalized = path.replace('\\', '/').trim().trimEnd('/').ifBlank { "/" }
        return allowedWriteRoots.any { root ->
            val normalizedRoot = root.replace('\\', '/').trim().trimEnd('/').ifBlank { "/" }
            normalizedRoot == "/" || normalized == normalizedRoot || normalized.startsWith("$normalizedRoot/")
        }
    }

    val effectiveShellEnabled: Boolean
        get() = shellEnabled && !readOnly && allowedWriteRoots.any { it.trim() == "/" }
}

data class WorkspaceAuditEntry(
    val timestamp: Long,
    val action: String,
    val target: String,
    val success: Boolean,
    val detail: String,
)

data class WorkspaceLocalDirectoryGrant(
    val id: String,
    val treeUri: String,
    val displayName: String,
    val canRead: Boolean,
    val canWrite: Boolean,
    val createdAt: Long,
)

data class WorkspaceIntegrityReport(
    val healthy: Boolean,
    val issues: List<String>,
)

data class WorkspaceSearchMatch(
    val path: String,
    val line: Int,
    val text: String,
)

data class WorkspaceCommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean = false,
    val truncated: Boolean = false,
)
