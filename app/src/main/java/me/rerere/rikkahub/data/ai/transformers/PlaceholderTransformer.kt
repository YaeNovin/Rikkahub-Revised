package me.rerere.rikkahub.data.ai.transformers

import android.content.Context
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.utils.applyPlaceholders
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

data class PlaceholderCtx(
    val context: Context,
    val settingsStore: SettingsStore,
    val model: me.rerere.ai.provider.Model,
    val assistant: Assistant,
    val workspace: me.rerere.rikkahub.data.db.entity.WorkspaceEntity? = null,
    val workspaceCwd: String? = null,
)

interface PlaceholderProvider {
    val placeholders: Map<String, PlaceholderInfo>
}

data class PlaceholderInfo(
    val displayName: @Composable () -> Unit,
    val resolver: (PlaceholderCtx) -> String
)

class PlaceholderBuilder {
    private val placeholders = mutableMapOf<String, PlaceholderInfo>()

    fun placeholder(
        key: String,
        displayName: @Composable () -> Unit,
        resolver: (PlaceholderCtx) -> String
    ) {
        placeholders[key] = PlaceholderInfo(displayName, resolver)
    }

    fun build(): Map<String, PlaceholderInfo> = placeholders.toMap()
}

fun buildPlaceholders(block: PlaceholderBuilder.() -> Unit): Map<String, PlaceholderInfo> {
    return PlaceholderBuilder().apply(block).build()
}

object DefaultPlaceholderProvider : PlaceholderProvider {
    override val placeholders: Map<String, PlaceholderInfo> = buildPlaceholders {
        placeholder("cur_date", { Text(stringResource(R.string.placeholder_current_date)) }) { it.resolve("cur_date") }
        placeholder("current_date", { Text(stringResource(R.string.placeholder_current_date)) }) { it.resolve("current_date") }
        placeholder("date", { Text(stringResource(R.string.placeholder_current_date)) }) { it.resolve("date") }
        placeholder("date_iso", { Text(stringResource(R.string.placeholder_date_iso)) }) { it.resolve("date_iso") }
        placeholder("current_time", { Text(stringResource(R.string.placeholder_current_time)) }) { it.resolve("current_time") }
        placeholder("time", { Text(stringResource(R.string.placeholder_current_time)) }) { it.resolve("time") }
        placeholder("time_iso", { Text(stringResource(R.string.placeholder_time_iso)) }) { it.resolve("time_iso") }
        placeholder("current_datetime", { Text(stringResource(R.string.placeholder_current_datetime)) }) { it.resolve("current_datetime") }
        placeholder("datetime", { Text(stringResource(R.string.placeholder_current_datetime)) }) { it.resolve("datetime") }
        placeholder("current_datetime_iso", { Text(stringResource(R.string.placeholder_current_datetime_iso)) }) { it.resolve("current_datetime_iso") }
        placeholder("current_timestamp", { Text(stringResource(R.string.placeholder_current_timestamp)) }) { it.resolve("current_timestamp") }
        placeholder("timestamp", { Text(stringResource(R.string.placeholder_current_timestamp)) }) { it.resolve("timestamp") }
        placeholder("locale", { Text(stringResource(R.string.placeholder_locale)) }) { it.resolve("locale") }
        placeholder("language", { Text(stringResource(R.string.placeholder_language)) }) { it.resolve("language") }
        placeholder("timezone", { Text(stringResource(R.string.placeholder_timezone)) }) { it.resolve("timezone") }
        placeholder("system_version", { Text(stringResource(R.string.placeholder_system_version)) }) { it.resolve("system_version") }
        placeholder("device_info", { Text(stringResource(R.string.placeholder_device_info)) }) { it.resolve("device_info") }
        placeholder("battery_level", { Text(stringResource(R.string.placeholder_battery_level)) }) { it.resolve("battery_level") }
        placeholder("model_id", { Text(stringResource(R.string.placeholder_model_id)) }) { it.resolve("model_id") }
        placeholder("model_name", { Text(stringResource(R.string.placeholder_model_name)) }) { it.resolve("model_name") }
        placeholder("model_type", { Text(stringResource(R.string.placeholder_model_type)) }) { it.resolve("model_type") }
        placeholder("assistant_id", { Text(stringResource(R.string.placeholder_assistant_id)) }) { it.resolve("assistant_id") }
        placeholder("assistant_name", { Text(stringResource(R.string.placeholder_assistant_name)) }) { it.resolve("assistant_name") }
        placeholder("assistant", { Text(stringResource(R.string.placeholder_assistant_alias)) }) { it.resolve("assistant") }
        placeholder("char", { Text(stringResource(R.string.placeholder_char)) }) { it.resolve("char") }
        placeholder("char_name", { Text(stringResource(R.string.placeholder_char_name)) }) { it.resolve("char_name") }
        placeholder("character_name", { Text(stringResource(R.string.placeholder_character_name)) }) { it.resolve("character_name") }
        placeholder("nickname", { Text(stringResource(R.string.placeholder_nickname)) }) { it.resolve("nickname") }
        placeholder("user", { Text(stringResource(R.string.placeholder_user)) }) { it.resolve("user") }
        placeholder("user_name", { Text(stringResource(R.string.placeholder_user_name)) }) { it.resolve("user_name") }
        placeholder("player_name", { Text(stringResource(R.string.placeholder_player_name)) }) { it.resolve("player_name") }
        placeholder("workspace_id", { Text(stringResource(R.string.placeholder_workspace_id)) }) { it.resolve("workspace_id") }
        placeholder("workspace_name", { Text(stringResource(R.string.placeholder_workspace_name)) }) { it.resolve("workspace_name") }
        placeholder("workspace_root", { Text(stringResource(R.string.placeholder_workspace_root)) }) { it.resolve("workspace_root") }
        placeholder("workspace_cwd", { Text(stringResource(R.string.placeholder_workspace_cwd)) }) { it.resolve("workspace_cwd") }
        placeholder("workspace_relative_cwd", { Text(stringResource(R.string.placeholder_workspace_relative_cwd)) }) { it.resolve("workspace_relative_cwd") }
        placeholder("has_workspace", { Text(stringResource(R.string.placeholder_has_workspace)) }) { it.resolve("has_workspace") }
        placeholder("is_workspace_bound", { Text(stringResource(R.string.placeholder_is_workspace_bound)) }) { it.resolve("is_workspace_bound") }
        placeholder("app_mode", { Text(stringResource(R.string.placeholder_app_mode)) }) { it.resolve("app_mode") }
        placeholder("roleplay_mode", { Text(stringResource(R.string.placeholder_roleplay_mode)) }) { it.resolve("roleplay_mode") }
    }
}

