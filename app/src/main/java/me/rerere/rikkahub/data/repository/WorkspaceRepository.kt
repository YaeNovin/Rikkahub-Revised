package me.rerere.rikkahub.data.repository

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.dao.WorkspaceDAO
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.files.WorkspaceLocalFileEntry
import me.rerere.rikkahub.data.files.WorkspaceLocalFilePage
import me.rerere.rikkahub.data.files.WorkspaceLocalTextSnapshot
import me.rerere.rikkahub.data.files.WorkspaceSafFileSystem
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.workspace.RootfsInstallProgress
import me.rerere.workspace.RootfsInstaller
import me.rerere.workspace.WorkspaceCommandResult
import me.rerere.workspace.WorkspaceAccessPolicy
import me.rerere.workspace.WorkspaceAuditEntry
import me.rerere.workspace.WorkspaceDeletedFile
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceFilePage
import me.rerere.workspace.WorkspaceFileRevision
import me.rerere.workspace.WorkspaceManager
import me.rerere.workspace.WorkspaceShellStatus
import me.rerere.workspace.WorkspaceStorageStats
import me.rerere.workspace.WorkspaceStorageArea
import me.rerere.workspace.WorkspaceTextSnapshot
import me.rerere.workspace.WorkspaceIntegrityReport
import me.rerere.workspace.WorkspaceLocalDirectoryGrant
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

