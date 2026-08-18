package me.rerere.rikkahub.data.ai.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceToolApprovalTest {
    @Test
    fun `default approvals protect mutations and rootfs access`() {
        assertFalse(resolveWorkspaceToolApproval("workspace_read_file", emptyMap()))
        assertTrue(resolveWorkspaceToolApproval("workspace_write_file", emptyMap()))
        assertTrue(resolveWorkspaceToolApproval("workspace_edit_file", emptyMap()))
        assertTrue(resolveWorkspaceToolApproval("workspace_read_rootfs", emptyMap()))
        assertTrue(resolveWorkspaceToolApproval("workspace_write_rootfs", emptyMap()))
        assertTrue(resolveWorkspaceToolApproval("workspace_edit_rootfs", emptyMap()))
        assertTrue(resolveWorkspaceToolApproval("workspace_shell", emptyMap()))
    }

    @Test
    fun `workspace override remains available for each approval control`() {
        val overrides = mapOf(
            "workspace_write_file" to false,
            "workspace_write_rootfs" to false,
        )

        assertFalse(resolveWorkspaceToolApproval("workspace_write_file", overrides))
        assertFalse(resolveWorkspaceToolApproval("workspace_write_rootfs", overrides))
        assertTrue(resolveWorkspaceToolApproval("workspace_edit_rootfs", overrides))
    }

    @Test
    fun `tmp and system paths are classified as rootfs`() {
        assertFalse("/workspace/project/readme.md".isRootfsPath())
        assertTrue("/tmp/session.log".isRootfsPath())
        assertTrue("/etc/hosts".isRootfsPath())
        assertTrue("/skills/agent.md".isRootfsPath())
    }
}
