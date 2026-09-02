package me.rerere.rikkahub.data.ai.transformers

import android.os.Build
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.model.WorkspaceFileOperationMode
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.WorkspaceShellStatus

/**
 * Workspace 系统提示注入转换器
 *
 * 当助手绑定了一个 shell 已就绪的 workspace 时, 在系统提示词中追加一段引导,
 * 让模型了解 workspace 环境与 workspace_* 工具的使用方式。
 */
class WorkspaceReminderTransformer(
    private val workspaceRepository: WorkspaceRepository,
) : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val workspaceId = ctx.assistant.workspaceId?.toString() ?: return messages
        val workspace = workspaceRepository.getById(workspaceId) ?: return messages
        val shellReady = workspace.shellStatus == WorkspaceShellStatus.READY.name
        val localCwd = ctx.workspaceCwd?.takeIf { it.startsWith("saf:") }
        val localCwdIsRoot = localCwd?.removePrefix("saf:")
            ?.substringAfter('/', missingDelimiterValue = "")
            ?.isBlank() == true
        val localGrants = workspaceRepository.getLocalDirectoryGrants(workspaceId)
        val localDirectoriesAvailable = localGrants.isNotEmpty()
        val localGrantId = localCwd?.removePrefix("saf:")?.substringBefore('/')
        val localGrant = localGrants.firstOrNull { it.id == localGrantId }
        val accessPolicy = workspaceRepository.getAccessPolicy(workspaceId)
        val localCommandMode =
            ctx.workspaceFileOperationMode == WorkspaceFileOperationMode.COMMANDS &&
                localCwdIsRoot &&
                localGrant?.canRead == true &&
                localGrant.canWrite &&
                accessPolicy.shellEnabled &&
                !accessPolicy.readOnly
        if (!shellReady && !localDirectoriesAvailable) return messages

        val prompt = buildWorkspacePrompt(
            workspace = workspace,
            cwd = ctx.workspaceCwd,
            includeShell = shellReady && localCwd == null,
            includeLocalDirectories = localDirectoriesAvailable,
            localCwd = localCwd,
            localCommandMode = localCommandMode,
        )

        // 追加到第一条 system 消息; 若不存在则插入一条
        val systemIndex = messages.indexOfFirst { it.role == MessageRole.SYSTEM }
        return if (systemIndex >= 0) {
            messages.toMutableList().apply {
                this[systemIndex] = this[systemIndex].appendText("\n\n$prompt")
            }
        } else {
            listOf(UIMessage.system(prompt)) + messages
        }
    }
}

