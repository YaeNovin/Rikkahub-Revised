package me.rerere.rikkahub.data.ai.transformers

import me.rerere.rikkahub.R

/**
 * The variables exposed by prompt editors. Runtime expansion remains owned by
 * the corresponding transformer; this catalog only describes the contract
 * shown to users and keeps the editor and settings pages in sync.
 */
enum class PromptVariableScope {
    ASSISTANT_SYSTEM,
    MESSAGE_TEMPLATE,
    TITLE_PROMPT,
    SUGGESTION_PROMPT,
    COMPRESS_PROMPT,
    TRANSLATION_PROMPT,
    QUICK_MESSAGE,
}

enum class PromptVariableSyntax {
    DOUBLE_BRACES,
    SINGLE_BRACES,
}

data class PromptVariableDescriptor(
    val key: String,
    val labelRes: Int,
    val descriptionRes: Int,
    val scopes: Set<PromptVariableScope>,
    val syntax: PromptVariableSyntax = PromptVariableSyntax.DOUBLE_BRACES,
    val aliasOf: String? = null,
) {
    val token: String
        get() = when (syntax) {
            PromptVariableSyntax.DOUBLE_BRACES -> "{{$key}}"
            PromptVariableSyntax.SINGLE_BRACES -> "{$key}"
        }
}

