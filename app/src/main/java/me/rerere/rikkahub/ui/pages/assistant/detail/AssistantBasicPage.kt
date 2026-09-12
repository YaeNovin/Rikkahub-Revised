package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import me.rerere.rikkahub.ui.components.ui.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.ListItem
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import me.rerere.rikkahub.ui.components.ui.AppearanceModalBottomSheet as ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.material3.TextButton
import me.rerere.rikkahub.data.model.selectBackground
import me.rerere.rikkahub.data.model.selectGradientBackground
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.formatContextWindowTokens
import me.rerere.ai.provider.parseContextWindowTokens
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.db.entity.KnowledgeBaseEntity
import me.rerere.rikkahub.data.ai.context.MIN_ROLLING_CONTEXT_THRESHOLD_TOKENS
import me.rerere.rikkahub.data.ai.context.effectiveRollingContextThreshold
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.GradientBackgroundPreset
import me.rerere.rikkahub.data.model.GradientBackgroundCustomColors
import me.rerere.rikkahub.data.datastore.isGlobalBackgroundAppliedToChat
import me.rerere.rikkahub.ui.components.ai.ModelSelector
import me.rerere.rikkahub.ui.components.ai.ReasoningButton
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.FormItem
import me.rerere.rikkahub.ui.components.ui.AnimatedGradientBackground
import me.rerere.rikkahub.ui.components.ui.GradientBackgroundSpec
import me.rerere.rikkahub.ui.components.ui.HctColorPicker
import me.rerere.rikkahub.ui.components.ui.Select
import me.rerere.rikkahub.ui.components.ui.TagsInput
import me.rerere.rikkahub.ui.components.ui.UIAvatar
import me.rerere.rikkahub.ui.hooks.heroAnimation
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import me.rerere.rikkahub.ui.theme.createGradientBackgroundPalette
import me.rerere.rikkahub.ui.theme.updateGradientCustomColor
import me.rerere.rikkahub.utils.toFixed
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import kotlin.uuid.Uuid
import kotlin.math.roundToInt
import me.rerere.rikkahub.data.model.Tag as DataTag

@Composable
fun AssistantBasicPage(id: String) {
    val vm: AssistantDetailVM = koinViewModel(
        parameters = {
            parametersOf(id)
        }
    )
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val providers by vm.providers.collectAsStateWithLifecycle()
    val tags by vm.tags.collectAsStateWithLifecycle()
    val workspaces by vm.workspaces.collectAsStateWithLifecycle()
    val knowledgeBases by vm.knowledgeBases.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(stringResource(R.string.assistant_page_tab_basic))
                },
                navigationIcon = {
                    BackButton()
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.scaffoldContainerColor,
    ) { innerPadding ->
        AssistantBasicContent(
            innerPadding = innerPadding,
            assistant = assistant,
            providers = providers,
            tags = tags,
            workspaces = workspaces,
            knowledgeBases = knowledgeBases,
            onUpdate = { vm.update(it, before = assistant) },
            vm = vm
        )
    }
}

