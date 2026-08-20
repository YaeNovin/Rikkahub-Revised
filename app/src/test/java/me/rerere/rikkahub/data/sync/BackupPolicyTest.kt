package me.rerere.rikkahub.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class BackupPolicyTest {
    @Test
    fun `legacy files selection remains limited to attachments`() {
        val legacyItems = listOf(LegacyBackupItem.DATABASE, LegacyBackupItem.FILES)

        assertTrue(BackupPolicy.hasScope(legacyItems, BackupScope.DATABASE))
        assertTrue(BackupPolicy.hasScope(legacyItems, BackupScope.ATTACHMENTS))
        assertFalse(BackupPolicy.hasScope(legacyItems, BackupScope.TOKENS))
        assertFalse(BackupPolicy.hasScope(legacyItems, BackupScope.WORKSPACE))
        assertFalse(BackupPolicy.hasScope(legacyItems, BackupScope.ROOTFS))
    }

    @Test
    fun `workspace scope requires database metadata`() {
        assertFalse(BackupPolicy.isValidSelection(listOf(LegacyBackupItem.WORKSPACE)))
        assertFalse(BackupPolicy.workspaceBackupAllowed(listOf(LegacyBackupItem.WORKSPACE)))

        val validItems = listOf(LegacyBackupItem.DATABASE, LegacyBackupItem.WORKSPACE)
        assertTrue(BackupPolicy.isValidSelection(validItems))
        assertTrue(BackupPolicy.workspaceBackupAllowed(validItems))
    }

    @Test
    fun `recovery drill restores only workspace files from an archive`() {
        val archive = File.createTempFile("backup-policy", ".zip")
        try {
            ZipOutputStream(FileOutputStream(archive)).use { output ->
                output.putNextEntry(ZipEntry("workspaces/demo/files/notes/todo.txt"))
                output.write("keep".toByteArray())
                output.closeEntry()
                output.putNextEntry(ZipEntry("workspaces/demo/linux/etc/passwd"))
                output.write("exclude".toByteArray())
                output.closeEntry()
                output.putNextEntry(ZipEntry("workspaces/../files/escape.txt"))
                output.write("exclude".toByteArray())
                output.closeEntry()
            }

            val restoreTargets = mutableListOf<String>()
            ZipInputStream(FileInputStream(archive)).use { input ->
                var entry: ZipEntry?
                while (input.nextEntry.also { entry = it } != null) {
                    BackupPolicy.workspaceRestoreRelativePath(entry!!.name)?.let(restoreTargets::add)
                    input.closeEntry()
                }
            }

            assertEquals(listOf("demo/files/notes/todo.txt"), restoreTargets)
        } finally {
            archive.delete()
        }
    }

    @Test
    fun `attachment and workspace archive paths reject traversal`() {
        assertEquals("nested/file.txt", BackupPolicy.safeRelativePath("upload/nested/file.txt", "upload"))
        assertEquals(
            "nested/result.png",
            BackupPolicy.safeRelativePath(
                "${BackupPolicy.GENERATED_IMAGES_DIRECTORY}/nested/result.png",
                BackupPolicy.GENERATED_IMAGES_DIRECTORY,
            ),
        )
        assertNull(BackupPolicy.safeRelativePath("upload/../settings.json", "upload"))
        assertNull(
            BackupPolicy.safeRelativePath(
                "${BackupPolicy.GENERATED_IMAGES_DIRECTORY}/../settings.json",
                BackupPolicy.GENERATED_IMAGES_DIRECTORY,
            ),
        )
        assertNull(BackupPolicy.safeRelativePath("upload\\escape.txt", "upload"))
        assertNull(BackupPolicy.workspaceRestoreRelativePath("workspaces/demo/tmp/cache.txt"))
        assertNull(BackupPolicy.workspaceRestoreRelativePath("workspaces/../files/escape.txt"))
    }

    private enum class LegacyBackupItem {
        DATABASE,
        FILES,
        WORKSPACE,
    }
}
