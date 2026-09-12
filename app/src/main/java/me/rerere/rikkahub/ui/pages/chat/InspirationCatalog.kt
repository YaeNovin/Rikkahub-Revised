package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.InspirationAudience
import me.rerere.rikkahub.data.model.InspirationCard

@Composable
internal fun inspirationCatalog(): List<InspirationCard> = listOf(
    Triple("explain", R.string.chat_inspiration_explain_title, R.string.chat_inspiration_explain_prompt),
    Triple("plan", R.string.chat_inspiration_plan_title, R.string.chat_inspiration_plan_prompt),
    Triple("write", R.string.chat_inspiration_write_title, R.string.chat_inspiration_write_prompt),
    Triple("brainstorm", R.string.chat_inspiration_brainstorm_title, R.string.chat_inspiration_brainstorm_prompt),
    Triple("summarize", R.string.chat_inspiration_summarize_title, R.string.chat_inspiration_summarize_prompt),
    Triple("compare", R.string.chat_inspiration_compare_title, R.string.chat_inspiration_compare_prompt),
    Triple("learn", R.string.chat_inspiration_learn_title, R.string.chat_inspiration_learn_prompt),
    Triple("code", R.string.chat_inspiration_code_title, R.string.chat_inspiration_code_prompt),
).map { (id, title, prompt) -> InspirationCard("builtin:$id", stringResource(title), stringResource(prompt), InspirationAudience.NORMAL) } + listOf(
    Triple("dialogue", R.string.inspiration_dialogue_title, R.string.inspiration_dialogue_prompt),
    Triple("scene", R.string.inspiration_scene_title, R.string.inspiration_scene_prompt),
    Triple("plot", R.string.inspiration_plot_title, R.string.inspiration_plot_prompt),
    Triple("character", R.string.inspiration_character_title, R.string.inspiration_character_prompt),
    Triple("encounter", R.string.inspiration_encounter_title, R.string.inspiration_encounter_prompt),
    Triple("daily", R.string.inspiration_daily_title, R.string.inspiration_daily_prompt),
).map { (id, title, prompt) -> InspirationCard("builtin:$id", stringResource(title), stringResource(prompt), InspirationAudience.ENTERTAINMENT) }
