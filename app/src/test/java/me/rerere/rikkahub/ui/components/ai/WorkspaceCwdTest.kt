package me.rerere.rikkahub.ui.components.ai

import me.rerere.rikkahub.data.ai.tools.normalizeShellCwd
import me.rerere.workspace.WorkspaceLocalDirectoryGrant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class WorkspaceCwdTest {
    @Test
    fun `local cwd round trips grant and relative path`() {
        val encoded = encodeLocalWorkspaceCwd("grant-1", "nested/project")

        assertEquals("saf:grant-1/nested/project", encoded)
        assertEquals(
            LocalWorkspaceCwd("grant-1", "nested/project"),
            parseLocalWorkspaceCwd(encoded),
        )
    }

    @Test
    fun `local cwd root omits trailing slash`() {
        val encoded = encodeLocalWorkspaceCwd("grant-1", "/")

        assertEquals("saf:grant-1", encoded)
        assertEquals(LocalWorkspaceCwd("grant-1", ""), parseLocalWorkspaceCwd(encoded))
    }

    @Test
    fun `invalid local cwd is ignored`() {
        assertNull(parseLocalWorkspaceCwd(null))
        assertNull(parseLocalWorkspaceCwd("saf:"))
        assertNull(parseLocalWorkspaceCwd("/workspace/project"))
        assertNull(parseLocalWorkspaceCwd("saf:grant-1/../outside"))
    }

    @Test
    fun `local cwd displays grant name instead of internal encoding`() {
        val grants = listOf(
            WorkspaceLocalDirectoryGrant(
                id = "grant-1",
                treeUri = "content://provider/tree/root",
                displayName = "Documents",
                canRead = true,
                canWrite = true,
                createdAt = 1L,
            )
        )

        assertEquals(
            "Documents/project",
            formatWorkspaceCwd("saf:grant-1/project", grants),
        )
    }

    @Test
    fun `shell cwd accepts workspace absolute and relative forms`() {
        assertEquals("", normalizeShellCwd("/workspace/"))
        assertEquals("src/main", normalizeShellCwd("/workspace/src/main"))
        assertEquals("src/main", normalizeShellCwd("src\\main"))
    }

    @Test
    fun `shell cwd rejects paths outside workspace`() {
        assertThrows(IllegalArgumentException::class.java) {
            normalizeShellCwd("/etc")
        }
        assertThrows(IllegalArgumentException::class.java) {
            normalizeShellCwd("../outside")
        }
    }

    @Test
    fun `local cwd rejects traversal`() {
        assertThrows(IllegalArgumentException::class.java) {
            encodeLocalWorkspaceCwd("grant-1", "nested/../outside")
        }
    }
}
