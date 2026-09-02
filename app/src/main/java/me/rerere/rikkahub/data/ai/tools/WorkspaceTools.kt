package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.DiffMetadata
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.toMetadata
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.files.WorkspaceLocalFileEntry
import me.rerere.rikkahub.data.files.WorkspaceSafFileSystem
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.utils.generateUnifiedDiff
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceManager
import org.koin.java.KoinJavaComponent.getKoin
import java.io.ByteArrayOutputStream

private const val SHELL_TIMEOUT_MAX_SECONDS = 600L
private const val MAX_LOCAL_COMMAND_TIMEOUT_SECONDS = 600L
private const val MAX_READ_FILE_BYTES = 8L * 1024 * 1024

val WorkspaceToolDefaultApprovals: Map<String, Boolean> = mapOf(
    "workspace_read_file" to false,
    "workspace_write_file" to true,
    "workspace_edit_file" to true,
    "workspace_read_rootfs" to true,
    "workspace_write_rootfs" to true,
    "workspace_edit_rootfs" to true,
    "workspace_shell" to true,
    "workspace_create_directory" to true,
    "workspace_delete" to true,
    "workspace_move" to true,
    "workspace_copy" to true,
    "workspace_list_local_files" to true,
    "workspace_local_shell" to true,
    "workspace_read_local_file" to true,
    "workspace_write_local_file" to true,
    "workspace_edit_local_file" to true,
    "workspace_create_local_directory" to true,
    "workspace_delete_local" to true,
    "workspace_move_local" to true,
    "workspace_copy_local" to true,
)

fun resolveWorkspaceToolApproval(name: String, overrides: Map<String, Boolean>): Boolean =
    overrides[name] ?: WorkspaceToolDefaultApprovals[name] ?: false

suspend fun createWorkspaceLocalFileTools(
    workspaceId: String?,
    workspaceRepository: WorkspaceRepository,
): List<Tool> {
    if (workspaceId.isNullOrBlank()) return emptyList()
    if (workspaceRepository.getLocalDirectoryGrants(workspaceId).isEmpty()) return emptyList()
    val approvalOverrides = workspaceRepository.getById(workspaceId)?.toolApprovalOverrides().orEmpty()
    val accessPolicy = workspaceRepository.getAccessPolicy(workspaceId)
    fun needsApproval(name: String) = resolveWorkspaceToolApproval(name, approvalOverrides)
    return buildList {
        add(createListLocalFilesTool(workspaceId, ::needsApproval, workspaceRepository))
        add(createReadLocalFileTool(workspaceId, ::needsApproval, workspaceRepository))
        if (!accessPolicy.readOnly) {
            add(createWriteLocalFileTool(workspaceId, ::needsApproval, workspaceRepository))
            add(createEditLocalFileTool(workspaceId, ::needsApproval, workspaceRepository))
            add(createCreateLocalDirectoryTool(workspaceId, ::needsApproval, workspaceRepository))
            add(createDeleteLocalTool(workspaceId, ::needsApproval, workspaceRepository))
            add(createMoveLocalTool(workspaceId, ::needsApproval, workspaceRepository))
            add(createCopyLocalTool(workspaceId, ::needsApproval, workspaceRepository))
        }
    }
}

/**
 * Exposes the local command bridge only for a SAF authorization root. A SAF
 * subdirectory, Rootfs CWD, or an invalid grant deliberately falls back to the
 * regular local-file tools in ChatService.
 */
suspend fun createWorkspaceLocalCommandTools(
    workspaceId: String?,
    workspaceRepository: WorkspaceRepository,
    cwd: String?,
): List<Tool> {
    if (workspaceId.isNullOrBlank()) return emptyList()
    val grantId = cwd?.safRootGrantId() ?: return emptyList()
    val grant = workspaceRepository.getLocalDirectoryGrants(workspaceId)
        .firstOrNull { it.id == grantId }
        ?: return emptyList()
    if (!grant.canRead || !grant.canWrite) return emptyList()
    val accessPolicy = workspaceRepository.getAccessPolicy(workspaceId)
    if (accessPolicy.readOnly || !accessPolicy.shellEnabled) return emptyList()
    val approvalOverrides = workspaceRepository.getById(workspaceId)?.toolApprovalOverrides().orEmpty()
    fun needsApproval(name: String) = resolveWorkspaceToolApproval(name, approvalOverrides)
    return listOf(createLocalShellTool(workspaceId, grantId, ::needsApproval, workspaceRepository))
}