private fun buildWorkspacePrompt(
    workspace: WorkspaceEntity,
    cwd: String? = null,
    includeShell: Boolean,
    includeLocalDirectories: Boolean,
    localCwd: String? = null,
    localCommandMode: Boolean = false,
): String = buildString {
    appendLine("<workspace>")
    if (includeShell) {
        appendLine("You have access to a persistent Linux workspace named \"${workspace.name}\", running in a PRoot compatibility environment. PRoot is not a security boundary; follow the workspace access policy.")
        appendLine("- The workspace files area is mounted at `/workspace`. Use it as your working directory; files written there persist across turns of this conversation.")
        appendLine("- All paths passed to Rootfs workspace tools must be absolute and inside the Rootfs (for example `/workspace/notes.md`).")
        appendLine("- Available Rootfs tools:")
        appendLine("  - `workspace_read_file`: read file contents.")
        appendLine("  - `workspace_write_file` / `workspace_edit_file`: create files, or make precise edits to existing files.")
        appendLine("  - `workspace_create_directory`: create a directory under `/workspace` (including missing parents).")
        appendLine("  - `workspace_delete`: move a file or directory under `/workspace` to workspace trash; pass `recursive=true` for directories.")
        appendLine("  - `workspace_move` / `workspace_copy`: move or recursively copy items under `/workspace`; targets are not overwritten unless `overwrite=true`.")
        appendLine("  - `workspace_shell`: run commands available in the Rootfs. Android host commands and host paths are not available inside this Shell.")
        appendLine("- For Linux commands such as `mkdir`, `rm`, `mv`, and `cp`, use `workspace_shell`; direct file tools are limited to `/workspace` and provide safer explicit operations.")
        appendLine("- Prefer `workspace_shell` for tasks that standard Unix tools handle well, and prefer `workspace_edit_file` for targeted edits over rewriting whole files.")
        appendLine("- Shell commands run non-interactively with a bounded timeout and output limit. Do not use commands that wait for a TTY (for example plain `top`, `less`, or an install prompt); use bounded/non-interactive flags such as `-c`, `--yes`, or `timeout` where supported.")
        appendLine(androidShellCompatibilityNote())
        appendLine("- The skills directory is mounted at `/skills`. Each skill is a subdirectory `/skills/<skill-name>/` containing a `SKILL.md` (with `name` and `description` frontmatter) plus any supporting files. Read a skill's `SKILL.md` before using it, and follow its instructions.")
        appendLine("- Files the user uploaded are mounted at `/upload`. Treat `/upload` as READ-ONLY: read uploaded files from `/upload/<file-name>`, but never modify, overwrite, or delete anything there. If you need to change an uploaded file, copy it into `/workspace` first and edit the copy.")
        if (!cwd.isNullOrBlank() && localCwd == null) {
            appendLine("- Current working directory: `$cwd`. Use this as the default context for file operations and shell commands.")
        }
    }
    if (includeLocalDirectories) {
        appendLine("- The user has explicitly authorized one or more Android device folders through SAF. These folders are not Shell paths and must never be addressed as `/sdcard` or through host commands.")
        if (localCommandMode) {
            appendLine("- The current conversation is in local command mode at the SAF authorization root. Use `workspace_local_shell` with the current `grant_id` for local file and directory reads or changes; do not call the individual `workspace_*_local` file tools.")
            appendLine("- `workspace_local_shell` runs Android `/system/bin/sh` against a bounded temporary mirror and synchronizes changes below this SAF root after the command completes. Use relative paths, non-interactive commands, and avoid absolute device paths.")
        } else {
            appendLine("- Use `workspace_list_local_files` first to obtain an authorized `grant_id`, then use `workspace_read_local_file`, `workspace_write_local_file`, or `workspace_edit_local_file` with paths relative to that grant.")
            appendLine("- Local mutation tools also include `workspace_create_local_directory`, `workspace_delete_local`, `workspace_move_local`, and `workspace_copy_local`; directory deletion requires `recursive=true`, and targets are not overwritten unless `overwrite=true`.")
        }
        appendLine("- Local file read/write operations remain subject to Android provider permissions and user confirmation.")
        if (localCwd != null) {
            val payload = localCwd.removePrefix("saf:")
            val grantId = payload.substringBefore('/')
            val path = payload.substringAfter('/', missingDelimiterValue = "")
            appendLine("- Current working directory is the user-authorized SAF directory with grant_id `$grantId`${path.takeIf { it.isNotBlank() }?.let { " at relative path `$it`" }.orEmpty()}.")
            if (localCommandMode) {
                appendLine("- SAF directories are not visible to Rootfs Shell; use `workspace_local_shell` with this grant_id instead of `/sdcard` paths or `workspace_shell`.")
            } else {
                appendLine("- SAF directories are not visible to Rootfs Shell; use the local SAF tools with this grant_id instead of `/sdcard` paths or `workspace_shell`.")
            }
        }
    }
    append("</workspace>")
}

private fun androidShellCompatibilityNote(): String {
    // Keep JVM unit tests usable where Android's SDK stubs throw when accessed.
    val apiLevel = runCatching { Build.VERSION.SDK_INT }.getOrDefault(Build.VERSION_CODES.Q)
    return when {
        apiLevel >= Build.VERSION_CODES.S ->
            "- Android 12+ may reclaim excessive/background child processes. Keep commands short, avoid unbounded parallel jobs, and do not assume real root privileges; PRoot root is user-space only."
        apiLevel >= Build.VERSION_CODES.Q ->
            "- Android 10-11 uses a restricted host shell and no-exec external storage. Keep executable files in the workspace Rootfs, and do not expect `/sdcard` paths or native binaries to run."
        else ->
            "- Android 9 and older still enforce app sandbox and SELinux rules. The host shell is Toybox and PRoot does not grant real root; use the workspace Rootfs for GNU/Linux tools."
    }
}

private fun UIMessage.appendText(extra: String): UIMessage {
    val updatedParts = parts.toMutableList()
    val firstTextIndex = updatedParts.indexOfFirst { it is UIMessagePart.Text }
    if (firstTextIndex >= 0) {
        val text = updatedParts[firstTextIndex] as UIMessagePart.Text
        updatedParts[firstTextIndex] = text.copy(text = text.text + extra)
    } else {
        updatedParts.add(UIMessagePart.Text(extra))
    }
    return copy(parts = updatedParts)
}