class WorkspaceRepository(
    private val dao: WorkspaceDAO,
    private val manager: WorkspaceManager,
    private val rootfsInstaller: RootfsInstaller,
    private val settingsStore: SettingsStore,
    private val safFileSystem: WorkspaceSafFileSystem,
) {
    // Android 12+ limits app descendant processes. Serialize shell commands per
    // workspace so repeated tool calls cannot spawn competing PRoot trees.
    private val shellLocks = ConcurrentHashMap<String, Mutex>()

    fun listFlow(): Flow<List<WorkspaceEntity>> = dao.listFlow()

    suspend fun checkIntegrity() = withContext(Dispatchers.IO) {
        val workspaces = dao.getAll()
        for (workspace in workspaces) {
            val dir = manager.workspaceDir(workspace.root)
            if (!dir.exists()) {
                // 目录缺失时不删除记录(例如恢复备份后工作区文件未随数据库一起恢复),
                // 仅标记为 BROKEN 以保留记录与助手绑定, 避免误删用户工作区
                Log.w(TAG, "Workspace directory missing, marking as broken: id=${workspace.id}, root=${workspace.root}")
                if (workspace.shellStatus != WorkspaceShellStatus.BROKEN.name) {
                    updateShellState(workspace.id, WorkspaceShellStatus.BROKEN.name)
                }
                continue
            }
            val statusName = workspace.shellStatus
            if ((statusName == WorkspaceShellStatus.READY.name || statusName == WorkspaceShellStatus.INSTALLING.name)
                && !manager.hasRootfs(workspace.root)
            ) {
                Log.w(TAG, "Rootfs missing, resetting shell status: id=${workspace.id}")
                updateShellState(workspace.id, WorkspaceShellStatus.DISABLED.name)
            }
        }
    }

    suspend fun getById(id: String): WorkspaceEntity? = dao.getById(id)

    suspend fun create(name: String): WorkspaceEntity {
        val id = Uuid.random().toString()
        val now = System.currentTimeMillis()
        val finalName = name.trim().ifBlank { "Workspace" }
        require(!isNameTaken(finalName, excludeId = null)) {
            "Workspace name already exists: $finalName"
        }
        val workspace = WorkspaceEntity(
            id = id,
            name = finalName,
            root = id,
            createdAt = now,
            updatedAt = now,
            lastAccessAt = null,
        )
        manager.ensureWorkspace(workspace.root)
        dao.upsert(workspace)
        manager.recordAudit(workspace.root, action = "workspace_create")
        return workspace
    }

    suspend fun rename(id: String, name: String): Boolean {
        val workspace = dao.getById(id) ?: return false
        val finalName = name.trim().ifBlank { workspace.name }
        require(!isNameTaken(finalName, excludeId = id)) {
            "Workspace name already exists: $finalName"
        }
        dao.upsert(
            workspace.copy(
                name = finalName,
                updatedAt = System.currentTimeMillis(),
            )
        )
        manager.recordAudit(workspace.root, action = "workspace_rename", target = finalName)
        return true
    }

    /** 名字是否已被其他 workspace 占用（trim 后精确匹配，排除 [excludeId] 自身） */
    suspend fun isNameTaken(name: String, excludeId: String?): Boolean {
        val target = name.trim()
        return dao.getAll().any { it.id != excludeId && it.name.trim() == target }
    }

    suspend fun setToolApproval(id: String, toolName: String, needsApproval: Boolean): Boolean {
        val workspace = dao.getById(id) ?: return false
        val overrides = workspace.toolApprovalOverrides() + (toolName to needsApproval)
        dao.upsert(
            workspace.copy(
                toolApprovals = JsonInstant.encodeToString(overrides),
                updatedAt = System.currentTimeMillis(),
            )
        )
        return true
    }

    suspend fun getAccessPolicy(id: String): WorkspaceAccessPolicy = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: return@withContext WorkspaceAccessPolicy()
        manager.readAccessPolicy(workspace.root)
    }

    suspend fun setAccessPolicy(id: String, policy: WorkspaceAccessPolicy): Boolean =
        withContext(Dispatchers.IO) {
            val workspace = dao.getById(id) ?: return@withContext false
            manager.writeAccessPolicy(workspace.root, policy)
            manager.recordAudit(
                root = workspace.root,
                action = "security_policy_update",
                detail = "readOnly=${policy.readOnly}, shell=${policy.effectiveShellEnabled}, roots=${policy.allowedWriteRoots.size}",
            )
            true
        }

    suspend fun getLocalDirectoryGrants(id: String): List<WorkspaceLocalDirectoryGrant> =
        withContext(Dispatchers.IO) {
            val workspace = dao.getById(id) ?: return@withContext emptyList()
            manager.readLocalDirectoryGrants(workspace.root)
        }

    suspend fun addLocalDirectoryGrant(
        id: String,
        treeUri: String,
        resultFlags: Int,
    ): WorkspaceLocalDirectoryGrant = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        val uri = android.net.Uri.parse(treeUri)
        val permission = safFileSystem.persistTreePermission(uri, resultFlags)
        val current = manager.readLocalDirectoryGrants(workspace.root)
        val existing = current.firstOrNull { equivalentTreeUri(it.treeUri, uri) }
        val grant = existing?.copy(
            displayName = permission.displayName,
            canRead = permission.canRead,
            canWrite = permission.canWrite,
        ) ?: WorkspaceLocalDirectoryGrant(
            id = Uuid.random().toString(),
            treeUri = uri.toString(),
            displayName = permission.displayName,
            canRead = permission.canRead,
            canWrite = permission.canWrite,
            createdAt = System.currentTimeMillis(),
        )
        val updated = current.filterNot { equivalentTreeUri(it.treeUri, uri) } + grant
        try {
            audited(workspace, "local_directory_authorize", grant.displayName, "write=${grant.canWrite}") {
                manager.writeLocalDirectoryGrants(workspace.root, updated)
            }
        } catch (error: Throwable) {
            if (existing == null) {
                val usedElsewhere = dao.getAll()
                    .filterNot { it.id == id }
                    .any { other ->
                        manager.readLocalDirectoryGrants(other.root).any { equivalentTreeUri(it.treeUri, uri) }
                    }
                if (!usedElsewhere) runCatching { safFileSystem.releaseTreePermission(uri) }
            }
            throw error
        }
        grant
    }

    suspend fun removeLocalDirectoryGrant(id: String, grantId: String): Boolean =
        withContext(Dispatchers.IO) {
            val workspace = dao.getById(id) ?: return@withContext false
            val current = manager.readLocalDirectoryGrants(workspace.root)
            val removed = current.firstOrNull { it.id == grantId } ?: return@withContext false
            audited(workspace, "local_directory_revoke", removed.displayName) {
                manager.writeLocalDirectoryGrants(workspace.root, current.filterNot { it.id == grantId })
            }
            val stillUsed = dao.getAll()
                .filterNot { it.id == id }
                .any { other ->
                manager.readLocalDirectoryGrants(other.root).any {
                    equivalentTreeUri(it.treeUri, android.net.Uri.parse(removed.treeUri))
                }
                }
            if (!stillUsed) runCatching {
                safFileSystem.releaseTreePermission(android.net.Uri.parse(removed.treeUri))
            }
            true
        }

    suspend fun listLocalFiles(
        id: String,
        grantId: String,
        path: String,
        offset: Int = 0,
    ): WorkspaceLocalFilePage = withContext(Dispatchers.IO) {
        val (workspace, grant) = requireLocalGrant(id, grantId)
        dao.updateLastAccess(id, System.currentTimeMillis())
        audited(workspace, "local_directory_list", "${grant.displayName}/$path") {
            safFileSystem.list(grant, path, offset)
        }
    }

    suspend fun readLocalTextSnapshot(
        id: String,
        grantId: String,
        path: String,
    ): WorkspaceLocalTextSnapshot = withContext(Dispatchers.IO) {
        val (workspace, grant) = requireLocalGrant(id, grantId)
        dao.updateLastAccess(id, System.currentTimeMillis())
        audited(workspace, "local_file_read", "${grant.displayName}/$path") {
            safFileSystem.readTextSnapshot(grant, path)
        }
    }

    suspend fun readLocalFileBytes(
        id: String,
        grantId: String,
        path: String,
        maxBytes: Long = WorkspaceSafFileSystem.MAX_BINARY_READ_BYTES,
    ): ByteArray = withContext(Dispatchers.IO) {
        val (workspace, grant) = requireLocalGrant(id, grantId)
        dao.updateLastAccess(id, System.currentTimeMillis())
        audited(workspace, "local_file_read", "${grant.displayName}/$path") {
            safFileSystem.readBytes(grant, path, maxBytes)
        }
    }

    suspend fun writeLocalText(
        id: String,
        grantId: String,
        path: String,
        text: String,
        overwrite: Boolean = true,
        expectedRevision: WorkspaceFileRevision? = null,
    ): WorkspaceLocalFileEntry = withContext(Dispatchers.IO) {
        val (workspace, grant) = requireLocalGrant(id, grantId)
        audited(workspace, "local_file_write", "${grant.displayName}/$path") {
            safFileSystem.writeText(grant, path, text, overwrite, expectedRevision)
        }
    }

    suspend fun createLocalDirectory(
        id: String,
        grantId: String,
        path: String,
    ): WorkspaceLocalFileEntry = withContext(Dispatchers.IO) {
        val (workspace, grant) = requireLocalGrant(id, grantId)
        audited(workspace, "local_directory_create", "${grant.displayName}/$path") {
            safFileSystem.createDirectory(grant, path)
        }
    }

    suspend fun deleteLocal(
        id: String,
        grantId: String,
        path: String,
        recursive: Boolean = false,
    ): Boolean = withContext(Dispatchers.IO) {
        val (workspace, grant) = requireLocalGrant(id, grantId)
        audited(workspace, "local_file_delete", "${grant.displayName}/$path") {
            safFileSystem.delete(grant, path, recursive)
        }
    }

    suspend fun moveLocal(
        id: String,
        grantId: String,
        source: String,
        target: String,
        overwrite: Boolean = false,
    ): WorkspaceLocalFileEntry = withContext(Dispatchers.IO) {
        val (workspace, grant) = requireLocalGrant(id, grantId)
        audited(workspace, "local_file_move", "${grant.displayName}/$source -> $target") {
            safFileSystem.move(grant, source, target, overwrite)
        }
    }

    suspend fun copyLocal(
        id: String,
        grantId: String,
        source: String,
        target: String,
        overwrite: Boolean = false,
    ): WorkspaceLocalFileEntry = withContext(Dispatchers.IO) {
        val (workspace, grant) = requireLocalGrant(id, grantId)
        audited(workspace, "local_file_copy", "${grant.displayName}/$source -> $target") {
            safFileSystem.copy(grant, source, target, overwrite)
        }
    }

    suspend fun getLocalDocumentUri(id: String, grantId: String, path: String): String =
        withContext(Dispatchers.IO) {
            val (_, grant) = requireLocalGrant(id, grantId)
            safFileSystem.documentUri(grant, path).toString()
        }

    private suspend fun requireLocalGrant(
        id: String,
        grantId: String,
    ): Pair<WorkspaceEntity, WorkspaceLocalDirectoryGrant> {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        val grant = manager.readLocalDirectoryGrants(workspace.root)
            .firstOrNull { it.id == grantId }
            ?: error("Local directory authorization not found: $grantId")
        return workspace to grant
    }

    suspend fun requireAiWriteAllowed(id: String, path: String) = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        val policy = manager.readAccessPolicy(workspace.root)
        require(policy.allowsWrite(path)) { "Workspace security policy does not allow writing to $path" }
    }

    suspend fun requireAiShellAllowed(id: String) = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        val policy = manager.readAccessPolicy(workspace.root)
        require(policy.effectiveShellEnabled) {
            "Workspace security policy does not allow shell execution"
        }
    }

    private suspend fun requireAiLocalShellAllowed(id: String) = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        val policy = manager.readAccessPolicy(workspace.root)
        require(policy.shellEnabled && !policy.readOnly) {
            "Workspace security policy does not allow local command execution"
        }
    }

    suspend fun auditHistory(id: String, limit: Int = 20): List<WorkspaceAuditEntry> =
        withContext(Dispatchers.IO) {
            val workspace = dao.getById(id) ?: return@withContext emptyList()
            manager.readAudit(workspace.root, limit)
        }

    suspend fun integrityReport(id: String): WorkspaceIntegrityReport = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: return@withContext WorkspaceIntegrityReport(false, listOf("Workspace not found"))
        manager.integrityReport(workspace.root)
    }

    suspend fun repairWorkspace(id: String): WorkspaceIntegrityReport = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: return@withContext WorkspaceIntegrityReport(false, listOf("Workspace not found"))
        val report = audited(workspace, "workspace_repair", "") {
            manager.repairWorkspace(workspace.root)
        }
        updateShellState(
            workspace,
            if (manager.hasRootfs(workspace.root)) {
                WorkspaceShellStatus.READY.name
            } else {
                WorkspaceShellStatus.DISABLED.name
            },
        )
        report
    }

    suspend fun installRootfs(
        id: String,
        url: String,
        expectedSha256: String? = null,
        onProgress: (RootfsInstallProgress) -> Unit = {},
    ): Boolean {
        val workspace = dao.getById(id) ?: return false
        updateShellState(workspace, WorkspaceShellStatus.INSTALLING.name)
        manager.recordAudit(workspace.root, action = "rootfs_install_start")
        try {
            // runInterruptible 让协程取消转成线程中断, 打断 install 内阻塞的下载/解压循环
            runInterruptible(Dispatchers.IO) {
                rootfsInstaller.install(
                    root = workspace.root,
                    url = url,
                    expectedSha256 = expectedSha256,
                    onProgress = onProgress,
                )
            }
            updateShellState(workspace, WorkspaceShellStatus.READY.name)
            manager.recordAudit(workspace.root, action = "rootfs_install", success = true)
            return true
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                restoreShellState(workspace)
            }
            throw e
        } catch (e: InterruptedException) {
            withContext(NonCancellable) {
                restoreShellState(workspace)
            }
            throw CancellationException("Rootfs install cancelled").also { it.initCause(e) }
        } catch (e: Throwable) {
            Log.e(TAG, "installRootfs failed: workspace=${workspace.id}, root=${workspace.root}, url=$url", e)
            if (manager.hasRootfs(workspace.root)) {
                restoreShellState(workspace)
            } else {
                updateShellState(workspace, WorkspaceShellStatus.BROKEN.name)
            }
            manager.recordAudit(
                workspace.root,
                action = "rootfs_install",
                success = false,
                detail = e.message.orEmpty(),
            )
            throw e
        }
    }

    suspend fun listFiles(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
    ): List<WorkspaceFileEntry> = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: return@withContext emptyList()
        dao.updateLastAccess(id, System.currentTimeMillis())
        manager.ensureWorkspace(workspace.root)
        manager.listFiles(workspace.root, path, area)
    }

    suspend fun listFilePage(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
        offset: Int = 0,
    ): WorkspaceFilePage = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: return@withContext WorkspaceFilePage(emptyList(), null, 0)
        dao.updateLastAccess(id, System.currentTimeMillis())
        manager.ensureWorkspace(workspace.root)
        manager.listFilePage(workspace.root, path, area, offset)
    }

    suspend fun readText(
        id: String,
        path: String,
    ): String = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        manager.readText(workspace.root, path)
    }

    suspend fun readTextSnapshot(id: String, path: String): WorkspaceTextSnapshot =
        withContext(Dispatchers.IO) {
            val workspace = dao.getById(id) ?: error("Workspace not found: $id")
            manager.ensureWorkspace(workspace.root)
            manager.readTextSnapshot(workspace.root, path)
        }

    suspend fun writeText(
        id: String,
        path: String,
        text: String,
        overwrite: Boolean,
        expectedRevision: WorkspaceFileRevision? = null,
    ): WorkspaceFileEntry = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        audited(workspace, "file_write", path) {
            manager.writeText(workspace.root, path, text, overwrite, expectedRevision = expectedRevision)
        }
    }

    /**
     * 读取文本用于应用内预览/编辑, 支持两个存储区.
     * FILES 区走 [WorkspaceManager.readText] (自带大小保护); LINUX 区通过 exportFile 读入内存,
     * 因此这里对 LINUX 区显式做大小限制, 避免大文件撑爆内存.
     */
    suspend fun readTextForPreview(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
    ): String = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        when (area) {
            WorkspaceStorageArea.FILES -> manager.readText(workspace.root, path)
            WorkspaceStorageArea.LINUX -> {
                val size = manager.fileSize(workspace.root, path, area)
                require(size <= MAX_PREVIEW_BYTES) {
                    "文件过大, 无法预览 (${size} bytes)"
                }
                ByteArrayOutputStream().use { out ->
                    manager.exportFile(workspace.root, path, area, out)
                    out.toString(Charsets.UTF_8.name())
                }
            }
        }
    }

    suspend fun importFile(
        id: String,
        area: WorkspaceStorageArea,
        destinationPath: String,
        fileName: String,
        inputStream: InputStream,
    ): WorkspaceFileEntry = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        audited(workspace, "file_import", "$destinationPath/$fileName") {
            manager.importFile(workspace.root, destinationPath, area, fileName, inputStream)
        }
    }

    suspend fun fileSize(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
    ): Long = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.fileSize(workspace.root, path, area)
    }

    suspend fun exportFile(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
        outputStream: OutputStream,
    ) = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.exportFile(workspace.root, path, area, outputStream)
    }

    /** 按 Rootfs 内绝对路径读取文件大小, 支持 /workspace、bind mount 与 Rootfs 内部路径 */
    suspend fun rootfsFileSize(
        id: String,
        path: String,
    ): Long = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        manager.rootfsFileSize(workspace.root, path)
    }

    /** 按 Rootfs 内绝对路径导出文件内容, 支持 /workspace、bind mount 与 Rootfs 内部路径 */
    suspend fun exportRootfsFile(
        id: String,
        path: String,
        outputStream: OutputStream,
    ) = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        manager.exportRootfsFile(workspace.root, path, outputStream)
    }

    suspend fun writeRootfsText(
        id: String,
        path: String,
        text: String,
        overwrite: Boolean,
    ): WorkspaceFileEntry = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        audited(workspace, "rootfs_file_write", path) {
            manager.writeRootfsText(workspace.root, path, text, overwrite)
        }
    }

    suspend fun deleteFile(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
        recursive: Boolean,
    ): Boolean {
        val deleted = withContext(Dispatchers.IO) {
            val workspace = dao.getById(id) ?: return@withContext false
            audited(workspace, "file_trash", path) {
                manager.moveFileToTrash(workspace.root, path, recursive, area) != null
            }
        }
        return deleted
    }

    suspend fun moveFileToTrash(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
        recursive: Boolean,
    ): WorkspaceDeletedFile? = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: return@withContext null
        audited(workspace, "file_trash", path) {
            manager.moveFileToTrash(workspace.root, path, recursive, area)
        }
    }

    suspend fun restoreDeletedFile(id: String, deletedFile: WorkspaceDeletedFile): WorkspaceFileEntry =
        withContext(Dispatchers.IO) {
            val workspace = dao.getById(id) ?: error("Workspace not found: $id")
            audited(workspace, "file_restore", deletedFile.originalPath) {
                manager.restoreDeletedFile(workspace.root, deletedFile)
            }
        }

    suspend fun storageStats(id: String): WorkspaceStorageStats = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.storageStats(workspace.root)
    }

    suspend fun filesAreaStats(id: String): Pair<Long, Int> = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: return@withContext 0L to 0
        manager.filesAreaStats(workspace.root)
    }

    suspend fun moveFile(
        id: String,
        source: String,
        target: String,
        overwrite: Boolean,
    ): WorkspaceFileEntry = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        audited(workspace, "file_move", "$source -> $target") {
            manager.moveFile(workspace.root, source, target, overwrite)
        }
    }

    suspend fun createWorkspaceDirectory(
        id: String,
        path: String,
    ): WorkspaceFileEntry = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        requireAiWriteAllowed(id, path)
        manager.ensureWorkspace(workspace.root)
        audited(workspace, "directory_create", path) {
            manager.createWorkspaceDirectory(workspace.root, path)
        }
    }

    suspend fun deleteWorkspacePath(
        id: String,
        path: String,
        recursive: Boolean = false,
    ): WorkspaceDeletedFile? = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        requireAiWriteAllowed(id, path)
        manager.ensureWorkspace(workspace.root)
        audited(workspace, "file_trash", path) {
            manager.moveWorkspacePathToTrash(workspace.root, path, recursive)
        }
    }

    suspend fun moveWorkspacePath(
        id: String,
        source: String,
        target: String,
        overwrite: Boolean = false,
    ): WorkspaceFileEntry = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        requireAiWriteAllowed(id, source)
        requireAiWriteAllowed(id, target)
        manager.ensureWorkspace(workspace.root)
        audited(workspace, "file_move", "$source -> $target") {
            manager.moveWorkspacePath(workspace.root, source, target, overwrite)
        }
    }

    suspend fun copyWorkspacePath(
        id: String,
        source: String,
        target: String,
        overwrite: Boolean = false,
    ): WorkspaceFileEntry = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        requireAiWriteAllowed(id, target)
        manager.ensureWorkspace(workspace.root)
        audited(workspace, "file_copy", "$source -> $target") {
            manager.copyWorkspacePath(workspace.root, source, target, overwrite)
        }
    }

    suspend fun executeCommand(
        id: String,
        command: String,
        cwd: String = "",
        timeoutMillis: Long = WorkspaceManager.DEFAULT_COMMAND_TIMEOUT_MS,
        stdin: ByteArray? = null,
    ): WorkspaceCommandResult {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        dao.updateLastAccess(id, System.currentTimeMillis())
        // runInterruptible 让协程取消转化为线程中断，从而打断阻塞的 Process.waitFor 并杀掉进程
        val shellLock = shellLocks.getOrPut(id) { Mutex() }
        return shellLock.withLock {
            runInterruptible(Dispatchers.IO) {
                manager.ensureWorkspace(workspace.root)
                audited(workspace, "shell_execute", cwd, "commandLength=${command.length}, timeoutMs=$timeoutMillis") {
                    manager.executeCommand(workspace.root, command, cwd, timeoutMillis, stdin)
                }
            }
        }
    }

    suspend fun executeLocalCommand(
        id: String,
        grantId: String,
        command: String,
        cwd: String = "",
        timeoutMillis: Long = WorkspaceSafFileSystem.DEFAULT_COMMAND_TIMEOUT_MS,
    ): WorkspaceCommandResult = withContext(Dispatchers.IO) {
        val (workspace, grant) = requireLocalGrant(id, grantId)
        requireAiLocalShellAllowed(id)
        require(grant.canWrite) {
            "Command mode requires write access to the authorized local directory"
        }
        dao.updateLastAccess(id, System.currentTimeMillis())
        // Serialize local command mirrors with other commands for this workspace.
        val shellLock = shellLocks.getOrPut("local:$id:$grantId") { Mutex() }
        shellLock.withLock {
            runInterruptible(Dispatchers.IO) {
                audited(
                    workspace,
                    "local_shell_execute",
                    "${grant.displayName}/$cwd",
                    "commandLength=${command.length}, timeoutMs=$timeoutMillis",
                ) {
                    safFileSystem.executeCommand(grant, command, cwd, timeoutMillis)
                }
            }
        }
    }

    suspend fun delete(id: String): Boolean {
        val workspace = dao.getById(id) ?: return false
        val localGrants = withContext(Dispatchers.IO) {
            manager.readLocalDirectoryGrants(workspace.root)
        }
        dao.deleteById(id)
        withContext(Dispatchers.IO) {
            manager.deleteWorkspace(workspace.root)
            localGrants.forEach { grant ->
                val stillUsed = dao.getAll().any { other ->
                    manager.readLocalDirectoryGrants(other.root).any {
                        equivalentTreeUri(it.treeUri, android.net.Uri.parse(grant.treeUri))
                    }
                }
                if (!stillUsed) runCatching {
                    safFileSystem.releaseTreePermission(android.net.Uri.parse(grant.treeUri))
                }
            }
        }
        cleanupAssistantReferences(id)
        return true
    }

    private suspend fun cleanupAssistantReferences(workspaceId: String) {
        settingsStore.update { settings ->
            settings.copy(
                assistants = settings.assistants.map { assistant ->
                    if (assistant.workspaceId?.toString() == workspaceId) {
                        assistant.copy(workspaceId = null)
                    } else {
                        assistant
                    }
                }
            )
        }
    }

    private fun equivalentTreeUri(stored: String, uri: android.net.Uri): Boolean {
        val parsed = runCatching { android.net.Uri.parse(stored) }.getOrNull() ?: return false
        if (parsed == uri) return true
        return parsed.normalizeScheme().toString().trimEnd('/') ==
            uri.normalizeScheme().toString().trimEnd('/')
    }

    private suspend fun restoreShellState(workspace: WorkspaceEntity) {
        updateShellState(workspace.id, workspace.shellStatus)
    }

    private inline fun <T> audited(
        workspace: WorkspaceEntity,
        action: String,
        target: String,
        detail: String = "",
        block: () -> T,
    ): T {
        return try {
            block().also {
                manager.recordAudit(workspace.root, action, target, success = true, detail = detail)
            }
        } catch (error: Throwable) {
            manager.recordAudit(
                workspace.root,
                action,
                target,
                success = false,
                detail = error.message.orEmpty(),
            )
            throw error
        }
    }

    private suspend fun updateShellState(
        workspace: WorkspaceEntity,
        shellStatus: String,
    ) = updateShellState(workspace.id, shellStatus)

    private suspend fun updateShellState(
        workspaceId: String,
        shellStatus: String,
    ) {
        dao.updateShellStatus(
            id = workspaceId,
            shellStatus = shellStatus,
            updatedAt = System.currentTimeMillis(),
        )
    }

    companion object {
        private const val TAG = "WorkspaceRepository"
        private const val MAX_PREVIEW_BYTES = 512L * 1024
    }
}