suspend fun createWorkspaceTools(
    workspaceId: String?,
    workspaceRepository: WorkspaceRepository,
    cwd: String? = null,
): List<Tool> {
    if (workspaceId.isNullOrBlank()) return emptyList()
    val approvalOverrides = workspaceRepository.getById(workspaceId)?.toolApprovalOverrides().orEmpty()
    val accessPolicy = workspaceRepository.getAccessPolicy(workspaceId)
    fun needsApproval(name: String) = resolveWorkspaceToolApproval(name, approvalOverrides)

    val shellCwd = cwd?.removePrefix("/workspace/")?.removePrefix("/workspace")

    return buildList {
        add(createReadFileTool(workspaceId, ::needsApproval, workspaceRepository))
        if (!accessPolicy.readOnly) {
            add(createWriteFileTool(workspaceId, ::needsApproval, workspaceRepository))
            add(createEditFileTool(workspaceId, ::needsApproval, workspaceRepository))
            add(createCreateDirectoryTool(workspaceId, ::needsApproval, workspaceRepository))
            add(createDeleteWorkspaceTool(workspaceId, ::needsApproval, workspaceRepository))
            add(createMoveWorkspaceTool(workspaceId, ::needsApproval, workspaceRepository))
            add(createCopyWorkspaceTool(workspaceId, ::needsApproval, workspaceRepository))
        }
        // A SAF CWD is a content-provider tree, not a filesystem path visible to PRoot.
        // Do not expose workspace_shell in that state; local SAF tools remain available below.
        if (accessPolicy.effectiveShellEnabled && !cwd.isLocalSafCwd()) {
            add(createShellTool(workspaceId, ::needsApproval, workspaceRepository, shellCwd))
        }
    }
}

private val IMAGE_EXTENSIONS = setOf(
    "png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "heic", "heif", "avif", "ico",
)

private fun String.isImagePath(): Boolean =
    substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS

private fun createReadFileTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_read_file",
    description = """
        Read a file using the assistant's bound workspace Rootfs. Paths must be absolute inside Rootfs.
        Use /workspace for the workspace files area.
        Supports UTF-8 text files and image files (png, jpg, jpeg, gif, webp, bmp, svg, heic, heif, avif, ico).
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(required = true)
            },
            required = listOf("path"),
        )
    },
    needsApproval = {
        needsApproval("workspace_read_file") ||
            (it.pathInRootfs("path") && needsApproval("workspace_read_rootfs"))
    },
    execute = {
        val path = it.jsonObject.absolutePath("path")
        if (path.isImagePath()) {
            workspaceRepository.readImageInRootfs(workspaceId, path)
        } else {
            val text = workspaceRepository.readTextInRootfs(workspaceId, path)
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("path", path)
                        put("text", text)
                    }.toString()
                )
            )
        }
    },
)

private fun createWriteFileTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_write_file",
    description = """
        Write a UTF-8 text file using the assistant's bound workspace Rootfs. Paths must be absolute inside Rootfs.
        Use /workspace for the workspace files area.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(required = true)
                put("text", buildJsonObject {
                    put("type", "string")
                    put("description", "UTF-8 text content to write")
                })
                put("overwrite", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Whether to overwrite an existing file. Defaults to true.")
                })
            },
            required = listOf("path", "text"),
        )
    },
    needsApproval = {
        needsApproval("workspace_write_file") ||
            (it.pathInRootfs("path") && needsApproval("workspace_write_rootfs"))
    },
    execute = {
        val params = it.jsonObject
        val path = params.absolutePath("path")
        workspaceRepository.requireAiWriteAllowed(workspaceId, path)
        val text = params.string("text") ?: error("text is required")
        val overwrite = params["overwrite"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true
        val entry = workspaceRepository.writeTextInRootfs(workspaceId, path, text, overwrite)
        listOf(UIMessagePart.Text(entry.toJson().toString()))
    },
)

