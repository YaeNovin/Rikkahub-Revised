package me.rerere.rikkahub.data.sync

/**
 * Shared backup boundary for every backup transport.
 *
 * Rootfs is deliberately not selectable: it is installed content, can be rebuilt by the
 * workspace installer, and must never be restored from a cloud backup.
 */
enum class BackupScope(
    val defaultIncluded: Boolean,
    val userSelectable: Boolean,
) {
    DATABASE(defaultIncluded = true, userSelectable = true),
    TOKENS(defaultIncluded = false, userSelectable = true),
    ATTACHMENTS(defaultIncluded = true, userSelectable = true),
    WORKSPACE(defaultIncluded = false, userSelectable = true),
    ROOTFS(defaultIncluded = false, userSelectable = false),
}

object BackupPolicy {
    const val SETTINGS_ENTRY = "settings.json"
    const val GENERATED_IMAGES_DIRECTORY = "images"
    const val WORKSPACES_DIRECTORY = "workspaces"
    const val WORKSPACE_POLICY_RELATIVE_PATH = ".rikkahub/policy.properties"

    private val workspaceRootName = Regex("[A-Za-z0-9._-]+")

    /**
     * `FILES` was the old all-in-one option. Keep interpreting it as attachments so existing
     * persisted configurations remain usable without silently expanding their backup scope.
     */
    fun hasScope(items: Collection<Enum<*>>, scope: BackupScope): Boolean = when (scope) {
        BackupScope.ATTACHMENTS -> items.any { it.name == BackupScope.ATTACHMENTS.name || it.name == "FILES" }
        BackupScope.ROOTFS -> false
        else -> items.any { it.name == scope.name }
    }

    fun workspaceBackupAllowed(items: Collection<Enum<*>>): Boolean =
        hasScope(items, BackupScope.WORKSPACE) && hasScope(items, BackupScope.DATABASE)

    fun isValidSelection(items: Collection<Enum<*>>): Boolean =
        !hasScope(items, BackupScope.WORKSPACE) || hasScope(items, BackupScope.DATABASE)

    /** Returns a portable ZIP relative path, or null when an entry could escape its target. */
    fun safeRelativePath(entryName: String, prefix: String): String? {
        val expectedPrefix = "$prefix/"
        if (!entryName.startsWith(expectedPrefix)) return null
        val relativePath = entryName.removePrefix(expectedPrefix)
        if (relativePath.isBlank() || relativePath.contains('\\')) return null
        if (relativePath.split('/').any { it.isBlank() || it == "." || it == ".." }) return null
        return relativePath
    }

    /**
     * Accepts `workspaces/<root>/files/<path>` and the single access-policy metadata file.
     * Rootfs (`linux`), audit history, trash, and PRoot temporary files are always rejected.
     */
    fun workspaceRestoreRelativePath(entryName: String): String? {
        if (entryName.contains('\\')) return null
        val parts = entryName.split('/')
        if (parts.size < 4 || parts[0] != WORKSPACES_DIRECTORY) return null
        if (!workspaceRootName.matches(parts[1]) || parts[1] == "." || parts[1] == "..") return null
        if (parts.drop(2).joinToString("/") == WORKSPACE_POLICY_RELATIVE_PATH) {
            return "${parts[1]}/$WORKSPACE_POLICY_RELATIVE_PATH"
        }
        if (parts[2] != "files") return null
        val relativeFilePath = parts.drop(3).joinToString("/")
        if (relativeFilePath.isBlank() || relativeFilePath.split('/').any { it.isBlank() || it == "." || it == ".." }) {
            return null
        }
        return "${parts[1]}/files/$relativeFilePath"
    }
}