@Composable
internal fun AssistantBasicContent(
    innerPadding: PaddingValues,
    assistant: Assistant,
    providers: List<me.rerere.ai.provider.ProviderSetting>,
    tags: List<DataTag>,
    workspaces: List<WorkspaceEntity>,
    knowledgeBases: List<KnowledgeBaseEntity>,
    onUpdate: (Assistant) -> Unit,
    vm: AssistantDetailVM
) {
    val settings = LocalSettings.current
    val globalBackgroundOverridesChat = settings.isGlobalBackgroundAppliedToChat()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(innerPadding)
            .imePadding(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            UIAvatar(
                value = assistant.avatar,
                name = assistant.name.ifBlank { stringResource(R.string.assistant_page_default_assistant) },
                onUpdate = { avatar ->
                    onUpdate(
                        assistant.copy(
                            avatar = avatar
                        )
                    )
                },
                modifier = Modifier
                    .size(80.dp)
                    .heroAnimation("assistant_${assistant.id}")
            )
        }

        me.rerere.rikkahub.ui.pages.chat.AssistantSuggestionSettingsEntry(assistant, settings, onUpdate)
        me.rerere.rikkahub.ui.pages.chat.InspirationSettingsEntry(assistant.id)
        Card(
            colors = CustomColors.cardColorsOnSurfaceContainer
        ) {
            FormItem(
                label = {
                    Text(stringResource(R.string.assistant_page_name))
                },
                modifier = Modifier.padding(8.dp),

                ) {
                OutlinedTextField(
                    value = assistant.name,
                    onValueChange = {
                        onUpdate(
                            assistant.copy(
                                name = it
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            HorizontalDivider()

            KnowledgeBaseBinding(
                assistant = assistant,
                knowledgeBases = knowledgeBases,
                onUpdate = onUpdate,
            )

            HorizontalDivider()

            FormItem(
                label = {
                    Text(stringResource(R.string.assistant_page_tags))
                },
                modifier = Modifier.padding(8.dp),
            ) {
                TagsInput(
                    value = assistant.tags,
                    tags = tags,
                    onValueChange = { tagIds, tagList ->
                        vm.updateTags(tagIds, tagList)
                    },
                )
            }

            HorizontalDivider()

            FormItem(
                label = {
                    Text(stringResource(R.string.assistant_page_workspace))
                },
                description = {
                    Text(stringResource(R.string.assistant_page_workspace_desc))
                },
                modifier = Modifier.padding(8.dp),
            ) {
                val selectedWorkspace = workspaces.find { it.id == assistant.workspaceId?.toString() }
                Select(
                    options = listOf<WorkspaceEntity?>(null) + workspaces,
                    selectedOption = selectedWorkspace,
                    onOptionSelected = { workspace ->
                        onUpdate(
                            assistant.copy(
                                workspaceId = workspace?.id?.let { Uuid.parse(it) }
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    optionToString = { workspace ->
                        workspace?.name ?: stringResource(R.string.workspace_no_binding)
                    },
                )
            }

            HorizontalDivider()

            FormItem(
                modifier = Modifier.padding(8.dp),
                label = {
                    Text(stringResource(R.string.assistant_page_use_assistant_avatar))
                },
                description = {
                    Text(stringResource(R.string.assistant_page_use_assistant_avatar_desc))
                },
                tail = {
                    Switch(
                        checked = assistant.useAssistantAvatar,
                        onCheckedChange = {
                            onUpdate(
                                assistant.copy(
                                    useAssistantAvatar = it
                                )
                            )
                        }
                    )
                }
            )
        }

        Card(
            colors = CustomColors.cardColorsOnSurfaceContainer
        ) {
            FormItem(
                modifier = Modifier.padding(8.dp),
                label = {
                    Text(stringResource(R.string.assistant_page_chat_model))
                },
                description = {
                    Text(stringResource(R.string.assistant_page_chat_model_desc))
                },
                content = {
                    ModelSelector(
                        modelId = assistant.chatModelId,
                        providers = providers,
                        type = ModelType.CHAT,
                        onSelect = {
                            onUpdate(
                                assistant.copy(
                                    chatModelId = it.id
                                )
                            )
                        },
                    )
                }
            )
            HorizontalDivider()
            FormItem(
                modifier = Modifier.padding(8.dp),
                label = {
                    Text(stringResource(R.string.assistant_page_temperature))
                },
                description = {
                    Text(
                        text = buildAnnotatedString {
                            append(stringResource(R.string.assistant_page_temperature_warning))
                        }
                    )
                },
                tail = {
                    Switch(
                        checked = assistant.temperature != null,
                        onCheckedChange = { enabled ->
                            onUpdate(
                                assistant.copy(
                                    temperature = if (enabled) 1.0f else null
                                )
                            )
                        }
                    )
                }
            ) {
                if (assistant.temperature != null) {
                    var temperatureInput by remember(assistant.id) {
                        mutableStateOf(assistant.temperature.toString())
                    }
                    val temperatureValue = temperatureInput.toFloatOrNull()
                    OutlinedTextField(
                        value = temperatureInput,
                        onValueChange = { value ->
                            temperatureInput = value
                            value.toFloatOrNull()?.takeIf { it in 0f..2f }?.let { temperature ->
                                onUpdate(
                                    assistant.copy(
                                        temperature = temperature
                                    )
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        isError = temperatureValue == null || temperatureValue !in 0f..2f,
                        supportingText = {
                            Text("0 - 2")
                        }
                    )
                }
            }
            HorizontalDivider()
            FormItem(
                modifier = Modifier.padding(8.dp),
                label = {
                    Text(stringResource(R.string.assistant_page_rolling_context_compression))
                },
                description = {
                    Text(stringResource(R.string.assistant_page_rolling_context_compression_desc))
                },
                tail = {
                    Switch(
                        checked = assistant.enableRollingContextCompression,
                        onCheckedChange = { enabled ->
                            onUpdate(assistant.copy(enableRollingContextCompression = enabled))
                        },
                    )
                },
            ) {
                val rollingThreshold = effectiveRollingContextThreshold(
                    assistant.rollingContextCompressionThresholdTokens,
                )
                var thresholdInput by remember(assistant.id, rollingThreshold) {
                    mutableStateOf(formatContextWindowTokens(rollingThreshold))
                }
                val threshold = parseContextWindowTokens(thresholdInput)
                OutlinedTextField(
                    value = thresholdInput,
                    onValueChange = { value ->
                        thresholdInput = value
                        parseContextWindowTokens(value)
                            ?.takeIf { it >= MIN_ROLLING_CONTEXT_THRESHOLD_TOKENS }
                            ?.let { parsedThreshold ->
                                onUpdate(
                                    assistant.copy(
                                        rollingContextCompressionThresholdTokens = parsedThreshold,
                                    )
                                )
                            }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = assistant.enableRollingContextCompression,
                    label = {
                        Text(stringResource(R.string.assistant_page_rolling_context_threshold))
                    },
                    supportingText = {
                        Text(
                            stringResource(
                                R.string.assistant_page_rolling_context_threshold_desc,
                            )
                        )
                    },
                    singleLine = true,
                    isError = assistant.enableRollingContextCompression &&
                        (threshold == null || threshold < MIN_ROLLING_CONTEXT_THRESHOLD_TOKENS),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                )
            }
            HorizontalDivider()
            FormItem(
                modifier = Modifier.padding(8.dp),
                label = {
                    Text(stringResource(R.string.assistant_page_top_p))
                },
                description = {
                    Text(
                        text = buildAnnotatedString {
                            append(stringResource(R.string.assistant_page_top_p_warning))
                        }
                    )
                },
                tail = {
                    Switch(
                        checked = assistant.topP != null,
                        onCheckedChange = { enabled ->
                            onUpdate(
                                assistant.copy(
                                    topP = if (enabled) 1.0f else null
                                )
                            )
                        }
                    )
                }
            ) {
                assistant.topP?.let { topP ->
                    var topPInput by remember(assistant.id) {
                        mutableStateOf(topP.toString())
                    }
                    val topPValue = topPInput.toFloatOrNull()
                    OutlinedTextField(
                        value = topPInput,
                        onValueChange = { value ->
                            topPInput = value
                            value.toFloatOrNull()?.takeIf { it in 0f..1f }?.let { nextTopP ->
                                onUpdate(
                                    assistant.copy(
                                        topP = nextTopP
                                    )
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        isError = topPValue == null || topPValue !in 0f..1f,
                        supportingText = {
                            Text("0 - 1")
                        }
                    )
                }
            }
            HorizontalDivider()
            FormItem(
                modifier = Modifier.padding(8.dp),
                label = {
                    Text(stringResource(R.string.assistant_page_stream_output))
                },
                description = {
                    Text(stringResource(R.string.assistant_page_stream_output_desc))
                },
                tail = {
                    Switch(
                        checked = assistant.streamOutput,
                        onCheckedChange = {
                            onUpdate(
                                assistant.copy(
                                    streamOutput = it
                                )
                            )
                        }
                    )
                }
            )
            HorizontalDivider()
            FormItem(
                modifier = Modifier.padding(8.dp),
                label = {
                    Text(stringResource(R.string.assistant_page_thinking_budget))
                },
            ) {
                ReasoningButton(
                    reasoningLevel = assistant.reasoningLevel,
                    onUpdateReasoningLevel = { level ->
                        onUpdate(assistant.copy(reasoningLevel = level))
                    }
                )
            }
            HorizontalDivider()
            FormItem(
                modifier = Modifier.padding(8.dp),
                label = {
                    Text(stringResource(R.string.assistant_page_max_tokens))
                },
                description = {
                    Text(stringResource(R.string.assistant_page_max_tokens_desc))
                }
            ) {
                OutlinedTextField(
                    value = assistant.maxTokens?.toString() ?: "",
                    onValueChange = { text ->
                        val tokens = if (text.isBlank()) {
                            null
                        } else {
                            text.toIntOrNull()?.takeIf { it > 0 }
                        }
                        onUpdate(
                            assistant.copy(
                                maxTokens = tokens
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(stringResource(R.string.assistant_page_max_tokens_no_limit))
                    },
                    supportingText = {
                        if (assistant.maxTokens != null) {
                            Text(stringResource(R.string.assistant_page_max_tokens_limit, assistant.maxTokens))
                        } else {
                            Text(stringResource(R.string.assistant_page_max_tokens_no_token_limit))
                        }
                    }
                )
            }
        }

        Card(
            colors = CustomColors.cardColorsOnSurfaceContainer
        ) {
            if (globalBackgroundOverridesChat) {
                FormItem(
                    modifier = Modifier.padding(8.dp),
                    label = {
                        Text(stringResource(R.string.assistant_page_background_global_override_title))
                    },
                    description = {
                        Text(stringResource(R.string.assistant_page_background_global_override_desc))
                    },
                )
                TextButton(onClick = vm::useAssistantBackground, modifier = Modifier.padding(horizontal = 8.dp)) {
                    Text("关闭全局背景覆盖，使用助手背景")
                }
                HorizontalDivider()
            }
            FormItem(
                modifier = Modifier.padding(8.dp),
                label = {
                    Text(stringResource(R.string.assistant_page_gradient_background))
                },
                description = {
                    Text(stringResource(R.string.assistant_page_gradient_background_desc))
                },
                tail = {
                    Switch(
                        checked = assistant.useGradientBackground,
                        enabled = !globalBackgroundOverridesChat,
                        onCheckedChange = {
                            onUpdate(
                                assistant.selectGradientBackground(it)
                            )
                        }
                    )
                }
            )

            if (assistant.useGradientBackground) {
                HorizontalDivider()
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                        .padding(horizontal = 8.dp, vertical = 10.dp)
                        .clip(MaterialTheme.shapes.medium),
                ) {
                    AnimatedGradientBackground(
                        spec = GradientBackgroundSpec(
                            opacity = assistant.backgroundOpacity,
                            animationEnabled = assistant.gradientBackgroundAnimation,
                            speed = assistant.gradientBackgroundSpeed,
                            followTheme = assistant.gradientBackgroundFollowTheme,
                            preset = assistant.gradientBackgroundPreset,
                            customColors = assistant.gradientBackgroundCustomColors,
                            intensity = assistant.gradientBackgroundIntensity,
                            motionScale = assistant.gradientBackgroundMotionScale,
                            blobCount = assistant.gradientBackgroundBlobCount,
                            softness = assistant.gradientBackgroundSoftness,
                            angle = assistant.gradientBackgroundAngle,
                            vignette = assistant.gradientBackgroundVignette,
                            performanceEffectsEnabled = settings.advancedAppearanceSetting
                                .enableGradientPerformanceEffects,
                            rendererMode = settings.advancedAppearanceSetting.gradientRendererMode,
                            respectSystemReducedMotion = settings.advancedAppearanceSetting
                                .respectSystemReducedMotion,
                        ),
                    )
                }
                HorizontalDivider()
                FormItem(
                    modifier = Modifier.padding(8.dp),
                    label = { Text(stringResource(R.string.assistant_page_gradient_preset)) },
                    description = {
                        Text(stringResource(R.string.assistant_page_gradient_preset_desc))
                    },
                ) {
                    GradientPresetSelector(
                        selected = assistant.gradientBackgroundPreset,
                        enabled = !globalBackgroundOverridesChat &&
                            !assistant.gradientBackgroundFollowTheme,
                        onSelected = { preset ->
                            onUpdate(
                                assistant.copy(
                                    gradientBackgroundPreset = preset,
                                    gradientBackgroundCustomColors = GradientBackgroundCustomColors(),
                                )
                            )
                        },
                    )
                }
                HorizontalDivider()
                GradientCustomColorEditor(
                    assistant = assistant,
                    enabled = !globalBackgroundOverridesChat &&
                        !assistant.gradientBackgroundFollowTheme,
                    onColorsUpdate = vm::updateGradientBackgroundCustomColors,
                )
                HorizontalDivider()
                FormItem(
                    modifier = Modifier.padding(8.dp),
                    label = { Text(stringResource(R.string.assistant_page_gradient_follow_theme)) },
                    description = {
                        Text(stringResource(R.string.assistant_page_gradient_follow_theme_desc))
                    },
                    tail = {
                        Switch(
                            checked = assistant.gradientBackgroundFollowTheme,
                            enabled = !globalBackgroundOverridesChat,
                            onCheckedChange = { enabled ->
                                onUpdate(
                                    assistant.copy(
                                        gradientBackgroundFollowTheme = enabled,
                                        gradientBackgroundCustomColors = if (enabled) {
                                            GradientBackgroundCustomColors()
                                        } else {
                                            assistant.gradientBackgroundCustomColors
                                        },
                                    )
                                )
                            },
                        )
                    },
                )
                FormItem(
                    modifier = Modifier.padding(8.dp),
                    label = { Text(stringResource(R.string.assistant_page_gradient_intensity)) },
                    description = {
                        Text(stringResource(R.string.assistant_page_gradient_intensity_desc))
                    },
                ) {
                    val intensity = assistant.gradientBackgroundIntensity.coerceIn(0.5f, 1.5f)
                    Slider(
                        value = intensity,
                        enabled = !globalBackgroundOverridesChat,
                        onValueChange = { value ->
                            onUpdate(assistant.copy(gradientBackgroundIntensity = value))
                        },
                        valueRange = 0.5f..1.5f,
                        steps = 9,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = stringResource(
                            R.string.assistant_page_gradient_scale_value,
                            (intensity * 100).roundToInt(),
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FormItem(
                    modifier = Modifier.padding(8.dp),
                    label = { Text(stringResource(R.string.assistant_page_gradient_animation)) },
                    description = { Text(stringResource(R.string.assistant_page_gradient_animation_desc)) },
                    tail = {
                        Switch(
                            checked = assistant.gradientBackgroundAnimation,
                            enabled = !globalBackgroundOverridesChat,
                            onCheckedChange = { enabled ->
                                onUpdate(assistant.copy(gradientBackgroundAnimation = enabled))
                            },
                        )
                    },
                )
                FormItem(
                    modifier = Modifier.padding(8.dp),
                    label = { Text(stringResource(R.string.assistant_page_gradient_blob_count)) },
                    description = {
                        Text(stringResource(R.string.assistant_page_gradient_blob_count_desc))
                    },
                ) {
                    val blobCount = assistant.gradientBackgroundBlobCount.coerceIn(0, 4)
                    Slider(
                        value = blobCount.toFloat(),
                        enabled = !globalBackgroundOverridesChat,
                        onValueChange = { value ->
                            onUpdate(assistant.copy(gradientBackgroundBlobCount = value.roundToInt()))
                        },
                        valueRange = 0f..4f,
                        steps = 3,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = stringResource(R.string.assistant_page_gradient_blob_count_value, blobCount),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FormItem(
                    modifier = Modifier.padding(8.dp),
                    label = { Text(stringResource(R.string.assistant_page_gradient_softness)) },
                    description = {
                        Text(stringResource(R.string.assistant_page_gradient_softness_desc))
                    },
                ) {
                    val softness = assistant.gradientBackgroundSoftness.coerceIn(0.55f, 1.5f)
                    Slider(
                        value = softness,
                        enabled = !globalBackgroundOverridesChat,
                        onValueChange = { value ->
                            onUpdate(assistant.copy(gradientBackgroundSoftness = value))
                        },
                        valueRange = 0.55f..1.5f,
                        steps = 8,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = stringResource(
                            R.string.assistant_page_gradient_softness_value,
                            (softness * 100).roundToInt(),
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FormItem(
                    modifier = Modifier.padding(8.dp),
                    label = { Text(stringResource(R.string.assistant_page_gradient_angle)) },
                    description = { Text(stringResource(R.string.assistant_page_gradient_angle_desc)) },
                ) {
                    val angle = assistant.gradientBackgroundAngle.coerceIn(-180f, 180f)
                    Slider(
                        value = angle,
                        enabled = !globalBackgroundOverridesChat,
                        onValueChange = { value ->
                            onUpdate(assistant.copy(gradientBackgroundAngle = value))
                        },
                        valueRange = -180f..180f,
                        steps = 11,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = stringResource(
                            R.string.assistant_page_gradient_angle_value,
                            angle.roundToInt(),
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FormItem(
                    modifier = Modifier.padding(8.dp),
                    label = { Text(stringResource(R.string.assistant_page_gradient_vignette)) },
                    description = { Text(stringResource(R.string.assistant_page_gradient_vignette_desc)) },
                ) {
                    val vignette = assistant.gradientBackgroundVignette.coerceIn(0f, 1f)
                    Slider(
                        value = vignette,
                        enabled = !globalBackgroundOverridesChat,
                        onValueChange = { value ->
                            onUpdate(assistant.copy(gradientBackgroundVignette = value))
                        },
                        valueRange = 0f..1f,
                        steps = 10,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = stringResource(
                            R.string.assistant_page_gradient_vignette_value,
                            (vignette * 100).roundToInt(),
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (assistant.gradientBackgroundAnimation) {
                    FormItem(
                        modifier = Modifier.padding(8.dp),
                        label = { Text(stringResource(R.string.assistant_page_gradient_speed)) },
                        description = { Text(stringResource(R.string.assistant_page_gradient_speed_desc)) },
                    ) {
                        Slider(
                            value = assistant.gradientBackgroundSpeed.coerceIn(0.5f, 2f),
                            enabled = !globalBackgroundOverridesChat,
                            onValueChange = { speed ->
                                onUpdate(assistant.copy(gradientBackgroundSpeed = speed))
                            },
                            valueRange = 0.5f..2f,
                            steps = 5,
                        )
                    }
                    FormItem(
                        modifier = Modifier.padding(8.dp),
                        label = { Text(stringResource(R.string.assistant_page_gradient_motion)) },
                        description = {
                            Text(stringResource(R.string.assistant_page_gradient_motion_desc))
                        },
                    ) {
                        val motionScale = assistant.gradientBackgroundMotionScale
                            .coerceIn(0.25f, 1.5f)
                        Slider(
                            value = motionScale,
                            enabled = !globalBackgroundOverridesChat,
                            onValueChange = { value ->
                                onUpdate(assistant.copy(gradientBackgroundMotionScale = value))
                            },
                            valueRange = 0.25f..1.5f,
                            steps = 4,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            text = stringResource(
                                R.string.assistant_page_gradient_scale_value,
                                (motionScale * 100).roundToInt(),
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (!assistant.useGradientBackground) {
                HorizontalDivider()

                BackgroundPicker(
                    modifier = Modifier.padding(8.dp),
                    background = assistant.background,
                    backgroundOpacity = assistant.backgroundOpacity,
                    enabled = !globalBackgroundOverridesChat,
                    onUpdate = { background ->
                        onUpdate(
                            assistant.selectBackground(background)
                        )
                    }
                )
            }

            if (assistant.useGradientBackground || !assistant.background.isNullOrBlank()) {
                val backgroundOpacity = assistant.backgroundOpacity.coerceIn(0f, 1f)
                var opacitySlider by remember(assistant.id, assistant.background, assistant.useGradientBackground) {
                    mutableFloatStateOf(backgroundOpacity)
                }
                var opacityDragging by remember { mutableStateOf(false) }
                LaunchedEffect(backgroundOpacity) {
                    if (!opacityDragging) opacitySlider = backgroundOpacity
                }
                HorizontalDivider()
                FormItem(
                    modifier = Modifier.padding(8.dp),
                    label = {
                        Text(stringResource(R.string.assistant_page_background_opacity))
                    },
                    description = {
                        Text(
                            stringResource(
                                if (assistant.useGradientBackground) {
                                    R.string.assistant_page_gradient_background_opacity_desc
                                } else {
                                    R.string.assistant_page_background_opacity_desc
                                }
                            )
                        )
                    }
                ) {
                    Slider(
                        value = opacitySlider,
                        enabled = !globalBackgroundOverridesChat,
                        onValueChange = {
                            opacityDragging = true
                            opacitySlider = it
                        },
                        onValueChangeFinished = {
                            opacityDragging = false
                            onUpdate(
                                assistant.copy(
                                    backgroundOpacity = opacitySlider.toFixed(2)
                                        .toFloatOrNull()?.coerceIn(0f, 1f) ?: 1.0f
                                )
                            )
                        },
                        valueRange = 0f..1f,
                        steps = 19,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = stringResource(
                            R.string.assistant_page_background_opacity_value,
                            (opacitySlider * 100).roundToInt()
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.75f),
                    )
                }

                if (!assistant.useGradientBackground && !assistant.background.isNullOrBlank()) {
                val backgroundBlurRadius = assistant.backgroundBlurRadius.coerceIn(0f, 40f)
                var blurSlider by remember(assistant.id, assistant.background) {
                    mutableFloatStateOf(backgroundBlurRadius)
                }
                var blurDragging by remember { mutableStateOf(false) }
                LaunchedEffect(backgroundBlurRadius) {
                    if (!blurDragging) blurSlider = backgroundBlurRadius
                }
                HorizontalDivider()
                FormItem(
                    modifier = Modifier.padding(8.dp),
                    label = {
                        Text(stringResource(R.string.assistant_page_background_blur))
                    },
                    description = {
                        Text(stringResource(R.string.assistant_page_background_blur_desc))
                    }
                ) {
                    Slider(
                        value = blurSlider,
                        enabled = !globalBackgroundOverridesChat,
                        onValueChange = {
                            blurDragging = true
                            blurSlider = it
                        },
                        onValueChangeFinished = {
                            blurDragging = false
                            onUpdate(
                                assistant.copy(
                                    backgroundBlurRadius = blurSlider.toFixed(1)
                                        .toFloatOrNull()?.coerceIn(0f, 40f) ?: 0f
                                )
                            )
                        },
                        valueRange = 0f..40f,
                        steps = 19,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = if (blurSlider == 0f) {
                            stringResource(R.string.assistant_page_background_blur_off)
                        } else {
                            stringResource(
                                R.string.assistant_page_background_blur_value,
                                blurSlider.roundToInt()
                            )
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.75f),
                    )
                }
                }
            }
        }
    }
}

@Composable
private fun GradientCustomColorEditor(
    assistant: Assistant,
    enabled: Boolean,
    onColorsUpdate: (
        (GradientBackgroundCustomColors) -> GradientBackgroundCustomColors,
    ) -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val dark = LocalDarkMode.current
    val defaultPalette = remember(
        colorScheme,
        dark,
        assistant.gradientBackgroundFollowTheme,
        assistant.gradientBackgroundPreset,
    ) {
        createGradientBackgroundPalette(
            colorScheme = colorScheme,
            dark = dark,
            followTheme = assistant.gradientBackgroundFollowTheme,
            preset = assistant.gradientBackgroundPreset,
        )
    }
    val palette = remember(
        colorScheme,
        dark,
        assistant.gradientBackgroundFollowTheme,
        assistant.gradientBackgroundPreset,
        assistant.gradientBackgroundCustomColors,
    ) {
        createGradientBackgroundPalette(
            colorScheme = colorScheme,
            dark = dark,
            followTheme = assistant.gradientBackgroundFollowTheme,
            preset = assistant.gradientBackgroundPreset,
            customColors = assistant.gradientBackgroundCustomColors,
        )
    }
    var selectedBlob by remember(assistant.id) { mutableStateOf(false) }
    var selectedIndex by remember(assistant.id) { mutableStateOf(0) }
    val colors = if (selectedBlob) palette.blobs.map { it.color } else palette.baseStops.map { it.second }
    val selectedColor = colors.getOrElse(selectedIndex) { colors.first() }
    val hasCustomColors = assistant.gradientBackgroundCustomColors.baseColors.isNotEmpty() ||
        assistant.gradientBackgroundCustomColors.blobColors.isNotEmpty()

    FormItem(
        modifier = Modifier.padding(8.dp),
        label = { Text(stringResource(R.string.assistant_page_gradient_custom_colors)) },
        description = { Text(stringResource(R.string.assistant_page_gradient_custom_colors_desc)) },
    ) {
        Text(
            text = stringResource(R.string.assistant_page_gradient_base_colors),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        GradientColorSwatches(
            colors = palette.baseStops.map { it.second },
            selectedIndex = selectedIndex.takeIf { !selectedBlob },
            enabled = enabled,
            onSelected = { index ->
                selectedBlob = false
                selectedIndex = index
            },
        )
        Text(
            text = stringResource(R.string.assistant_page_gradient_blob_colors),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        GradientColorSwatches(
            colors = palette.blobs.map { it.color },
            selectedIndex = selectedIndex.takeIf { selectedBlob },
            enabled = enabled,
            onSelected = { index ->
                selectedBlob = true
                selectedIndex = index
            },
        )
        HctColorPicker(
            color = selectedColor,
            enabled = enabled,
            onColorChange = { nextColor ->
                onColorsUpdate { currentColors ->
                    updateGradientCustomColor(
                        customColors = currentColors,
                        defaultPalette = defaultPalette,
                        editingBlob = selectedBlob,
                        selectedIndex = selectedIndex,
                        color = nextColor,
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        androidx.compose.material3.TextButton(
            onClick = {
                onColorsUpdate { GradientBackgroundCustomColors() }
            },
            enabled = enabled && hasCustomColors,
        ) {
            Text(stringResource(R.string.assistant_page_gradient_reset_colors))
        }
    }
}

@Composable
private fun GradientColorSwatches(
    colors: List<Color>,
    selectedIndex: Int?,
    enabled: Boolean,
    onSelected: (Int) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        colors.forEachIndexed { index, color ->
            Surface(
                onClick = { onSelected(index) },
                enabled = enabled,
                modifier = Modifier.size(38.dp),
                shape = MaterialTheme.shapes.small,
                color = color,
                border = BorderStroke(
                    width = if (selectedIndex == index) 2.dp else 1.dp,
                    color = if (selectedIndex == index) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outlineVariant
                    },
                ),
                content = {},
            )
        }
    }
}

@Composable
private fun GradientPresetSelector(
    selected: GradientBackgroundPreset,
    enabled: Boolean,
    onSelected: (GradientBackgroundPreset) -> Unit,
) {
    val options = listOf(
        GradientBackgroundPreset.CLASSIC to stringResource(R.string.assistant_page_gradient_preset_classic),
        GradientBackgroundPreset.AURORA to stringResource(R.string.assistant_page_gradient_preset_aurora),
        GradientBackgroundPreset.SUNSET to stringResource(R.string.assistant_page_gradient_preset_sunset),
        GradientBackgroundPreset.MONOCHROME to stringResource(R.string.assistant_page_gradient_preset_monochrome),
    )
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        options.chunked(2).forEach { rowOptions ->
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                rowOptions.forEachIndexed { index, (preset, label) ->
                    SegmentedButton(
                        selected = selected == preset,
                        onClick = { onSelected(preset) },
                        enabled = enabled,
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = rowOptions.size,
                        ),
                        label = {
                            Text(
                                text = label,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun KnowledgeBaseBinding(
    assistant: Assistant,
    knowledgeBases: List<KnowledgeBaseEntity>,
    onUpdate: (Assistant) -> Unit,
) {
    var showSheet by remember { mutableStateOf(false) }
    val selected = knowledgeBases.filter { it.id in assistant.knowledgeBaseIds.map { id -> id.toString() } }
    FormItem(
        modifier = Modifier.padding(8.dp),
        label = { Text(stringResource(R.string.assistant_page_knowledge_bases)) },
        description = { Text(stringResource(R.string.assistant_page_knowledge_bases_desc)) },
    ) {
        androidx.compose.material3.TextButton(
            onClick = { showSheet = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = selected.joinToString(", ") { it.name }.ifBlank {
                    stringResource(R.string.assistant_page_knowledge_bases_unbound)
                },
                maxLines = 2,
            )
        }
    }

    if (showSheet) {
        ModalBottomSheet(onDismissRequest = { showSheet = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp),
            ) {
                if (knowledgeBases.isEmpty()) {
                    Text(
                        text = stringResource(R.string.assistant_page_knowledge_bases_empty),
                        modifier = Modifier.padding(24.dp),
                    )
                } else {
                    knowledgeBases.forEach { base ->
                        val checked = base.id in assistant.knowledgeBaseIds.map { it.toString() }
                        ListItem(
                            headlineContent = { Text(base.name) },
                            supportingContent = { Text(base.description.ifBlank { stringResource(R.string.assistant_page_knowledge_bases_desc) }) },
                            trailingContent = {
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = { isChecked ->
                                        val ids = assistant.knowledgeBaseIds.toMutableSet()
                                        val id = Uuid.parse(base.id)
                                        if (isChecked) ids += id else ids -= id
                                        onUpdate(assistant.copy(knowledgeBaseIds = ids))
                                    },
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}
