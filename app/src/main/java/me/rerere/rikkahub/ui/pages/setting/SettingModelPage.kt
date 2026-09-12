package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import me.rerere.rikkahub.ui.components.ui.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AiBrain01
import me.rerere.hugeicons.stroke.AiEditing
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.usesVectorMemory
import me.rerere.rikkahub.data.datastore.ChatSuggestionStyle
import me.rerere.rikkahub.data.model.withSuggestionConfig
import me.rerere.rikkahub.data.datastore.ChatSuggestionDisplayMode
import me.rerere.rikkahub.data.datastore.SuggestionInsertMode
import me.rerere.rikkahub.data.datastore.resolveBackgroundChatModel
import me.rerere.rikkahub.ui.components.ai.ModelListSheet
import me.rerere.rikkahub.ui.components.ai.rememberModelListState
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.AppearanceDropdownMenu as DropdownMenu
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel
import kotlin.uuid.Uuid
import kotlin.math.roundToInt

@Composable
fun SettingModelPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val pagerState = rememberPagerState { 2 }
    val scope = rememberCoroutineScope()

    Scaffold(
        containerColor = CustomColors.scaffoldContainerColor,
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_model_page_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        bottomBar = {
            BottomAppBar(
                containerColor = CustomColors.cardColorsOnSurfaceContainer.containerColor
            ) {
                NavigationBarItem(
                    selected = pagerState.currentPage == 0,
                    onClick = { scope.launch { pagerState.animateScrollToPage(0) } },
                    icon = { Icon(HugeIcons.AiBrain01, null) },
                    label = { Text(stringResource(R.string.setting_model_page_tab_model)) }
                )
                NavigationBarItem(
                    selected = pagerState.currentPage == 1,
                    onClick = { scope.launch { pagerState.animateScrollToPage(1) } },
                    icon = { Icon(HugeIcons.AiEditing, null) },
                    label = { Text(stringResource(R.string.setting_model_page_tab_prompt)) }
                )
            }
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
    ) { contentPadding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            when (page) {
                0 -> ModelSettingsPage(settings = settings, vm = vm, contentPadding = contentPadding)
                1 -> PromptSettingsPage(settings = settings, vm = vm, contentPadding = contentPadding)
            }
        }
    }
}

