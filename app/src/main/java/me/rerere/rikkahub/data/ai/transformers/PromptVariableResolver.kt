package me.rerere.rikkahub.data.ai.transformers

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone
import me.rerere.ai.provider.Model
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.model.Assistant

/**
 * Runtime values available to every prompt editor.
 *
 * Prompt fields are evaluated in different code paths (message transforms,
 * title generation, translation, and Pebble templates). Keeping the values in
 * one resolver prevents a variable from being advertised by the editor but
 * silently left unresolved by one of those paths.
 */
data class PromptVariableResolutionContext(
    val settings: Settings,
    val model: Model? = null,
    val assistant: Assistant? = null,
    val workspace: WorkspaceEntity? = null,
    val workspaceCwd: String? = null,
    val context: Context? = null,
)

fun PromptVariableResolutionContext.resolvePromptVariables(
    now: LocalDateTime = LocalDateTime.now(),
): Map<String, String> {
    val locale = Locale.getDefault()
    val assistantName = assistant?.name.orEmpty().ifBlank { "assistant" }
    val userName = settings.displaySetting.userNickname.ifBlank { "user" }
    val workspaceRoot = workspace?.root.orEmpty()
    val workspaceCwdValue = workspaceCwd?.trim()?.takeIf { it.isNotEmpty() }
        ?: workspaceRoot
    val workspaceRelativeCwd = workspaceRoot
        .takeIf { it.isNotBlank() }
        ?.let { root ->
            workspaceCwdValue
                .removePrefix(root)
                .trimStart('/', '\\')
                .ifBlank { "." }
        }
        .orEmpty()
    val date = now.toLocalDate().format(
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale),
    )
    val time = now.toLocalTime().format(
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale),
    )
    val dateTime = now.format(
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withLocale(locale),
    )
    val timestamp = now.atZone(ZoneId.systemDefault()).toEpochSecond().toString()
    val hasWorkspace = workspace != null || !assistant?.workspaceId?.toString().isNullOrBlank()
    val mode = settings.extensionManagementMode.name.lowercase(Locale.ROOT)

    return linkedMapOf(
        // Existing names are kept as aliases so old user prompts remain valid.
        "cur_date" to date,
        "current_date" to date,
        "date" to date,
        "date_iso" to now.toLocalDate().toString(),
        "current_time" to time,
        "time" to time,
        "time_iso" to now.format(DateTimeFormatter.ofPattern("HH:mm:ss")),
        "current_datetime" to dateTime,
        "datetime" to dateTime,
        "current_datetime_iso" to now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")),
        "current_timestamp" to timestamp,
        "timestamp" to timestamp,
        "locale" to locale.displayName,
        "language" to locale.language,
        // Keep the existing display-name behaviour for the original variable.
        "timezone" to TimeZone.getDefault().displayName,
        "system_version" to "Android SDK v${Build.VERSION.SDK_INT} (${Build.VERSION.RELEASE})",
        "device_info" to "${Build.BRAND} ${Build.MODEL}".trim(),
        "battery_level" to context?.batteryLevel()?.toString().orEmpty(),
        "model_id" to model?.modelId.orEmpty(),
        "model_name" to model?.displayName.orEmpty(),
        "model_type" to model?.type?.name?.lowercase(Locale.ROOT).orEmpty(),
        "assistant_id" to assistant?.id?.toString().orEmpty(),
        "assistant_name" to assistantName,
        "assistant" to assistantName,
        "char" to assistantName,
        "char_name" to assistantName,
        "character_name" to assistantName,
        "user" to userName,
        "user_name" to userName,
        "player_name" to userName,
        "nickname" to userName,
        "workspace_id" to assistant?.workspaceId?.toString().orEmpty(),
        "workspace_name" to workspace?.name.orEmpty(),
        "workspace_root" to workspaceRoot,
        "workspace_cwd" to workspaceCwdValue,
        "workspace_relative_cwd" to workspaceRelativeCwd,
        "has_workspace" to hasWorkspace.toString(),
        "is_workspace_bound" to hasWorkspace.toString(),
        "app_mode" to mode,
        "roleplay_mode" to (mode == "entertainment").toString(),
    )
}

private fun Context.batteryLevel(): Int? {
    val manager = getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return null
    val level = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    return level.takeIf { it >= 0 }
}
