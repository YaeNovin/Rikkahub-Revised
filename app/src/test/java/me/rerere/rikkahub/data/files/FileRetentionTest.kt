package me.rerere.rikkahub.data.files

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FileRetentionTest {
    @Test
    fun `retention cutoff uses complete days`() {
        val now = 10L * 24 * 60 * 60 * 1_000
        assertEquals(7L * 24 * 60 * 60 * 1_000, retentionCutoffMillis(3, now))
    }

    @Test
    fun `retention days reject unsafe ranges`() {
        assertTrue(runCatching { retentionCutoffMillis(0, 0) }.isFailure)
        assertTrue(runCatching { retentionCutoffMillis(MAX_FILE_RETENTION_DAYS + 1, 0) }.isFailure)
    }

    @Test
    fun `generated image paths cannot escape their folder`() {
        val filesDir = Files.createTempDirectory("rikkahub-retention").toFile()
        try {
            val valid = resolveFileInFolder(filesDir, "images", "images/result.png")
            assertEquals(File(filesDir, "images/result.png").canonicalFile, valid)
            assertNull(resolveFileInFolder(filesDir, "images", "upload/result.png"))
            assertNull(resolveFileInFolder(filesDir, "images", "images/../secrets.txt"))
        } finally {
            filesDir.deleteRecursively()
        }
    }
}