@Composable
private fun ModelSettingsPage(settings: Settings, vm: SettingVM, contentPadding: PaddingValues) {
    val vectorOnly = settings.usesVectorMemory()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding + PaddingValues(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            ModelSettingItem(
                title = stringResource(R.string.setting_model_page_chat_model),
                description = stringResource(R.string.setting_model_page_chat_model_desc),
                modelId = settings.chatModelId,
                providers = settings.providers,
                onSelect = { vm.updateSettings(settings.copy(chatModelId = it.id)) },
            )
        }
        item {
            ModelSettingItem(
                title = stringResource(R.string.setting_model_page_fast_model),
                description = stringResource(R.string.setting_model_page_fast_model_desc),
                modelId = settings.fastModelId,
                providers = settings.providers,
                onSelect = { vm.updateSettings(settings.copy(fastModelId = it.id)) },
            )
        }
        item {
            ModelSettingItem(
                title = stringResource(R.string.setting_model_page_image_model),
                description = stringResource(R.string.setting_model_page_image_model_desc),
                modelId = settings.imageGenerationModelId,
                providers = settings.providers,
                type = ModelType.IMAGE,
                onSelect = { model ->
                    vm.updateSettings { current ->
                        current.copy(imageGenerationModelId = model.id)
                    }
                },
                onClear = {
                    // Keep the nullable UI semantics while preserving the existing
                    // serialized Settings field used by older versions.
                    vm.updateSettings { current ->
                        current.copy(imageGenerationModelId = Uuid.random())
                    }
                },
            )
        }
        item {
            ModelSettingItem(
                title = stringResource(R.string.setting_model_page_title_model),
                description = backgroundModelDescription(
                    base = stringResource(R.string.setting_model_page_title_model_desc),
                    modelName = settings.resolveBackgroundChatModel(settings.titleModelId)?.displayName,
                ),
                modelId = settings.titleModelId,
                providers = settings.providers,
                onSelect = { vm.updateSettings(settings.copy(titleModelId = it.id)) },
                onClear = { vm.updateSettings(settings.copy(titleModelId = null)) },
            )
        }
        item {
            TitleGenerationOptions(settings = settings, vm = vm)
        }
        item {
            SuggestionModelSettingItem(
                settings = settings,
                vm = vm,
            )
        }
        item {
            ModelSettingItem(
                title = stringResource(R.string.setting_model_page_translate_model),
                description = stringResource(R.string.setting_model_page_translate_model_desc),
                modelId = settings.translateModeId,
                providers = settings.providers,
                onSelect = { vm.updateSettings(settings.copy(translateModeId = it.id)) },
            )
        }
        item {
            ModelSettingItem(
                title = stringResource(R.string.setting_model_page_ocr_model),
                description = stringResource(R.string.setting_model_page_ocr_model_desc),
                modelId = settings.ocrModelId,
                providers = settings.providers,
                onSelect = { vm.updateSettings(settings.copy(ocrModelId = it.id)) },
            )
        }
        item {
            ModelSettingItem(
                title = stringResource(R.string.setting_model_page_compress_model),
                description = stringResource(R.string.setting_model_page_compress_model_desc),
                modelId = settings.compressModelId,
                providers = settings.providers,
                onSelect = { vm.updateSettings(settings.copy(compressModelId = it.id)) },
            )
        }
        item {
            ModelSettingItem(
                title = stringResource(R.string.setting_model_page_embedding_model),
                description = stringResource(R.string.setting_model_page_embedding_model_desc),
                modelId = settings.embeddingModelId,
                providers = settings.providers,
                type = ModelType.EMBEDDING,
                onSelect = { vm.updateSettings(settings.copy(embeddingModelId = it.id)) },
                onClear = { vm.updateSettings(settings.copy(embeddingModelId = null)) },
            )
        }
        item {
            ModelSettingItem(
                title = stringResource(R.string.setting_model_page_memory_extraction_model),
                description = stringResource(
                    if (vectorOnly) R.string.memory_vector_chat_extraction_unavailable
                    else R.string.setting_model_page_memory_extraction_model_desc
                ),
                enabled = !vectorOnly,
                modelId = settings.memoryExtractionModelId.takeUnless { vectorOnly },
                providers = settings.providers,
                onSelect = { vm.updateSettings(settings.copy(memoryExtractionModelId = it.id)) },
                onClear = { vm.updateSettings(settings.copy(memoryExtractionModelId = null)) },
            )
        }
        item {
            ModelSettingItem(
                title = stringResource(R.string.setting_memory_vector_model),
                description = stringResource(R.string.setting_memory_vector_model_desc),
                modelId = settings.memoryExtractionModelId.takeIf { vectorOnly },
                providers = settings.providers,
                type = ModelType.EMBEDDING,
                onSelect = { vm.updateSettings(settings.copy(memoryExtractionModelId = it.id)) },
                onClear = { vm.updateSettings(settings.copy(memoryExtractionModelId = null)) },
            )
        }
    }
}

@Composable
private fun backgroundModelDescription(base: String, modelName: String?): String =
    base + "\n" + if (modelName == null) {
        stringResource(R.string.setting_model_page_background_model_unavailable)
    } else {
        stringResource(R.string.setting_model_page_background_model_active, modelName)
    }

@Composable
private fun TitleGenerationOptions(settings: Settings, vm: SettingVM) {
    var maxLength by remember(settings.titleMaxLength) {
        mutableFloatStateOf(settings.titleMaxLength.toFloat())
    }
    CardGroup(title = { Text(stringResource(R.string.setting_model_page_title_options)) }) {
        item(
            headlineContent = { Text(stringResource(R.string.setting_model_page_enable_title_generation)) },
            supportingContent = { Text(stringResource(R.string.setting_model_page_enable_title_generation_desc)) },
            trailingContent = {
                Switch(
                    checked = settings.enableTitleGeneration,
                    onCheckedChange = { enabled ->
                        vm.updateSettings { it.copy(enableTitleGeneration = enabled) }
                    },
                )
            },
        )
        if (settings.enableTitleGeneration) {
            item(
                headlineContent = { Text(stringResource(R.string.setting_model_page_title_max_length)) },
                supportingContent = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Slider(
                            value = maxLength,
                            onValueChange = { maxLength = it },
                            onValueChangeFinished = {
                                vm.updateSettings {
                                    it.copy(titleMaxLength = maxLength.roundToInt().coerceIn(8, 80))
                                }
                            },
                            valueRange = 8f..80f,
                            steps = 17,
                            modifier = Modifier.weight(1f),
                        )
                        Text(maxLength.roundToInt().toString())
                    }
                },
            )
        }
    }
}