object PromptVariableCatalog {
    val all: List<PromptVariableDescriptor> = listOf(
        PromptVariableDescriptor(
            key = "cur_date",
            labelRes = R.string.placeholder_current_date,
            descriptionRes = R.string.prompt_variable_current_date_desc,
            scopes = setOf(
                PromptVariableScope.ASSISTANT_SYSTEM,
                PromptVariableScope.TITLE_PROMPT,
                PromptVariableScope.SUGGESTION_PROMPT,
                PromptVariableScope.COMPRESS_PROMPT,
                PromptVariableScope.TRANSLATION_PROMPT,
            ),
        ),
        PromptVariableDescriptor(
            key = "model_id",
            labelRes = R.string.placeholder_model_id,
            descriptionRes = R.string.prompt_variable_model_id_desc,
            scopes = setOf(
                PromptVariableScope.ASSISTANT_SYSTEM,
                PromptVariableScope.TITLE_PROMPT,
                PromptVariableScope.SUGGESTION_PROMPT,
                PromptVariableScope.COMPRESS_PROMPT,
                PromptVariableScope.TRANSLATION_PROMPT,
            ),
        ),
        PromptVariableDescriptor(
            key = "model_name",
            labelRes = R.string.placeholder_model_name,
            descriptionRes = R.string.prompt_variable_model_name_desc,
            scopes = setOf(
                PromptVariableScope.ASSISTANT_SYSTEM,
                PromptVariableScope.TITLE_PROMPT,
                PromptVariableScope.SUGGESTION_PROMPT,
                PromptVariableScope.COMPRESS_PROMPT,
                PromptVariableScope.TRANSLATION_PROMPT,
            ),
        ),
        PromptVariableDescriptor(
            key = "locale",
            labelRes = R.string.placeholder_locale,
            descriptionRes = R.string.prompt_variable_locale_desc,
            scopes = setOf(
                PromptVariableScope.ASSISTANT_SYSTEM,
                PromptVariableScope.TITLE_PROMPT,
                PromptVariableScope.SUGGESTION_PROMPT,
                PromptVariableScope.COMPRESS_PROMPT,
                PromptVariableScope.TRANSLATION_PROMPT,
            ),
            syntax = PromptVariableSyntax.SINGLE_BRACES,
        ),
        PromptVariableDescriptor(
            key = "timezone",
            labelRes = R.string.placeholder_timezone,
            descriptionRes = R.string.prompt_variable_timezone_desc,
            scopes = setOf(PromptVariableScope.ASSISTANT_SYSTEM),
        ),
        PromptVariableDescriptor(
            key = "system_version",
            labelRes = R.string.placeholder_system_version,
            descriptionRes = R.string.prompt_variable_system_version_desc,
            scopes = setOf(PromptVariableScope.ASSISTANT_SYSTEM),
        ),
        PromptVariableDescriptor(
            key = "device_info",
            labelRes = R.string.placeholder_device_info,
            descriptionRes = R.string.prompt_variable_device_info_desc,
            scopes = setOf(PromptVariableScope.ASSISTANT_SYSTEM),
        ),
        PromptVariableDescriptor(
            key = "battery_level",
            labelRes = R.string.placeholder_battery_level,
            descriptionRes = R.string.prompt_variable_battery_level_desc,
            scopes = setOf(PromptVariableScope.ASSISTANT_SYSTEM),
        ),
        PromptVariableDescriptor(
            key = "nickname",
            labelRes = R.string.placeholder_nickname,
            descriptionRes = R.string.prompt_variable_nickname_desc,
            scopes = setOf(PromptVariableScope.ASSISTANT_SYSTEM),
            aliasOf = "user",
        ),
        PromptVariableDescriptor(
            key = "char",
            labelRes = R.string.placeholder_char,
            descriptionRes = R.string.prompt_variable_char_desc,
            scopes = setOf(PromptVariableScope.ASSISTANT_SYSTEM),
            aliasOf = "assistant_name",
        ),
        PromptVariableDescriptor(
            key = "user",
            labelRes = R.string.placeholder_user,
            descriptionRes = R.string.prompt_variable_user_desc,
            scopes = setOf(
                PromptVariableScope.ASSISTANT_SYSTEM,
                PromptVariableScope.TITLE_PROMPT,
                PromptVariableScope.SUGGESTION_PROMPT,
                PromptVariableScope.COMPRESS_PROMPT,
                PromptVariableScope.TRANSLATION_PROMPT,
            ),
        ),
        PromptVariableDescriptor(
            key = "current_time",
            labelRes = R.string.placeholder_current_time,
            descriptionRes = R.string.prompt_variable_current_time_desc,
            scopes = setOf(
                PromptVariableScope.ASSISTANT_SYSTEM,
                PromptVariableScope.TITLE_PROMPT,
                PromptVariableScope.SUGGESTION_PROMPT,
                PromptVariableScope.COMPRESS_PROMPT,
                PromptVariableScope.TRANSLATION_PROMPT,
            ),
        ),
        PromptVariableDescriptor(
            key = "current_datetime",
            labelRes = R.string.placeholder_current_datetime,
            descriptionRes = R.string.prompt_variable_current_datetime_desc,
            scopes = setOf(
                PromptVariableScope.ASSISTANT_SYSTEM,
                PromptVariableScope.TITLE_PROMPT,
                PromptVariableScope.SUGGESTION_PROMPT,
                PromptVariableScope.COMPRESS_PROMPT,
                PromptVariableScope.TRANSLATION_PROMPT,
            ),
        ),
        PromptVariableDescriptor(
            key = "date_iso",
            labelRes = R.string.placeholder_date_iso,
            descriptionRes = R.string.prompt_variable_date_iso_desc,
            scopes = setOf(
                PromptVariableScope.ASSISTANT_SYSTEM,
                PromptVariableScope.TITLE_PROMPT,
                PromptVariableScope.SUGGESTION_PROMPT,
                PromptVariableScope.COMPRESS_PROMPT,
                PromptVariableScope.TRANSLATION_PROMPT,
            ),
        ),
        PromptVariableDescriptor(
            key = "time_iso",
            labelRes = R.string.placeholder_time_iso,
            descriptionRes = R.string.prompt_variable_time_iso_desc,
            scopes = setOf(
                PromptVariableScope.ASSISTANT_SYSTEM,
                PromptVariableScope.TITLE_PROMPT,
                PromptVariableScope.SUGGESTION_PROMPT,
                PromptVariableScope.COMPRESS_PROMPT,
                PromptVariableScope.TRANSLATION_PROMPT,
            ),
        ),
        PromptVariableDescriptor(
            key = "current_datetime_iso",
            labelRes = R.string.placeholder_current_datetime_iso,
            descriptionRes = R.string.prompt_variable_current_datetime_iso_desc,
            scopes = setOf(
                PromptVariableScope.ASSISTANT_SYSTEM,
                PromptVariableScope.TITLE_PROMPT,
                PromptVariableScope.SUGGESTION_PROMPT,
                PromptVariableScope.COMPRESS_PROMPT,
                PromptVariableScope.TRANSLATION_PROMPT,
            ),
        ),
        PromptVariableDescriptor(
            key = "current_timestamp",
            labelRes = R.string.placeholder_current_timestamp,
            descriptionRes = R.string.prompt_variable_current_timestamp_desc,
            scopes = setOf(PromptVariableScope.ASSISTANT_SYSTEM),
        ),
        PromptVariableDescriptor(
            key = "language",
            labelRes = R.string.placeholder_language,
            descriptionRes = R.string.prompt_variable_language_desc,
            scopes = setOf(
                PromptVariableScope.ASSISTANT_SYSTEM,
                PromptVariableScope.TITLE_PROMPT,
                PromptVariableScope.SUGGESTION_PROMPT,
                PromptVariableScope.COMPRESS_PROMPT,
                PromptVariableScope.TRANSLATION_PROMPT,
            ),
        ),
        PromptVariableDescriptor(
            key = "model_type",
            labelRes = R.string.placeholder_model_type,
            descriptionRes = R.string.prompt_variable_model_type_desc,
            scopes = setOf(
                PromptVariableScope.ASSISTANT_SYSTEM,
                PromptVariableScope.TITLE_PROMPT,
                PromptVariableScope.SUGGESTION_PROMPT,
                PromptVariableScope.COMPRESS_PROMPT,
                PromptVariableScope.TRANSLATION_PROMPT,
            ),
        ),
        PromptVariableDescriptor(
            key = "assistant_id",
            labelRes = R.string.placeholder_assistant_id,
            descriptionRes = R.string.prompt_variable_assistant_id_desc,
            scopes = setOf(PromptVariableScope.ASSISTANT_SYSTEM),
        ),
        PromptVariableDescriptor(
            key = "assistant_name",
            labelRes = R.string.placeholder_assistant_name,
            descriptionRes = R.string.prompt_variable_assistant_name_desc,
            scopes = setOf(
                PromptVariableScope.ASSISTANT_SYSTEM,
                PromptVariableScope.TITLE_PROMPT,
                PromptVariableScope.SUGGESTION_PROMPT,
                PromptVariableScope.COMPRESS_PROMPT,
                PromptVariableScope.TRANSLATION_PROMPT,
                PromptVariableScope.QUICK_MESSAGE,
            ),
        ),
        PromptVariableDescriptor(
            key = "assistant",
            labelRes = R.string.placeholder_assistant_alias,
            descriptionRes = R.string.prompt_variable_assistant_alias_desc,
            scopes = setOf(
                PromptVariableScope.ASSISTANT_SYSTEM,
                PromptVariableScope.TITLE_PROMPT,
                PromptVariableScope.SUGGESTION_PROMPT,
                PromptVariableScope.COMPRESS_PROMPT,
                PromptVariableScope.TRANSLATION_PROMPT,
                PromptVariableScope.QUICK_MESSAGE,
            ),
            aliasOf = "assistant_name",
        ),
        PromptVariableDescriptor(
            key = "char_name",
            labelRes = R.string.placeholder_char_name,
            descriptionRes = R.string.prompt_variable_char_name_desc,
            scopes = setOf(
                PromptVariableScope.ASSISTANT_SYSTEM,
                PromptVariableScope.TITLE_PROMPT,
                PromptVariableScope.SUGGESTION_PROMPT,
                PromptVariableScope.COMPRESS_PROMPT,
                PromptVariableScope.TRANSLATION_PROMPT,
                PromptVariableScope.QUICK_MESSAGE,
            ),
            aliasOf = "assistant_name",
        ),
        PromptVariableDescriptor(
            key = "character_name",
            labelRes = R.string.placeholder_character_name,
            descriptionRes = R.string.prompt_variable_character_name_desc,
            scopes = setOf(
                PromptVariableScope.ASSISTANT_SYSTEM,
                PromptVariableScope.TITLE_PROMPT,
                PromptVariableScope.SUGGESTION_PROMPT,
                PromptVariableScope.COMPRESS_PROMPT,
                PromptVariableScope.TRANSLATION_PROMPT,
                PromptVariableScope.QUICK_MESSAGE,
            ),
            aliasOf = "assistant_name",
        ),
        PromptVariableDescriptor(
            key = "user_name",
            labelRes = R.string.placeholder_user_name,
            descriptionRes = R.string.prompt_variable_user_name_desc,
            scopes = setOf(
                PromptVariableScope.ASSISTANT_SYSTEM,
                PromptVariableScope.TITLE_PROMPT,
                PromptVariableScope.SUGGESTION_PROMPT,
                PromptVariableScope.COMPRESS_PROMPT,
                PromptVariableScope.TRANSLATION_PROMPT,
                PromptVariableScope.QUICK_MESSAGE,
            ),
            aliasOf = "user",
        ),
        PromptVariableDescriptor(
            key = "player_name",
            labelRes = R.string.placeholder_player_name,
            descriptionRes = R.string.prompt_variable_player_name_desc,
            scopes = setOf(
                PromptVariableScope.ASSISTANT_SYSTEM,
                PromptVariableScope.TITLE_PROMPT,
                PromptVariableScope.SUGGESTION_PROMPT,
                PromptVariableScope.COMPRESS_PROMPT,
                PromptVariableScope.TRANSLATION_PROMPT,
                PromptVariableScope.QUICK_MESSAGE,
            ),
            aliasOf = "user",
        ),
        PromptVariableDescriptor(
            key = "workspace_id",
            labelRes = R.string.placeholder_workspace_id,
            descriptionRes = R.string.prompt_variable_workspace_id_desc,
            scopes = setOf(PromptVariableScope.ASSISTANT_SYSTEM),
        ),
        PromptVariableDescriptor(
            key = "workspace_name",
            labelRes = R.string.placeholder_workspace_name,
            descriptionRes = R.string.prompt_variable_workspace_name_desc,
            scopes = setOf(
                PromptVariableScope.ASSISTANT_SYSTEM,
                PromptVariableScope.TITLE_PROMPT,
                PromptVariableScope.SUGGESTION_PROMPT,
                PromptVariableScope.COMPRESS_PROMPT,
                PromptVariableScope.TRANSLATION_PROMPT,
            ),
        ),
        PromptVariableDescriptor(
            key = "workspace_root",
            labelRes = R.string.placeholder_workspace_root,
            descriptionRes = R.string.prompt_variable_workspace_root_desc,
            scopes = setOf(PromptVariableScope.ASSISTANT_SYSTEM),
        ),
        PromptVariableDescriptor(
            key = "workspace_cwd",
            labelRes = R.string.placeholder_workspace_cwd,
            descriptionRes = R.string.prompt_variable_workspace_cwd_desc,
            scopes = setOf(
                PromptVariableScope.ASSISTANT_SYSTEM,
                PromptVariableScope.TITLE_PROMPT,
                PromptVariableScope.SUGGESTION_PROMPT,
                PromptVariableScope.COMPRESS_PROMPT,
            ),
        ),
        PromptVariableDescriptor(
            key = "workspace_relative_cwd",
            labelRes = R.string.placeholder_workspace_relative_cwd,
            descriptionRes = R.string.prompt_variable_workspace_relative_cwd_desc,
            scopes = setOf(
                PromptVariableScope.ASSISTANT_SYSTEM,
                PromptVariableScope.TITLE_PROMPT,
                PromptVariableScope.SUGGESTION_PROMPT,
                PromptVariableScope.COMPRESS_PROMPT,
            ),
        ),
        PromptVariableDescriptor(
            key = "has_workspace",
            labelRes = R.string.placeholder_has_workspace,
            descriptionRes = R.string.prompt_variable_has_workspace_desc,
            scopes = setOf(PromptVariableScope.ASSISTANT_SYSTEM),
        ),
        PromptVariableDescriptor(
            key = "is_workspace_bound",
            labelRes = R.string.placeholder_is_workspace_bound,
            descriptionRes = R.string.prompt_variable_is_workspace_bound_desc,
            scopes = setOf(PromptVariableScope.ASSISTANT_SYSTEM),
            aliasOf = "has_workspace",
        ),
        PromptVariableDescriptor(
            key = "app_mode",
            labelRes = R.string.placeholder_app_mode,
            descriptionRes = R.string.prompt_variable_app_mode_desc,
            scopes = setOf(PromptVariableScope.ASSISTANT_SYSTEM),
        ),
        PromptVariableDescriptor(
            key = "roleplay_mode",
            labelRes = R.string.placeholder_roleplay_mode,
            descriptionRes = R.string.prompt_variable_roleplay_mode_desc,
            scopes = setOf(PromptVariableScope.ASSISTANT_SYSTEM),
        ),
        PromptVariableDescriptor(
            key = "role",
            labelRes = R.string.assistant_page_template_variable_role,
            descriptionRes = R.string.prompt_variable_role_desc,
            scopes = setOf(PromptVariableScope.MESSAGE_TEMPLATE),
        ),
        PromptVariableDescriptor(
            key = "message",
            labelRes = R.string.assistant_page_template_variable_message,
            descriptionRes = R.string.prompt_variable_message_desc,
            scopes = setOf(PromptVariableScope.MESSAGE_TEMPLATE),
        ),
        PromptVariableDescriptor(
            key = "time",
            labelRes = R.string.assistant_page_template_variable_time,
            descriptionRes = R.string.prompt_variable_time_desc,
            scopes = setOf(PromptVariableScope.MESSAGE_TEMPLATE),
        ),
        PromptVariableDescriptor(
            key = "date",
            labelRes = R.string.assistant_page_template_variable_date,
            descriptionRes = R.string.prompt_variable_date_desc,
            scopes = setOf(PromptVariableScope.MESSAGE_TEMPLATE),
        ),
        PromptVariableDescriptor(
            key = "content",
            labelRes = R.string.prompt_variable_content,
            descriptionRes = R.string.prompt_variable_content_desc,
            scopes = setOf(
                PromptVariableScope.TITLE_PROMPT,
                PromptVariableScope.SUGGESTION_PROMPT,
                PromptVariableScope.COMPRESS_PROMPT,
            ),
            syntax = PromptVariableSyntax.SINGLE_BRACES,
        ),
        PromptVariableDescriptor(
            key = "target_tokens",
            labelRes = R.string.prompt_variable_target_tokens,
            descriptionRes = R.string.prompt_variable_target_tokens_desc,
            scopes = setOf(PromptVariableScope.COMPRESS_PROMPT),
            syntax = PromptVariableSyntax.SINGLE_BRACES,
        ),
        PromptVariableDescriptor(
            key = "additional_context",
            labelRes = R.string.prompt_variable_additional_context,
            descriptionRes = R.string.prompt_variable_additional_context_desc,
            scopes = setOf(PromptVariableScope.COMPRESS_PROMPT),
            syntax = PromptVariableSyntax.SINGLE_BRACES,
        ),
        PromptVariableDescriptor(
            key = "source_text",
            labelRes = R.string.prompt_variable_source_text,
            descriptionRes = R.string.prompt_variable_source_text_desc,
            scopes = setOf(PromptVariableScope.TRANSLATION_PROMPT),
            syntax = PromptVariableSyntax.SINGLE_BRACES,
        ),
        PromptVariableDescriptor(
            key = "target_lang",
            labelRes = R.string.prompt_variable_target_lang,
            descriptionRes = R.string.prompt_variable_target_lang_desc,
            scopes = setOf(PromptVariableScope.TRANSLATION_PROMPT),
            syntax = PromptVariableSyntax.SINGLE_BRACES,
        ),
        PromptVariableDescriptor(
            key = "character",
            labelRes = R.string.prompt_variable_character,
            descriptionRes = R.string.prompt_variable_character_desc,
            scopes = setOf(PromptVariableScope.QUICK_MESSAGE),
        ),
        PromptVariableDescriptor(
            key = "location",
            labelRes = R.string.prompt_variable_location,
            descriptionRes = R.string.prompt_variable_location_desc,
            scopes = setOf(PromptVariableScope.QUICK_MESSAGE),
        ),
        PromptVariableDescriptor(
            key = "target",
            labelRes = R.string.prompt_variable_target,
            descriptionRes = R.string.prompt_variable_target_desc,
            scopes = setOf(PromptVariableScope.QUICK_MESSAGE),
        ),
        PromptVariableDescriptor(
            key = "scene",
            labelRes = R.string.prompt_variable_scene,
            descriptionRes = R.string.prompt_variable_scene_desc,
            scopes = setOf(PromptVariableScope.QUICK_MESSAGE),
        ),
        PromptVariableDescriptor(
            key = "action",
            labelRes = R.string.prompt_variable_action,
            descriptionRes = R.string.prompt_variable_action_desc,
            scopes = setOf(PromptVariableScope.QUICK_MESSAGE),
        ),
        PromptVariableDescriptor(
            key = "emotion",
            labelRes = R.string.prompt_variable_emotion,
            descriptionRes = R.string.prompt_variable_emotion_desc,
            scopes = setOf(PromptVariableScope.QUICK_MESSAGE),
        ),
        PromptVariableDescriptor(
            key = "goal",
            labelRes = R.string.prompt_variable_goal,
            descriptionRes = R.string.prompt_variable_goal_desc,
            scopes = setOf(PromptVariableScope.QUICK_MESSAGE),
        ),
        PromptVariableDescriptor(
            key = "relationship",
            labelRes = R.string.prompt_variable_relationship,
            descriptionRes = R.string.prompt_variable_relationship_desc,
            scopes = setOf(PromptVariableScope.QUICK_MESSAGE),
        ),
        PromptVariableDescriptor(
            key = "world",
            labelRes = R.string.prompt_variable_world,
            descriptionRes = R.string.prompt_variable_world_desc,
            scopes = setOf(PromptVariableScope.QUICK_MESSAGE),
        ),
        PromptVariableDescriptor(
            key = "style",
            labelRes = R.string.prompt_variable_style,
            descriptionRes = R.string.prompt_variable_style_desc,
            scopes = setOf(PromptVariableScope.QUICK_MESSAGE),
        ),
    )

    fun forScope(scope: PromptVariableScope): List<PromptVariableDescriptor> =
        all.filter { scope in it.scopes }

    fun primaryForScope(scope: PromptVariableScope): List<PromptVariableDescriptor> =
        forScope(scope).filter { it.aliasOf == null }

    fun aliasesFor(
        variable: PromptVariableDescriptor,
        scope: PromptVariableScope,
    ): List<PromptVariableDescriptor> = forScope(scope).filter { it.aliasOf == variable.key }
}
