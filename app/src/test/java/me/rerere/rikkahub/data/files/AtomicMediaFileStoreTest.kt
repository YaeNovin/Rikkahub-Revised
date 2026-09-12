package me.rerere.rikkahub.data.files

import java.io.ByteArrayInputStream
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AtomicMediaFileStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `writes to final file only after complete stream`() {
        val bytes = ByteArray(512 * 1024) { (it % 251).toByte() }
        val target = File(temporaryFolder.root, "result.mp4")

        AtomicMediaFileStore.write(
            input = ByteArrayInputStream(bytes),
            target = target,
            expectedSizeBytes = bytes.size.toLong(),
        )

        assertTrue(target.isFile)
        assertArrayEquals(bytes, target.readBytes())
        assertFalse(File(temporaryFolder.root, "result.mp4.part").exists())
    }

    @Test
    fun `empty incomplete and oversized streams leave no files`() {
        listOf(
            { AtomicMediaFileStore.write(ByteArrayInputStream(byteArrayOf()), target("empty")) },
            { AtomicMediaFileStore.write(ByteArrayInputStream(byteArrayOf(1, 2)), target("short"), 3) },
            { AtomicMediaFileStore.write(ByteArrayInputStream(ByteArray(8)), target("large"), maxBytes = 4) },
        ).forEach { write ->
            assertThrows(IllegalStateException::class.java) { write() }
        }
        assertTrue(temporaryFolder.root.listFiles().orEmpty().isEmpty())
    }

    private fun target(name: String) = File(temporaryFolder.root, "$name.mp4")

    @Test
    fun `cleanup preserves completed media and recent partial downloads`() {
        val stale = File(temporaryFolder.root, "stale.mp4.part").apply { writeText("partial"); setLastModified(1000) }
        val complete = File(temporaryFolder.root, "complete.mp4").apply { writeText("video"); setLastModified(1000) }
        val recent = File(temporaryFolder.root, "recent.mp4.part").apply { writeText("partial") }
        AtomicMediaFileStore.cleanupStalePartials(temporaryFolder.root)
        assertFalse(stale.exists())
        assertTrue(complete.exists())
        assertTrue(recent.exists())
    }
}
