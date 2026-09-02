package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity
data class ConversationEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo("assistant_id", defaultValue = "0950e2dc-9bd5-4801-afa3-aa887aa36b4e")
    val assistantId: String,
    @ColumnInfo("title")
    val title: String,
    @ColumnInfo("nodes")
    val nodes: String,
    @ColumnInfo("create_at")
    val createAt: Long,
    @ColumnInfo("update_at")
    val updateAt: Long,
    @ColumnInfo("suggestions", defaultValue = "[]")
    val chatSuggestions: String,
    @ColumnInfo("is_pinned", defaultValue = "0")
    val isPinned: Boolean,
    @ColumnInfo("custom_system_prompt", defaultValue = "")
    val customSystemPrompt: String = "",
    @ColumnInfo("mode_injection_ids", defaultValue = "[]")
    val modeInjectionIds: String = "[]",
    @ColumnInfo("lorebook_ids", defaultValue = "[]")
    val lorebookIds: String = "[]",
    @ColumnInfo("temporary_mode_injections", defaultValue = "{}")
    val temporaryModeInjections: String = "{}",
    @ColumnInfo("lorebook_runtime_states", defaultValue = "{}")
    val lorebookRuntimeStates: String = "{}",
    @ColumnInfo("workspace_cwd", defaultValue = "")
    val workspaceCwd: String = "",
    @ColumnInfo("workspace_file_operation_mode", defaultValue = "TOOLS")
    val workspaceFileOperationMode: String = "TOOLS",
    @ColumnInfo("folder_id", defaultValue = "")
    val folderId: String = "",
    @ColumnInfo("rolling_context_summary", defaultValue = "")
    val rollingContextSummary: String = "",
    @ColumnInfo("source_conversation_id", defaultValue = "")
    val sourceConversationId: String = "",
    @ColumnInfo("source_message_id", defaultValue = "")
    val sourceMessageId: String = "",
    @ColumnInfo("branched_at", defaultValue = "0")
    val branchedAt: Long = 0,
    @ColumnInfo("source_conversation_title", defaultValue = "")
    val sourceConversationTitle: String = "",
)
