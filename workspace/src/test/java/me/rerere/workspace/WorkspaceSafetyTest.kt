package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File

class WorkspaceSafetyTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `save rejects content changed after editor snapshot`() {
        val root = temp.newFolder("files")
        val fileSystem = WorkspaceFileSystem()
        fileSystem.writeText(root, "notes.txt", "original")
        val snapshot = fileSystem.readTextSnapshot(root, "notes.txt")
        File(root, "notes.txt").writeText("changed elsewhere")

        assertThrows(IllegalArgumentException::class.java) {
            fileSystem.writeText(
                root = root,
                path = "notes.txt",
                text = "editor value",
                expectedRevision = snapshot.revision,
            )
        }
        assertEquals("changed elsewhere", File(root, "notes.txt").readText())
    }

    @Test
    fun `atomic write leaves no temporary files`() {
        val root = temp.newFolder("atomic")
        val fileSystem = WorkspaceFileSystem()
        fileSystem.writeText(root, "notes.txt", "first")
        fileSystem.writeText(root, "notes.txt", "second")

        assertEquals("second", File(root, "notes.txt").readText())
        assertTrue(root.listFiles().orEmpty().none { it.name.startsWith(".rikkahub-") })
    }

    @Test
    fun `import exceeding limit is rejected without partial file`() {
        val root = temp.newFolder("import")
        val fileSystem = WorkspaceFileSystem(WorkspaceConfig(maxImportBytes = 4))

        assertThrows(IllegalArgumentException::class.java) {
            fileSystem.importBytes(root, "large.bin", ByteArrayInputStream(ByteArray(5)))
        }
        assertFalse(File(root, "large.bin").exists())
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `large directory can be read page by page`() {
        val root = temp.newFolder("paging")
        repeat(5) { File(root, "$it.txt").writeText(it.toString()) }
        val fileSystem = WorkspaceFileSystem(WorkspaceConfig(maxListEntries = 2))

        val first = fileSystem.listPage(root)
        val second = fileSystem.listPage(root, offset = first.nextOffset!!)
        val third = fileSystem.listPage(root, offset = second.nextOffset!!)

        assertEquals(listOf("0.txt", "1.txt"), first.entries.map { it.name })
        assertEquals(listOf("2.txt", "3.txt"), second.entries.map { it.name })
        assertEquals(listOf("4.txt"), third.entries.map { it.name })
        assertEquals(null, third.nextOffset)
        assertEquals(5, first.totalEntries)
    }

    @Test
    fun `trashed file can be restored to original path`() {
        val manager = WorkspaceManager(temp.newFolder("workspaces"))
        val root = "demo"
        manager.ensureWorkspace(root)
        manager.writeText(root, "notes/todo.txt", "keep")

        val deleted = manager.moveFileToTrash(root, "notes/todo.txt")!!
        assertFalse(File(manager.filesDir(root), "notes/todo.txt").exists())

        manager.restoreDeletedFile(root, deleted)
        assertEquals("keep", File(manager.filesDir(root), "notes/todo.txt").readText())
    }

    @Test
    fun `overwrite move preserves source and replaces target`() {
        val root = temp.newFolder("move")
        val fileSystem = WorkspaceFileSystem()
        fileSystem.writeText(root, "source.txt", "new")
        fileSystem.writeText(root, "target.txt", "old")

        fileSystem.move(root, "source.txt", "target.txt", overwrite = true)

        assertFalse(File(root, "source.txt").exists())
        assertEquals("new", File(root, "target.txt").readText())
    }

    @Test
    fun `directory creation creates missing parents`() {
        val root = temp.newFolder("mkdir")
        val fileSystem = WorkspaceFileSystem()

        val entry = fileSystem.createDirectory(root, "one/two/three")

        assertTrue(entry.isDirectory)
        assertTrue(File(root, "one/two/three").isDirectory)
    }

    @Test
    fun `copy recursively preserves source and content`() {
        val root = temp.newFolder("copy")
        val fileSystem = WorkspaceFileSystem()
        fileSystem.writeText(root, "source/nested/value.txt", "kept")

        val entry = fileSystem.copy(root, "source", "duplicate")

        assertTrue(entry.isDirectory)
        assertEquals("kept", File(root, "source/nested/value.txt").readText())
        assertEquals("kept", File(root, "duplicate/nested/value.txt").readText())
    }

    @Test
    fun `copy refuses target overwrite by default`() {
        val root = temp.newFolder("copy-overwrite")
        val fileSystem = WorkspaceFileSystem()
        fileSystem.writeText(root, "source.txt", "new")
        fileSystem.writeText(root, "target.txt", "old")

        assertThrows(IllegalArgumentException::class.java) {
            fileSystem.copy(root, "source.txt", "target.txt")
        }
        assertEquals("old", File(root, "target.txt").readText())

        fileSystem.copy(root, "source.txt", "target.txt", overwrite = true)
        assertEquals("new", File(root, "target.txt").readText())
    }

    @Test
    fun `copy refuses directory target inside source`() {
        val root = temp.newFolder("copy-self")
        val fileSystem = WorkspaceFileSystem()
        fileSystem.writeText(root, "source/value.txt", "data")

        assertThrows(IllegalArgumentException::class.java) {
            fileSystem.copy(root, "source", "source/nested")
        }
        assertFalse(File(root, "source/nested").exists())
    }

    @Test
    fun `copy limit rejects operation without partial target`() {
        val root = temp.newFolder("copy-limit")
        val fileSystem = WorkspaceFileSystem(WorkspaceConfig(maxCopyBytes = 3))
        fileSystem.writeText(root, "source.txt", "four")

        assertThrows(IllegalArgumentException::class.java) {
            fileSystem.copy(root, "source.txt", "target.txt")
        }
        assertFalse(File(root, "target.txt").exists())
    }

    @Test
    fun `directory trash requires explicit recursive flag`() {
        val manager = WorkspaceManager(temp.newFolder("recursive-trash"))
        manager.ensureWorkspace("demo")
        manager.writeText("demo", "folder/value.txt", "data")

        assertThrows(IllegalArgumentException::class.java) {
            manager.moveWorkspacePathToTrash("demo", "/workspace/folder")
        }
        assertTrue(File(manager.filesDir("demo"), "folder/value.txt").isFile)
        assertTrue(manager.moveWorkspacePathToTrash("demo", "/workspace/folder", recursive = true) != null)
    }

    @Test
    fun `direct workspace operations reject rootfs paths`() {
        val manager = WorkspaceManager(temp.newFolder("rootfs-boundary"))
        manager.ensureWorkspace("demo")

        assertThrows(IllegalArgumentException::class.java) {
            manager.createWorkspaceDirectory("demo", "/etc/new-dir")
        }
    }

    @Test
    fun `workspace policy persists and limits write paths`() {
        val manager = WorkspaceManager(temp.newFolder("policy-workspaces"))
        val root = "demo"
        manager.ensureWorkspace(root)
        val policy = WorkspaceAccessPolicy(
            readOnly = false,
            shellEnabled = false,
            allowedWriteRoots = listOf("/workspace"),
        )

        manager.writeAccessPolicy(root, policy)
        val restored = manager.readAccessPolicy(root)

        assertEquals(policy, restored)
        assertTrue(restored.allowsWrite("/workspace/src/main.kt"))
        assertFalse(restored.allowsWrite("/etc/hosts"))
        assertFalse(restored.effectiveShellEnabled)
    }

    @Test
    fun `local directory grants persist without entering workspace files`() {
        val manager = WorkspaceManager(temp.newFolder("grant-workspaces"))
        val root = "demo"
        manager.ensureWorkspace(root)
        val grant = WorkspaceLocalDirectoryGrant(
            id = "grant-1",
            treeUri = "content://provider/tree/documents",
            displayName = "Documents",
            canRead = true,
            canWrite = true,
            createdAt = 1234L,
        )

        manager.writeLocalDirectoryGrants(root, listOf(grant))

        assertEquals(listOf(grant), manager.readLocalDirectoryGrants(root))
        assertTrue(manager.listFiles(root).isEmpty())
        manager.writeLocalDirectoryGrants(root, emptyList())
        assertTrue(manager.readLocalDirectoryGrants(root).isEmpty())
    }

    @Test
    fun `local directory grants reject duplicate URIs`() {
        val manager = WorkspaceManager(temp.newFolder("duplicate-grant-workspaces"))
        val first = WorkspaceLocalDirectoryGrant("one", "content://provider/tree/shared", "Shared", true, false, 1L)
        val second = first.copy(id = "two")

        assertThrows(IllegalArgumentException::class.java) {
            manager.writeLocalDirectoryGrants("demo", listOf(first, second))
        }
    }

    @Test
    fun `shared shell mount uses disposable copy`() {
        val source = temp.newFolder("skills")
        File(source, "SKILL.md").writeText("original")
        val fileToolsOnly = temp.newFolder("uploads")
        val prepared = prepareWorkspaceBindMounts(
            bindMounts = listOf(
                WorkspaceBindMount(source, "/skills", WorkspaceBindMountAccess.SHELL_READ_ONLY_COPY),
                WorkspaceBindMount(fileToolsOnly, "/upload", WorkspaceBindMountAccess.FILE_TOOLS_ONLY),
            ),
            tempDir = temp.newFolder("runtime"),
            scope = "test",
        )

        assertEquals(listOf("/skills"), prepared.mounts.map { it.target })
        File(prepared.mounts.single().source, "SKILL.md").writeText("modified copy")
        assertEquals("original", File(source, "SKILL.md").readText())
        val mirror = prepared.mounts.single().source
        prepared.close()
        assertFalse(mirror.exists())
    }

    @Test
    fun `bind mount targets reject traversal and relative paths`() {
        val source = temp.newFolder("mount-source")

        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceBindMount(source, "relative")
        }
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceBindMount(source, "/skills/../escape")
        }
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceBindMount(source, "/skills//nested")
        }
    }

    @Test
    fun `startup cleanup restores interrupted rootfs swap`() {
        val manager = WorkspaceManager(temp.newFolder("recovery-workspaces"))
        val root = "demo"
        manager.ensureWorkspace(root)
        manager.linuxDir(root).deleteRecursively()
        val backupShell = File(manager.tempDir(root), "rootfs-backup/bin/sh")
        backupShell.parentFile?.mkdirs()
        backupShell.writeText("#!/bin/sh\n")

        manager.cleanupAllTempDirs()

        assertTrue(File(manager.linuxDir(root), "bin/sh").isFile)
        assertFalse(manager.tempDir(root).exists())
    }

    @Test
    fun `repair removes incomplete staging without touching files`() {
        val manager = WorkspaceManager(temp.newFolder("repair-workspaces"))
        val root = "demo"
        manager.ensureWorkspace(root)
        manager.writeText(root, "important.txt", "keep")
        File(manager.tempDir(root), "rootfs-staging/partial").apply {
            parentFile?.mkdirs()
            writeText("partial")
        }

        assertFalse(manager.integrityReport(root).healthy)
        assertTrue(manager.repairWorkspace(root).healthy)
        assertEquals("keep", File(manager.filesDir(root), "important.txt").readText())
    }

    @Test
    fun `audit history records result without command contents`() {
        val manager = WorkspaceManager(temp.newFolder("audit-workspaces"))
        val root = "demo"
        manager.ensureWorkspace(root)
        manager.recordAudit(root, "shell_execute", target = "src", detail = "commandLength=12")

        val entry = manager.readAudit(root).single()
        assertEquals("shell_execute", entry.action)
        assertEquals("src", entry.target)
        assertEquals("commandLength=12", entry.detail)
        assertTrue(entry.success)
    }
}