@Composable
private fun SuggestionModelSettingItem(
    settings: Settings,
    vm: SettingVM,
) {
    val title = stringResource(R.string.setting_model_page_suggestion_model)
    val state = rememberModelListState(
        modelId = settings.suggestionModelId,
        providers = settings.providers,
        type = ModelType.CHAT,
    )
    var suggestionCount by remember(settings.suggestionCount) {
        mutableFloatStateOf(settings.suggestionCount.toFloat())
    }
    var suggestionMaxLength by remember(settings.suggestionMaxLength) {
        mutableFloatStateOf(settings.suggestionMaxLength.toFloat())
    }
    var showStyleMenu by remember { mutableStateOf(false) }
    var showInsertModeMenu by remember { mutableStateOf(false) }
    var showDisplayModeMenu by remember { mutableStateOf(false) }

    Column {
        CardGroup(title = { Text(title) }) {
            item(
                headlineContent = { Text(stringResource(R.string.setting_model_page_enable_suggestion)) },
                trailingContent = {
                    Switch(
                        checked = settings.enableSuggestion,
                        onCheckedChange = {
                            vm.updateSettings { current -> current.copy(enableSuggestion = it, suggestionOptions =
                                if (it && current.suggestionOptions.trigger == me.rerere.rikkahub.data.model.SuggestionTrigger.DISABLED)
                                    current.suggestionOptions.copy(trigger = me.rerere.rikkahub.data.model.SuggestionTrigger.AUTOMATIC)
                                else current.suggestionOptions) }
                        }
                    )
                },
            )
            if (settings.enableSuggestion) {
                item(
                    onClick = { state.open() },
                    headlineContent = { Text(title) },
                    trailingContent = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = state.currentModel?.displayName
                                    ?: stringResource(R.string.model_list_select_model),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (state.currentModel != null) {
                                IconButton(
                                    onClick = { vm.updateSettings { it.copy(suggestionModelId = null) } },
                                    modifier = Modifier.size(20.dp),
                                ) {
                                    Icon(HugeIcons.Cancel01, contentDescription = null, modifier = Modifier.size(14.dp))
                                }
                            } else {
                                Icon(
                                    HugeIcons.ArrowRight01,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    },
                )
                item(
                    headlineContent = { Text(stringResource(R.string.setting_model_page_suggestion_count)) },
                    supportingContent = {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Slider(
                                value = suggestionCount,
                                onValueChange = { suggestionCount = it },
                                onValueChangeFinished = {
                                    vm.updateSettings {
                                        it.copy(suggestionCount = suggestionCount.roundToInt().coerceIn(1, 10))
                                    }
                                },
                                valueRange = 1f..10f,
                                steps = 8,
                                modifier = Modifier.weight(1f),
                            )
                            Text(suggestionCount.roundToInt().toString())
                        }
                    },
                )
                item(
                    headlineContent = { Text(stringResource(R.string.setting_model_page_suggestion_max_length)) },
                    supportingContent = {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Slider(
                                value = suggestionMaxLength,
                                onValueChange = { suggestionMaxLength = it },
                                onValueChangeFinished = {
                                    vm.updateSettings {
                                        it.copy(
                                            suggestionMaxLength = suggestionMaxLength.roundToInt()
                                                .coerceIn(10, 200)
                                        )
                                    }
                                },
                                valueRange = 10f..200f,
                                steps = 18,
                                modifier = Modifier.weight(1f),
                            )
                            Text(suggestionMaxLength.roundToInt().toString())
                        }
                    },
                )
                item(
                    headlineContent = { Text(stringResource(R.string.setting_model_page_suggestion_style)) },
                    trailingContent = {
                        Box {
                            TextButton(onClick = { showStyleMenu = true }) {
                                Text(suggestionStyleLabel(settings.suggestionStyle))
                            }
                            DropdownMenu(
                                expanded = showStyleMenu,
                                onDismissRequest = { showStyleMenu = false },
                            ) {
                                ChatSuggestionStyle.entries.forEach { style ->
                                    DropdownMenuItem(
                                        text = { Text(suggestionStyleLabel(style)) },
                                        onClick = {
                                            showStyleMenu = false
                                            vm.updateSettings { it.copy(suggestionStyle = style) }
                                        },
                                    )
                                }
                            }
                        }
                    },
                )
                item(
                    headlineContent = { Text(stringResource(R.string.setting_model_page_suggestion_insert_mode)) },
                    trailingContent = {
                        Box {
                            TextButton(onClick = { showInsertModeMenu = true }) {
                                Text(suggestionInsertModeLabel(settings.suggestionInsertMode))
                            }
                            DropdownMenu(
                                expanded = showInsertModeMenu,
                                onDismissRequest = { showInsertModeMenu = false },
                            ) {
                                SuggestionInsertMode.entries.forEach { mode ->
                                    DropdownMenuItem(
                                        text = { Text(suggestionInsertModeLabel(mode)) },
                                        onClick = {
                                            showInsertModeMenu = false
                                            vm.updateSettings { it.copy(suggestionInsertMode = mode) }
                                        },
                                    )
                                }
                            }
                        }
                    },
                )
                item(
                    headlineContent = { Text(stringResource(R.string.setting_model_page_suggestion_display_mode)) },
                    supportingContent = {
                        Text(stringResource(R.string.setting_model_page_suggestion_display_mode_desc))
                    },
                    trailingContent = {
                        Box {
                            TextButton(onClick = { showDisplayModeMenu = true }) {
                                Text(suggestionDisplayModeLabel(settings.suggestionDisplayMode))
                            }
                            DropdownMenu(
                                expanded = showDisplayModeMenu,
                                onDismissRequest = { showDisplayModeMenu = false },
                            ) {
                                ChatSuggestionDisplayMode.entries.forEach { mode ->
                                    DropdownMenuItem(
                                        text = { Text(suggestionDisplayModeLabel(mode)) },
                                        onClick = {
                                            showDisplayModeMenu = false
                                            vm.updateSettings { it.copy(suggestionDisplayMode = mode) }
                                        },
                                    )
                                }
                            }
                        }
                    },
                )
            }
        }
        Text(
            text = backgroundModelDescription(
                base = stringResource(R.string.setting_model_page_suggestion_model_desc),
                modelName = settings.resolveBackgroundChatModel(settings.suggestionModelId)?.displayName,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
        )
    }

    me.rerere.rikkahub.ui.pages.chat.GlobalSuggestionSettingsEntry(settings) { config -> vm.updateSettings { it.withSuggestionConfig(config) } }
    ModelListSheet(state = state, onSelect = { model -> vm.updateSettings { it.copy(suggestionModelId = model.id) } })
}

