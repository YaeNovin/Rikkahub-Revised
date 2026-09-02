package me.rerere.rikkahub.data.ai.transformers

import java.time.LocalDateTime
import me.rerere.ai.provider.Model
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.datastore.ExtensionManagementMode
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class PromptVariableResolverTest {
    @Test
    fun `resolves common aliases and workspace context`() {
        val workspaceId = "workspace-1"
        val assistant = Assistant(
            id = Uuid.parse("11111111-1111-1111-1111-111111111111"),
            name = "Ari",
            workspaceId = Uuid.parse("22222222-2222-2222-2222-222222222222"),
        )
        val values = PromptVariableResolutionContext(
            settings = Settings(
                displaySetting = DisplaySetting(userNickname = "Mina"),
                extensionManagementMode = ExtensionManagementMode.ENTERTAINMENT,
            ),
            model = Model(modelId = "gemini-3-pro", displayName = "Gemini 3 Pro"),
            assistant = assistant,
            workspace = WorkspaceEntity(
                id = workspaceId,
                name = "Novel",
                root = "/workspace/novel",
                createdAt = 1L,
                updatedAt = 1L,
            ),
            workspaceCwd = "/workspace/novel/chapters",
        ).resolvePromptVariables(LocalDateTime.of(2026, 9, 2, 15, 4))

        assertEquals("Ari", values["char_name"])
        assertEquals("Ari", values["assistant_name"])
        assertEquals("Mina", values["user_name"])
        assertEquals("Novel", values["workspace_name"])
        assertEquals("/workspace/novel/chapters", values["workspace_cwd"])
        assertEquals("entertainment", values["app_mode"])
        assertEquals("true", values["roleplay_mode"])
        assertEquals("gemini-3-pro", values["model_id"])
        assertEquals("Ari", values["assistant"])
        assertEquals("Ari", values["character_name"])
        assertEquals("Mina", values["player_name"])
        assertEquals("chapters", values["workspace_relative_cwd"])
        assertEquals("2026-09-02", values["date_iso"])
        assertEquals("15:04:00", values["time_iso"])
        assertEquals(values["cur_date"], values["date"])
        assertEquals(values["current_time"], values["time"])
        assertEquals(values["current_datetime"], values["datetime"])
        assertEquals(values["current_timestamp"], values["timestamp"])
    }

    @Test
    fun `uses root directory when conversation cwd is absent`() {
        val values = PromptVariableResolutionContext(
            settings = Settings(),
            assistant = Assistant(name = "Assistant"),
            workspace = WorkspaceEntity(
                id = "workspace-1",
                name = "Project",
                root = "/workspace/project",
                createdAt = 1L,
                updatedAt = 1L,
            ),
        ).resolvePromptVariables()

        assertEquals("/workspace/project", values["workspace_cwd"])
        assertEquals("true", values["has_workspace"])
        assertEquals("true", values["is_workspace_bound"])
        assertEquals(".", values["workspace_relative_cwd"])
        assertTrue(values["current_datetime"].orEmpty().isNotBlank())
    }
}
