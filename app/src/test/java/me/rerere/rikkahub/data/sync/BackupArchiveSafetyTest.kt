package me.rerere.rikkahub.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry

class BackupArchiveSafetyTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `entry and total limits are enforced`() {
        val budget = BackupRestoreBudget(maxEntries = 2, maxEntryBytes = 4, maxTotalBytes = 6)
        budget.beginEntry(ZipEntry("first"))
        assertEquals(4L, budget.copyCurrentEntry(ByteArrayInputStream(ByteArray(4)), ByteArrayOutputStream()))
        budget.beginEntry(ZipEntry("second"))

        assertThrows(IllegalArgumentException::class.java) {
            budget.copyCurrentEntry(ByteArrayInputStream(ByteArray(3)), ByteArrayOutputStream())
        }
    }

    @Test
    fun `restore replaces target without leaving temporary files`() {
        val directory = temp.newFolder("restore")
        val target = File(directory, "data.txt").apply { writeText("old") }
        val budget = BackupRestoreBudget(maxEntryBytes = 32, maxTotalBytes = 32)
        budget.beginEntry(ZipEntry("data.txt"))

        budget.restoreCurrentEntry(ByteArrayInputStream("new".toByteArray()), target)
        budget.commit()

        assertEquals("new", target.readText())
        assertEquals(listOf("data.txt"), directory.listFiles().orEmpty().map { it.name })
    }

    @Test
    fun `rollback restores every previously replaced file`() {
        val directory = temp.newFolder("rollback")
        val first = File(directory, "first.txt").apply { writeText("first-old") }
        val second = File(directory, "second.txt").apply { writeText("second-old") }
        val budget = BackupRestoreBudget(maxEntryBytes = 32, maxTotalBytes = 64)
        budget.beginEntry(ZipEntry("first.txt"))
        budget.restoreCurrentEntry(ByteArrayInputStream("first-new".toByteArray()), first)
        budget.beginEntry(ZipEntry("second.txt"))
        budget.restoreCurrentEntry(ByteArrayInputStream("second-new".toByteArray()), second)

        budget.rollback()

        assertEquals("first-old", first.readText())
        assertEquals("second-old", second.readText())
    }
}