@Composable
private fun suggestionStyleLabel(style: ChatSuggestionStyle): String = stringResource(
    when (style) {
        ChatSuggestionStyle.BALANCED -> R.string.setting_model_page_suggestion_style_balanced
        ChatSuggestionStyle.FOLLOW_UP -> R.string.setting_model_page_suggestion_style_follow_up
        ChatSuggestionStyle.ACTIONABLE -> R.string.setting_model_page_suggestion_style_actionable
        ChatSuggestionStyle.CONCISE -> R.string.setting_model_page_suggestion_style_concise
        ChatSuggestionStyle.ROLEPLAY -> R.string.setting_model_page_suggestion_style_roleplay
    }
)

@Composable
private fun suggestionInsertModeLabel(mode: SuggestionInsertMode): String = stringResource(
    when (mode) {
        SuggestionInsertMode.REPLACE -> R.string.setting_model_page_suggestion_insert_replace
        SuggestionInsertMode.APPEND -> R.string.setting_model_page_suggestion_insert_append
    }
)

@Composable
private fun suggestionDisplayModeLabel(mode: ChatSuggestionDisplayMode): String = stringResource(
    when (mode) {
        ChatSuggestionDisplayMode.AUTO -> R.string.setting_model_page_suggestion_display_auto
        ChatSuggestionDisplayMode.COMPACT -> R.string.setting_model_page_suggestion_display_compact
        ChatSuggestionDisplayMode.TWO_ROW -> R.string.setting_model_page_suggestion_display_two_row
        ChatSuggestionDisplayMode.RICH -> R.string.setting_model_page_suggestion_display_rich
    }
)

@Composable
private fun ModelSettingItem(
    title: String,
    description: String,
    modelId: Uuid?,
    providers: List<ProviderSetting>,
    type: ModelType = ModelType.CHAT,
    onSelect: (Model) -> Unit,
    onClear: (() -> Unit)? = null,
    enabled: Boolean = true,
) {
    val state = rememberModelListState(
        modelId = modelId,
        providers = providers,
        type = type,
    )

    Column {
        CardGroup(title = { Text(title) }) {
            item(
                onClick = if (enabled) ({ state.open() }) else null,
                modifier = Modifier.alpha(if (enabled) 1f else 0.38f),
                headlineContent = { Text(title) },
                trailingContent = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = state.currentModel?.displayName
                                ?: stringResource(R.string.model_list_select_model),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (onClear != null && state.currentModel != null) {
                            IconButton(onClick = onClear, enabled = enabled, modifier = Modifier.size(20.dp)) {
                                Icon(HugeIcons.Cancel01, contentDescription = null, modifier = Modifier.size(14.dp))
                            }
                        } else {
                            Icon(
                                HugeIcons.ArrowRight01,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                },
            )
        }
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
        )
    }

    ModelListSheet(state = state, onSelect = onSelect)
}