private fun createEditFileTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_edit_file",
    description = """
        Edit a UTF-8 text file using the assistant's bound workspace Rootfs. Paths must be absolute inside Rootfs.
        Use /workspace for the workspace files area.
        Provide old_text and new_text. By default old_text must occur exactly once; set replace_all=true to replace every occurrence.
        If no exact match is found, whitespace-tolerant line matching is attempted automatically.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(required = true)
                put("old_text", buildJsonObject {
                    put("type", "string")
                    put("description", "Exact text to replace")
                })
                put("new_text", buildJsonObject {
                    put("type", "string")
                    put("description", "Replacement text")
                })
                put("replace_all", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Whether to replace every occurrence. Defaults to false.")
                })
            },
            required = listOf("path", "old_text", "new_text"),
        )
    },
    needsApproval = {
        needsApproval("workspace_edit_file") ||
            (it.pathInRootfs("path") && needsApproval("workspace_edit_rootfs"))
    },
    execute = {
        val params = it.jsonObject
        val path = params.absolutePath("path")
        workspaceRepository.requireAiWriteAllowed(workspaceId, path)
        val oldText = params.string("old_text") ?: error("old_text is required")
        val newText = params.string("new_text") ?: error("new_text is required")
        val replaceAll = params["replace_all"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
        require(oldText.isNotEmpty()) { "old_text must not be empty" }

        val original = workspaceRepository.readTextInRootfs(workspaceId, path)
        // 逐级尝试 exact -> line_trimmed -> block_anchor 替换器, 见 TextReplacers.kt
        val result = try {
            replaceText(original, oldText, newText, replaceAll)
        } catch (e: IllegalArgumentException) {
            error("${e.message} (path: $path)")
        }
        val entry = workspaceRepository.writeTextInRootfs(workspaceId, path, result.updated, overwrite = true)
        val diff = generateUnifiedDiff(original, result.updated, entry.path)
        listOf(
            UIMessagePart.Text(
                text = buildJsonObject {
                    put("path", entry.path)
                    put("replacements", result.replacements)
                    if (result.strategy != ExactReplacer.name) put("matchStrategy", result.strategy)
                    put("sizeBytes", entry.sizeBytes)
                    put("updatedAt", entry.updatedAt)
                }.toString(),
                // diff 存入 metadata 供 UI 渲染 diff view, 不会随工具结果发送给 API
                metadata = diff?.let { d -> DiffMetadata(diff = d).toMetadata() },
            )
        )
    },
)

private fun createShellTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
    defaultCwd: String? = null,
) = Tool(
    name = "workspace_shell",
    description = buildString {
        append("Run a shell command in the assistant's bound workspace Rootfs. The workspace files area is mounted at /workspace. ")
        append("Use cwd for a path relative to the workspace files root. ")
        if (!defaultCwd.isNullOrBlank()) {
            append("Defaults to '$defaultCwd'. ")
        }
        append("Requires Rootfs to be installed and ready. Commands are non-interactive, time-limited, and not real root; do not assume Android host paths or privileged operations are available.")
    },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("command", buildJsonObject {
                    put("type", "string")
                    put("description", "Shell command to run")
                })
                put("cwd", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        if (!defaultCwd.isNullOrBlank()) {
                            "Working directory relative to the workspace files root. Defaults to '$defaultCwd'."
                        } else {
                            "Working directory relative to the workspace files root. Defaults to root."
                        }
                    )
                })
                put("timeout", buildJsonObject {
                    put("type", "integer")
                    put(
                        "description",
                        "Command timeout in seconds. Defaults to 30, max $SHELL_TIMEOUT_MAX_SECONDS."
                    )
                })
            },
            required = listOf("command"),
        )
    },
    needsApproval = { needsApproval("workspace_shell") },
    execute = {
        val params = it.jsonObject
        val command = params.requiredString("command")
        workspaceRepository.requireAiShellAllowed(workspaceId)
        val cwd = normalizeShellCwd(params.string("cwd") ?: defaultCwd.orEmpty())
        val timeoutMillis = params.scalarString("timeout")?.toLongOrNull()
            ?.coerceIn(1L, SHELL_TIMEOUT_MAX_SECONDS)
            ?.times(1_000L)
            ?: WorkspaceManager.DEFAULT_COMMAND_TIMEOUT_MS
        val result = workspaceRepository.executeCommand(workspaceId, command, cwd, timeoutMillis)
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("exitCode", result.exitCode)
                    put("stdout", result.stdout)
                    put("stderr", result.stderr)
                    put("timedOut", result.timedOut)
                    if (result.truncated) put("truncated", true)
                }.toString()
            )
        )
    },
)

private fun createCreateDirectoryTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_create_directory",
    description = "Create a directory inside /workspace. Parent directories are created when missing.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject { putWorkspacePathProperty() },
            required = listOf("path"),
        )
    },
    needsApproval = { needsApproval("workspace_create_directory") },
    execute = {
        val path = it.jsonObject.requiredWorkspacePath("path")
        workspaceRepository.requireAiWriteAllowed(workspaceId, path)
        val entry = workspaceRepository.createWorkspaceDirectory(workspaceId, path)
        listOf(UIMessagePart.Text(entry.toJson().toString()))
    },
)

private fun createDeleteWorkspaceTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_delete",
    description = "Move a file or directory inside /workspace to the workspace trash. Directory deletion requires recursive=true.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putWorkspacePathProperty()
                put("recursive", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Required for directories. Defaults to false.")
                })
            },
            required = listOf("path"),
        )
    },
    needsApproval = { needsApproval("workspace_delete") },
    execute = {
        val params = it.jsonObject
        val path = params.requiredWorkspacePath("path")
        workspaceRepository.requireAiWriteAllowed(workspaceId, path)
        val recursive = params["recursive"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
        val deleted = workspaceRepository.deleteWorkspacePath(workspaceId, path, recursive)
        listOf(UIMessagePart.Text(buildJsonObject {
            put("path", path)
            put("deleted", deleted != null)
            deleted?.let { value -> put("trashToken", value.token) }
        }.toString()))
    },
)

private fun createMoveWorkspaceTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_move",
    description = "Move a file or directory inside /workspace. Set overwrite=true to replace an existing target.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putWorkspacePathProperty("source")
                putWorkspacePathProperty("target")
                putOverwriteProperty()
            },
            required = listOf("source", "target"),
        )
    },
    needsApproval = { needsApproval("workspace_move") },
    execute = {
        val params = it.jsonObject
        val source = params.requiredWorkspacePath("source")
        val target = params.requiredWorkspacePath("target")
        workspaceRepository.requireAiWriteAllowed(workspaceId, source)
        workspaceRepository.requireAiWriteAllowed(workspaceId, target)
        val overwrite = params.booleanOrDefault("overwrite", false)
        val entry = workspaceRepository.moveWorkspacePath(workspaceId, source, target, overwrite)
        listOf(UIMessagePart.Text(buildJsonObject {
            put("source", source)
            put("target", target)
            put("entry", entry.toJson())
        }.toString()))
    },
)

private fun createCopyWorkspaceTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_copy",
    description = "Copy a file or directory inside /workspace recursively. Direct copies are limited to 64 MB and 20,000 entries; set overwrite=true to replace an existing target.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putWorkspacePathProperty("source")
                putWorkspacePathProperty("target")
                putOverwriteProperty()
            },
            required = listOf("source", "target"),
        )
    },
    needsApproval = { needsApproval("workspace_copy") },
    execute = {
        val params = it.jsonObject
        val source = params.requiredWorkspacePath("source")
        val target = params.requiredWorkspacePath("target")
        workspaceRepository.requireAiWriteAllowed(workspaceId, target)
        val overwrite = params.booleanOrDefault("overwrite", false)
        val entry = workspaceRepository.copyWorkspacePath(workspaceId, source, target, overwrite)
        listOf(UIMessagePart.Text(buildJsonObject {
            put("source", source)
            put("target", target)
            put("entry", entry.toJson())
        }.toString()))
    },
)

private fun createLocalShellTool(
    workspaceId: String,
    grantId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_local_shell",
    description = "Run an Android shell command against the selected authorized SAF directory root. The command runs with that directory mirrored as its working tree; use relative paths only. Changes to files, directories, and file contents are synchronized back after the command finishes. This is available only when the conversation CWD is the SAF root.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("grant_id", buildJsonObject {
                    put("type", "string")
                    put("description", "Authorized SAF directory ID. Must match the current SAF root.")
                })
                put("command", buildJsonObject {
                    put("type", "string")
                    put("description", "Android sh command or script. Use relative paths inside the selected directory.")
                })
                put("cwd", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional relative working directory inside the selected SAF root. Defaults to the root.")
                })
                put("timeout", buildJsonObject {
                    put("type", "integer")
                    put("description", "Command timeout in seconds. Defaults to 30, maximum 600.")
                })
            },
            required = listOf("grant_id", "command"),
        )
    },
    needsApproval = { needsApproval("workspace_local_shell") },
    execute = {
        val params = it.jsonObject
        val requestedGrantId = params.requiredString("grant_id")
        require(requestedGrantId == grantId) {
            "grant_id must match the current SAF root authorization"
        }
        val command = params.requiredString("command")
        val cwd = params.string("cwd").orEmpty().let(::normalizeLocalCommandCwd)
        val timeoutMillis = params.scalarString("timeout")?.toLongOrNull()
            ?.coerceIn(1L, MAX_LOCAL_COMMAND_TIMEOUT_SECONDS)
            ?.times(1_000L)
            ?: WorkspaceSafFileSystem.DEFAULT_COMMAND_TIMEOUT_MS
        val result = workspaceRepository.executeLocalCommand(
            id = workspaceId,
            grantId = grantId,
            command = command,
            cwd = cwd,
            timeoutMillis = timeoutMillis,
        )
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("exitCode", result.exitCode)
                    put("stdout", result.stdout)
                    put("stderr", result.stderr)
                    put("timedOut", result.timedOut)
                    if (result.truncated) put("truncated", true)
                }.toString()
            )
        )
    },
)

private fun createListLocalFilesTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_list_local_files",
    description = "List local directories explicitly authorized by the user, or list files inside one authorized directory. Paths are relative to that directory and cannot escape it.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("grant_id", buildJsonObject {
                    put("type", "string")
                    put("description", "Authorized directory ID. Omit to list available authorized directories.")
                })
                put("path", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional path relative to the authorized directory.")
                })
                put("offset", buildJsonObject {
                    put("type", "integer")
                    put("description", "Pagination offset. Defaults to 0.")
                })
            },
        )
    },
    needsApproval = { needsApproval("workspace_list_local_files") },
    execute = {
        val params = it.jsonObject
        val grantId = params.string("grant_id")
        if (grantId.isNullOrBlank()) {
            val grants = workspaceRepository.getLocalDirectoryGrants(workspaceId)
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("directories", kotlinx.serialization.json.buildJsonArray {
                            grants.forEach { grant ->
                                add(buildJsonObject {
                                    put("grantId", grant.id)
                                    put("name", grant.displayName)
                                    put("canRead", grant.canRead)
                                    put("canWrite", grant.canWrite)
                                })
                            }
                        })
                    }.toString()
                )
            )
        } else {
            val path = params.string("path").orEmpty()
            val offset = params.scalarString("offset")?.toIntOrNull()?.coerceAtLeast(0) ?: 0
            val page = workspaceRepository.listLocalFiles(workspaceId, grantId, path, offset)
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("grantId", grantId)
                        put("path", path)
                        put("entries", kotlinx.serialization.json.buildJsonArray {
                            page.entries.forEach { add(it.toJson()) }
                        })
                        page.nextOffset?.let { put("nextOffset", it) }
                        put("totalEntries", page.totalEntries)
                    }.toString()
                )
            )
        }
    },
)

private fun createReadLocalFileTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_read_local_file",
    description = "Read a UTF-8 text file or image from a local directory explicitly authorized by the user. The path must be relative to the authorized directory.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putLocalGrantProperty()
                putLocalPathProperty()
            },
            required = listOf("grant_id", "path"),
        )
    },
    needsApproval = { needsApproval("workspace_read_local_file") },
    execute = {
        val params = it.jsonObject
        val grantId = params.requiredString("grant_id")
        val path = params.requiredRelativePath("path")
        if (path.isImagePath()) {
            val bytes = workspaceRepository.readLocalFileBytes(
                workspaceId,
                grantId,
                path,
                WorkspaceSafFileSystem.MAX_BINARY_READ_BYTES,
            )
            val uri = getKoin().get<FilesManager>().createChatFilesByByteArrays(listOf(bytes)).first()
            listOf(
                UIMessagePart.Image(url = uri.toString()),
                UIMessagePart.Text(buildJsonObject {
                    put("grantId", grantId)
                    put("path", path)
                    put("description", "Local image file read successfully")
                }.toString()),
            )
        } else {
            val snapshot = workspaceRepository.readLocalTextSnapshot(workspaceId, grantId, path)
            listOf(UIMessagePart.Text(buildJsonObject {
                put("grantId", grantId)
                put("path", path)
                put("text", snapshot.text)
            }.toString()))
        }
    },
)

private fun createWriteLocalFileTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_write_local_file",
    description = "Create or overwrite a UTF-8 text file inside a local directory explicitly authorized with write access by the user.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putLocalGrantProperty()
                putLocalPathProperty()
                put("text", buildJsonObject {
                    put("type", "string")
                    put("description", "UTF-8 text content to write")
                })
                put("overwrite", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Whether to overwrite an existing file. Defaults to true.")
                })
            },
            required = listOf("grant_id", "path", "text"),
        )
    },
    needsApproval = { needsApproval("workspace_write_local_file") },
    execute = {
        val params = it.jsonObject
        val entry = workspaceRepository.writeLocalText(
            id = workspaceId,
            grantId = params.requiredString("grant_id"),
            path = params.requiredRelativePath("path"),
            text = params.string("text") ?: error("text is required"),
            overwrite = params["overwrite"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true,
        )
        listOf(UIMessagePart.Text(entry.toJson().toString()))
    },
)

private fun createEditLocalFileTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_edit_local_file",
    description = "Make a precise text replacement in a file inside a local directory explicitly authorized with write access by the user.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putLocalGrantProperty()
                putLocalPathProperty()
                put("old_text", buildJsonObject { put("type", "string") })
                put("new_text", buildJsonObject { put("type", "string") })
                put("replace_all", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Whether to replace every occurrence. Defaults to false.")
                })
            },
            required = listOf("grant_id", "path", "old_text", "new_text"),
        )
    },
    needsApproval = { needsApproval("workspace_edit_local_file") },
    execute = {
        val params = it.jsonObject
        val grantId = params.requiredString("grant_id")
        val path = params.requiredRelativePath("path")
        val oldText = params.string("old_text") ?: error("old_text is required")
        val newText = params.string("new_text") ?: error("new_text is required")
        require(oldText.isNotEmpty()) { "old_text must not be empty" }
        val replaceAll = params["replace_all"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
        val snapshot = workspaceRepository.readLocalTextSnapshot(workspaceId, grantId, path)
        val result = replaceText(snapshot.text, oldText, newText, replaceAll)
        val entry = workspaceRepository.writeLocalText(
            id = workspaceId,
            grantId = grantId,
            path = path,
            text = result.updated,
            expectedRevision = snapshot.revision,
        )
        val diff = generateUnifiedDiff(snapshot.text, result.updated, entry.path)
        listOf(UIMessagePart.Text(
            text = buildJsonObject {
                put("grantId", grantId)
                put("path", entry.path)
                put("replacements", result.replacements)
                put("sizeBytes", entry.sizeBytes)
                put("updatedAt", entry.updatedAt)
            }.toString(),
            metadata = diff?.let { DiffMetadata(diff = it).toMetadata() },
        ))
    },
)

private fun createCreateLocalDirectoryTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_create_local_directory",
    description = "Create a directory inside a user-authorized local SAF directory. Parent directories are created when missing.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putLocalGrantProperty()
                putLocalPathProperty()
            },
            required = listOf("grant_id", "path"),
        )
    },
    needsApproval = { needsApproval("workspace_create_local_directory") },
    execute = {
        val params = it.jsonObject
        val entry = workspaceRepository.createLocalDirectory(
            workspaceId,
            params.requiredString("grant_id"),
            params.requiredRelativePath("path"),
        )
        listOf(UIMessagePart.Text(entry.toJson().toString()))
    },
)

private fun createDeleteLocalTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_delete_local",
    description = "Delete a file or directory inside a user-authorized local SAF directory. Directory deletion requires recursive=true.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putLocalGrantProperty()
                putLocalPathProperty()
                put("recursive", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Required for directories. Defaults to false.")
                })
            },
            required = listOf("grant_id", "path"),
        )
    },
    needsApproval = { needsApproval("workspace_delete_local") },
    execute = {
        val params = it.jsonObject
        val deleted = workspaceRepository.deleteLocal(
            workspaceId,
            params.requiredString("grant_id"),
            params.requiredRelativePath("path"),
            params.booleanOrDefault("recursive", false),
        )
        listOf(UIMessagePart.Text(buildJsonObject { put("deleted", deleted) }.toString()))
    },
)

private fun createMoveLocalTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_move_local",
    description = "Move a file or directory inside a user-authorized local SAF directory. Set overwrite=true to replace an existing target.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putLocalGrantProperty()
                putLocalPathProperty("source")
                putLocalPathProperty("target")
                putOverwriteProperty()
            },
            required = listOf("grant_id", "source", "target"),
        )
    },
    needsApproval = { needsApproval("workspace_move_local") },
    execute = {
        val params = it.jsonObject
        val entry = workspaceRepository.moveLocal(
            workspaceId,
            params.requiredString("grant_id"),
            params.requiredRelativePath("source"),
            params.requiredRelativePath("target"),
            params.booleanOrDefault("overwrite", false),
        )
        listOf(UIMessagePart.Text(entry.toJson().toString()))
    },
)

private fun createCopyLocalTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_copy_local",
    description = "Copy a file or directory inside a user-authorized local SAF directory recursively. Direct copies are limited to 64 MB and 20,000 entries; set overwrite=true to replace an existing target.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putLocalGrantProperty()
                putLocalPathProperty("source")
                putLocalPathProperty("target")
                putOverwriteProperty()
            },
            required = listOf("grant_id", "source", "target"),
        )
    },
    needsApproval = { needsApproval("workspace_copy_local") },
    execute = {
        val params = it.jsonObject
        val entry = workspaceRepository.copyLocal(
            workspaceId,
            params.requiredString("grant_id"),
            params.requiredRelativePath("source"),
            params.requiredRelativePath("target"),
            params.booleanOrDefault("overwrite", false),
        )
        listOf(UIMessagePart.Text(entry.toJson().toString()))
    },
)

private fun kotlinx.serialization.json.JsonObject.string(name: String): String? =
    when (val value = this[name]) {
        null -> null
        is JsonPrimitive -> {
            require(value.isString) { "$name must be a string" }
            value.contentOrNull
        }
        else -> error("$name must be a string")
    }

/** Numeric JSON fields are accepted as numbers by Gemini and as strings by some relays. */
private fun kotlinx.serialization.json.JsonObject.scalarString(name: String): String? =
    when (val value = this[name]) {
        null -> null
        is JsonPrimitive -> value.contentOrNull
        else -> error("$name must be a number or string")
    }

private fun kotlinx.serialization.json.JsonObject.requiredString(name: String): String {
    val value = this[name] ?: error("$name is required")
    require(value is JsonPrimitive && value.isString) { "$name must be a string" }
    return value.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        ?: error("$name is required")
}

private fun kotlinx.serialization.json.JsonObject.requiredRelativePath(name: String): String {
    val path = requiredString(name).replace('\\', '/').trim().trim('/')
    require(path.isNotBlank()) { "$name is required" }
    require(path.split('/').none { it.isBlank() || it == "." || it == ".." }) {
        "$name must stay inside the authorized directory"
    }
    require(!path.contains('\u0000')) { "$name contains invalid character" }
    return path
}

private fun kotlinx.serialization.json.JsonObject.requiredWorkspacePath(name: String): String {
    val path = absolutePath(name).trimEnd('/').ifBlank { "/" }
    require(path == WorkspaceManager.ROOTFS_WORKSPACE_DIR ||
        path.startsWith("${WorkspaceManager.ROOTFS_WORKSPACE_DIR}/")) {
        "$name must be inside ${WorkspaceManager.ROOTFS_WORKSPACE_DIR}"
    }
    require(path != WorkspaceManager.ROOTFS_WORKSPACE_DIR) {
        "$name must refer to an item inside ${WorkspaceManager.ROOTFS_WORKSPACE_DIR}"
    }
    require(path.trim('/').split('/').none { it.isBlank() || it == "." || it == ".." }) {
        "$name must stay inside ${WorkspaceManager.ROOTFS_WORKSPACE_DIR}"
    }
    return path
}

internal fun normalizeShellCwd(value: String): String {
    val normalized = value.replace('\\', '/').trim().trimEnd('/')
    val relative = when {
        normalized.isBlank() || normalized == WorkspaceManager.ROOTFS_WORKSPACE_DIR -> ""
        normalized.startsWith("${WorkspaceManager.ROOTFS_WORKSPACE_DIR}/") ->
            normalized.removePrefix("${WorkspaceManager.ROOTFS_WORKSPACE_DIR}/")
        normalized.startsWith('/') -> {
            require(false) { "cwd must be inside ${WorkspaceManager.ROOTFS_WORKSPACE_DIR}" }
            ""
        }
        else -> normalized
    }
    require(!relative.contains('\u0000')) { "cwd contains invalid character" }
    require(
        relative.isBlank() ||
            relative.split('/').none { it.isBlank() || it == "." || it == ".." }
    ) {
        "cwd must stay inside ${WorkspaceManager.ROOTFS_WORKSPACE_DIR}"
    }
    return relative
}

private fun kotlinx.serialization.json.JsonObject.booleanOrDefault(name: String, default: Boolean): Boolean =
    this[name]?.let { value ->
        require(value is JsonPrimitive) { "$name must be a boolean" }
        value.contentOrNull?.toBooleanStrictOrNull() ?: error("$name must be a boolean")
    } ?: default

private fun String?.isLocalSafCwd(): Boolean = this?.startsWith("saf:") == true

internal fun String.safRootGrantId(): String? {
    if (!startsWith("saf:")) return null
    val payload = removePrefix("saf:")
    val grantId = payload.substringBefore('/').trim()
    if (grantId.isBlank() || payload.substringAfter('/', missingDelimiterValue = "").isNotBlank()) return null
    return grantId
}

internal fun normalizeLocalCommandCwd(value: String): String {
    val normalized = value.replace('\\', '/').trim().trim('/')
    require(!normalized.contains('\u0000')) { "cwd contains invalid character" }
    require(
        normalized.isBlank() ||
            normalized.split('/').none { it.isBlank() || it == "." || it == ".." }
    ) { "cwd must stay inside the authorized SAF directory" }
    return normalized
}

private suspend fun WorkspaceRepository.readTextInRootfs(
    workspaceId: String,
    path: String,
): String = readRootfsBuffer(workspaceId, path).toString(Charsets.UTF_8.name())

/**
 * 按 Rootfs 内绝对路径读入内存。路径映射交给 WorkspaceManager, 由它统一处理
 * /workspace、bind mount 与 Rootfs 内部路径。
 */
private suspend fun WorkspaceRepository.readRootfsBuffer(
    workspaceId: String,
    path: String,
): ByteArrayOutputStream {
    val size = rootfsFileSize(workspaceId, path)
    require(size <= MAX_READ_FILE_BYTES) {
        "File is too large to read: $path (${size / 1024 / 1024}MB, max ${MAX_READ_FILE_BYTES / 1024 / 1024}MB). Use shell commands like head, tail, or grep to read parts of it."
    }
    return ByteArrayOutputStream(size.toInt()).also { exportRootfsFile(workspaceId, path, it) }
}

private suspend fun WorkspaceRepository.readImageInRootfs(
    workspaceId: String,
    path: String,
): List<UIMessagePart> {
    val bytes = readRootfsBuffer(workspaceId, path).toByteArray()

    val filesManager = getKoin().get<FilesManager>()
    val uris = filesManager.createChatFilesByByteArrays(listOf(bytes))
    return listOf(
        UIMessagePart.Image(url = uris.first().toString()),
        UIMessagePart.Text(
            buildJsonObject {
                put("path", path)
                put("description", "Image file read successfully")
            }.toString()
        ),
    )
}

private suspend fun WorkspaceRepository.writeTextInRootfs(
    workspaceId: String,
    path: String,
    text: String,
    overwrite: Boolean,
): WorkspaceFileEntry {
    return writeRootfsText(
        id = workspaceId,
        path = path,
        text = text,
        overwrite = overwrite,
    )
}

private fun kotlinx.serialization.json.JsonObject.absolutePath(name: String): String {
    val value = this[name] ?: error("$name is required")
    require(value is JsonPrimitive && value.isString) { "$name must be a string" }
    val path = value.contentOrNull?.replace('\\', '/')?.trim() ?: error("$name is required")
    require(path.isNotBlank()) { "$name is required" }
    require(path.startsWith("/")) { "$name must be an absolute path inside Rootfs" }
    require(!path.contains('\u0000')) { "$name contains invalid character" }
    return path
}

private fun kotlinx.serialization.json.JsonElement.pathInRootfs(name: String): Boolean =
    runCatching {
        jsonObject.absolutePath(name).isRootfsPath()
    }.getOrDefault(true)

internal fun String.isRootfsPath(): Boolean {
    val normalized = trimEnd('/').ifBlank { "/" }
    return normalized != "/workspace" && !normalized.startsWith("/workspace/")
}

private fun JsonObjectBuilder.putPathProperty(required: Boolean) {
    put("path", buildJsonObject {
        put("type", "string")
        put(
            "description",
            if (required) {
                "Absolute path inside Rootfs. Use /workspace for the workspace files area."
            } else {
                "Optional absolute path inside Rootfs. Use /workspace for the workspace files area."
            }
        )
    })
}

private fun JsonObjectBuilder.putLocalGrantProperty() {
    put("grant_id", buildJsonObject {
        put("type", "string")
        put("description", "ID returned by workspace_list_local_files for a user-authorized directory.")
    })
}

private fun JsonObjectBuilder.putWorkspacePathProperty(name: String = "path") {
    put(name, buildJsonObject {
        put("type", "string")
        put("description", "Absolute path inside /workspace. The target name is included in the path.")
    })
}

private fun JsonObjectBuilder.putOverwriteProperty() {
    put("overwrite", buildJsonObject {
        put("type", "boolean")
        put("description", "Whether to replace an existing target. Defaults to false.")
    })
}

private fun JsonObjectBuilder.putLocalPathProperty(name: String = "path") {
    put(name, buildJsonObject {
        put("type", "string")
        put("description", "Path relative to the authorized local directory. Absolute paths and parent traversal are not allowed.")
    })
}

private fun WorkspaceFileEntry.toJson() = buildJsonObject {
    put("path", path)
    put("name", name)
    put("isDirectory", isDirectory)
    put("sizeBytes", sizeBytes)
    put("updatedAt", updatedAt)
}

private fun WorkspaceLocalFileEntry.toJson() = buildJsonObject {
    put("grantId", grantId)
    put("path", path)
    put("name", name)
    put("isDirectory", isDirectory)
    put("sizeBytes", sizeBytes)
    put("updatedAt", updatedAt)
    put("mimeType", mimeType)
    put("canWrite", canWrite)
}
