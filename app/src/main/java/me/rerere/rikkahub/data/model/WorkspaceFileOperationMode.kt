package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable

/**
 * Selects how AI file operations are performed for an authorized SAF root.
 * Existing conversations default to [TOOLS] to preserve their behaviour.
 */
@Serializable
enum class WorkspaceFileOperationMode {
    TOOLS,
    COMMANDS,
}
