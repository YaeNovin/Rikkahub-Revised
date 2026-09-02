package me.rerere.rikkahub.data.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class WorkspaceSafPathTest {
    @Test
    fun `SAF paths stay relative to the authorized tree`() {
        assertEquals("notes/today.txt", normalizeSafRelativePath("/notes/today.txt/"))
        assertEquals("notes/today.txt", joinSafPath("notes", "today.txt"))
        assertEquals("", normalizeSafRelativePath("/"))
    }

    @Test
    fun `SAF paths reject traversal and invalid segments`() {
        assertThrows(IllegalArgumentException::class.java) {
            normalizeSafRelativePath("notes/../secret.txt")
        }
        assertThrows(IllegalArgumentException::class.java) {
            joinSafPath("notes", "nested/name.txt")
        }
        assertThrows(IllegalArgumentException::class.java) {
            normalizeSafRelativePath("notes\u0000.txt")
        }
    }
}