private fun PlaceholderCtx.resolve(key: String): String = PromptVariableResolutionContext(
    settings = settingsStore.settingsFlow.value,
    model = model,
    assistant = assistant,
    workspace = workspace,
    workspaceCwd = workspaceCwd,
    context = context,
).resolvePromptVariables()[key].orEmpty()

object PlaceholderTransformer : InputMessageTransformer, KoinComponent {
    private val defaultProvider = DefaultPlaceholderProvider

    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val settingsStore = get<SettingsStore>()
        val workspaceRepository = runCatching { get<WorkspaceRepository>() }.getOrNull()
        val workspace = assistantWorkspace(ctx.assistant, workspaceRepository)
        return messages.map {
            it.copy(
                parts = it.parts.map { part ->
                    if (part is UIMessagePart.Text) {
                        part.copy(
                            text = replacePlaceholders(
                                text = part.text,
                                ctx = ctx,
                                settingsStore = settingsStore,
                                workspace = workspace,
                            )
                        )
                    } else {
                        part
                    }
                }
            )
        }
    }

    private fun replacePlaceholders(
        text: String,
        ctx: TransformerContext,
        settingsStore: SettingsStore,
        workspace: me.rerere.rikkahub.data.db.entity.WorkspaceEntity?,
    ): String {
        val resolvedValues = PromptVariableResolutionContext(
            settings = settingsStore.settingsFlow.value,
            model = ctx.model,
            assistant = ctx.assistant,
            workspace = workspace,
            workspaceCwd = ctx.workspaceCwd,
            context = ctx.context,
        ).resolvePromptVariables()
        return text.applyPlaceholders(
            *defaultProvider.placeholders.map { (key, placeholderInfo) ->
                key to resolvedValues[key].orEmpty()
            }.toTypedArray()
        )
    }

    private suspend fun assistantWorkspace(
        assistant: Assistant,
        repository: WorkspaceRepository?,
    ) = assistant.workspaceId?.toString()?.let { id ->
        repository?.getById(id)
    }
}
