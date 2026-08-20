package me.rerere.rikkahub.ui.pages.imggen

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileDeletionTransactionTest {
    @Test
    fun `deletes all files after records are deleted`() = runBlocking {
        val directory = Files.createTempDirectory("gallery-delete-success").toFile()
        try {
            val first = directory.resolve("first.png").apply { writeText("first") }
            val second = directory.resolve("second.png").apply { writeText("second") }
            var recordsDeleted = false

            deleteFilesWithRollback(listOf(first, second, first)) {
                recordsDeleted = true
            }

            assertTrue(recordsDeleted)
            assertFalse(first.exists())
            assertFalse(second.exists())
            assertTrue(directory.listFiles().orEmpty().isEmpty())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `restores all files when record deletion fails`() = runBlocking {
        val directory = Files.createTempDirectory("gallery-delete-rollback").toFile()
        try {
            val first = directory.resolve("first.png").apply { writeText("first") }
            val second = directory.resolve("second.png").apply { writeText("second") }

            val failure = runCatching {
                deleteFilesWithRollback(listOf(first, second)) {
                    error("database failure")
                }
            }.exceptionOrNull()

            assertTrue(failure is IllegalStateException)
            assertEquals("first", first.readText())
            assertEquals("second", second.readText())
            assertEquals(
                listOf("first.png", "second.png"),
                directory.listFiles().orEmpty().map { it.name }.sorted(),
            )
        } finally {
            directory.deleteRecursively()
        }
    }
}
