package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import me.rerere.rikkahub.ui.components.ui.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import me.rerere.rikkahub.data.model.selectGlobalBackground
import me.rerere.rikkahub.data.model.ChatComposerMaterial
import me.rerere.rikkahub.data.model.chatComposerMaterial
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import me.rerere.rikkahub.ui.components.ui.finiteAppearanceValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.InformationCircle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.AdvancedAppearanceSetting
import me.rerere.rikkahub.data.datastore.AppearanceColorStyle
import me.rerere.rikkahub.data.datastore.BackgroundSurfaceStyle
import me.rerere.rikkahub.data.datastore.ChatBubbleStyle
import me.rerere.rikkahub.data.datastore.GradientRendererMode
import me.rerere.rikkahub.data.datastore.MAX_CHAT_PARAGRAPH_SPACING_RATIO
import me.rerere.rikkahub.data.datastore.MAX_CHAT_TEXT_LINE_HEIGHT_RATIO
import me.rerere.rikkahub.data.datastore.MAX_GLOBAL_BACKGROUND_BLUR_RADIUS
import me.rerere.rikkahub.data.datastore.MAX_LIQUID_GLASS_BLUR_RADIUS
import me.rerere.rikkahub.data.datastore.MAX_NAVIGATION_GLASS_BLUR_RADIUS
import me.rerere.rikkahub.data.datastore.MIN_CHAT_PARAGRAPH_SPACING_RATIO
import me.rerere.rikkahub.data.datastore.MIN_CHAT_TEXT_LINE_HEIGHT_RATIO
import me.rerere.rikkahub.data.datastore.MIN_GLOBAL_BACKGROUND_BLUR_RADIUS
import me.rerere.rikkahub.data.datastore.MIN_LIQUID_GLASS_BLUR_RADIUS
import me.rerere.rikkahub.data.datastore.MIN_NAVIGATION_GLASS_BLUR_RADIUS
import me.rerere.rikkahub.data.datastore.RichContentStyle
import me.rerere.rikkahub.data.datastore.configuredAssistantBackgroundCount
import me.rerere.rikkahub.data.datastore.hasActiveChatBackground
import me.rerere.rikkahub.data.datastore.isGlobalBackgroundActive
import me.rerere.rikkahub.data.datastore.isAutoAccentActive
import me.rerere.rikkahub.data.datastore.isGlobalBackgroundAppliedToChat
import me.rerere.rikkahub.data.datastore.resolveChatBackground
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.AppearanceAlertDialog
import me.rerere.rikkahub.ui.components.ui.AdvancedAppearanceSupport
import me.rerere.rikkahub.ui.components.ui.AGSL_GRADIENT_MIN_SDK
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.LocalAdvancedAppearanceCapabilities
import me.rerere.rikkahub.ui.pages.assistant.detail.BackgroundPicker
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel
import kotlin.math.abs
import kotlin.math.roundToInt

internal enum class AppearancePerformanceImpact {
    LOW,
    MEDIUM,
    HIGH,
}

private val LocalAppearanceSaveFailed = androidx.compose.runtime.compositionLocalOf { false }

internal fun estimateAppearancePerformanceImpact(
    supportsRealtimeBlur: Boolean,
    globalBackgroundActive: Boolean,
    globalBackgroundBlurred: Boolean,
    gradientBackgroundAnimated: Boolean,
    inputBlurActive: Boolean,
    topBarBlurActive: Boolean,
    navigationGlassActive: Boolean,
    dockGlassActive: Boolean,
    bubbleGlassActive: Boolean,
    richContentTranslucent: Boolean,
): AppearancePerformanceImpact {
    var score = 0
    if (globalBackgroundActive) score += 1
    if (gradientBackgroundAnimated) score += 2
    if (richContentTranslucent) score += 1
    if (supportsRealtimeBlur) {
        if (globalBackgroundBlurred) score += 2
        if (inputBlurActive) score += 1
        if (topBarBlurActive) score += 1
        if (navigationGlassActive) score += 1
        if (dockGlassActive) score += 1
        if (bubbleGlassActive) score += 1
    }
    return when (score) {
        in 0..2 -> AppearancePerformanceImpact.LOW
        in 3..5 -> AppearancePerformanceImpact.MEDIUM
        else -> AppearancePerformanceImpact.HIGH
    }
}

@Composable
fun SettingPreferencesAdvancedAppearancePage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val saveError by vm.appearanceSaveError.collectAsStateWithLifecycle()
    val saveErrorMessage = saveError
    if (saveErrorMessage != null) AppearanceAlertDialog(
        onDismissRequest = { vm.appearanceSaveError.value = null },
        title = { Text("设置未保存") }, text = { Text(saveErrorMessage) },
        confirmButton = { TextButton(onClick = { vm.appearanceSaveError.value = null }) { Text("知道了") } },
    )
    val appearance = settings.advancedAppearanceSetting
    val composerMaterial = settings.chatComposerMaterial()
    val appearanceCapabilities = LocalAdvancedAppearanceCapabilities.current
    val blurSupported = appearanceCapabilities.supportsRealtimeBlur
    val reducedEffects = appearanceCapabilities.usesReducedEffects
    val backgroundBlurMax = if (blurSupported) {
        appearanceCapabilities.maxBackgroundBlurRadius
    } else {
        MAX_GLOBAL_BACKGROUND_BLUR_RADIUS
    }
    val liveBlurMax = if (blurSupported) {
        minOf(MAX_LIQUID_GLASS_BLUR_RADIUS, appearanceCapabilities.maxLiveBlurRadius)
    } else {
        MAX_LIQUID_GLASS_BLUR_RADIUS
    }
    val navigationBlurMax = if (blurSupported) {
        minOf(MAX_NAVIGATION_GLASS_BLUR_RADIUS, appearanceCapabilities.maxLiveBlurRadius)
    } else {
        MAX_NAVIGATION_GLASS_BLUR_RADIUS
    }
    val topBarBlurMax = if (blurSupported) {
        minOf(MAX_GLOBAL_BACKGROUND_BLUR_RADIUS, appearanceCapabilities.maxLiveBlurRadius)
    } else {
        MAX_GLOBAL_BACKGROUND_BLUR_RADIUS
    }
    val chatBackgroundActive = settings.hasActiveChatBackground()
    val effectivePageSurfaceStyle = appearanceCapabilities.effectiveSurfaceStyle(
        appearance.pageSurfaceStyle
    )
    val effectiveOverlaySurfaceStyle = appearanceCapabilities.effectiveSurfaceStyle(
        appearance.overlaySurfaceStyle
    )
    val effectiveNavigationSurfaceStyle = appearanceCapabilities.effectiveSurfaceStyle(
        appearance.navigationSurfaceStyle
    )
    val globalPageBackgroundVisible = settings.isGlobalBackgroundActive() &&
        effectivePageSurfaceStyle != BackgroundSurfaceStyle.OPAQUE
    val topBarBackgroundAvailable = chatBackgroundActive || globalPageBackgroundVisible
    val overlayBackgroundAvailable = canAdjustOverlayEffects(settings)
    val bubbleStylesAvailable = settings.displaySetting.showAssistantBubble &&
        chatBackgroundActive && appearance.enableBubblePerformanceEffects
    val currentChatBackground = settings.resolveChatBackground()
    val performanceImpact = estimateAppearancePerformanceImpact(
        supportsRealtimeBlur = blurSupported,
        globalBackgroundActive = globalPageBackgroundVisible,
        globalBackgroundBlurred = globalPageBackgroundVisible && when (effectivePageSurfaceStyle) {
            BackgroundSurfaceStyle.FROSTED -> appearance.globalBackgroundBlurRadius > 0f
            BackgroundSurfaceStyle.LIQUID_GLASS -> appearance.pageLiquidGlassBlurRadius > 0f
            else -> false
        },
        gradientBackgroundAnimated = currentChatBackground.useGradientBackground &&
            currentChatBackground.gradientAnimation &&
            appearance.enableGradientPerformanceEffects,
        inputBlurActive = appearance.enableInputPerformanceEffects &&
            composerMaterial != ChatComposerMaterial.TRANSLUCENT && settings.displaySetting.inputBlurRadius > 0f,
        topBarBlurActive = topBarBackgroundAvailable &&
            appearance.enableTopBarPerformanceEffects &&
            settings.displaySetting.enableTopBarBlur && settings.displaySetting.topBarBlurRadius > 0f,
        navigationGlassActive = chatBackgroundActive &&
            appearance.enableNavigationPerformanceEffects &&
            appearance.enableNavigationGlass &&
            effectiveNavigationSurfaceStyle != BackgroundSurfaceStyle.OPAQUE,
        dockGlassActive = false,
        bubbleGlassActive = bubbleStylesAvailable &&
            appearanceCapabilities.effectiveBubbleStyle(
                appearance.chatBubbleStyle
            ) != ChatBubbleStyle.OUTLINED,
        richContentTranslucent = appearance.enableRichContentPerformanceEffects &&
            appearance.richContentStyle == RichContentStyle.TRANSLUCENT,
    )
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val extractingAccent = me.rerere.rikkahub.ui.theme.BackgroundAccentState.loading
    var showGlobalChatOverrideConfirmation by remember { mutableStateOf(false) }
    val configuredAssistantBackgroundCount = settings.configuredAssistantBackgroundCount()

    fun updateAppearance(transform: AdvancedAppearanceSetting.() -> AdvancedAppearanceSetting) {
        vm.updateAdvancedAppearance { current ->
            current.transform()
        }
    }

    fun updateDisplayAppearance(
        transform: me.rerere.rikkahub.data.datastore.DisplaySetting.() ->
            me.rerere.rikkahub.data.datastore.DisplaySetting,
    ) {
        vm.updateDisplaySetting { current -> current.transform() }
    }


    if (showGlobalChatOverrideConfirmation) {
        AppearanceAlertDialog(
            onDismissRequest = { showGlobalChatOverrideConfirmation = false },
            title = {
                Text(
                    stringResource(
                        R.string.setting_advanced_appearance_global_background_apply_chat_confirm_title
                    )
                )
            },
            text = {
                Text(
                    stringResource(
                        R.string.setting_advanced_appearance_global_background_apply_chat_confirm_message,
                        configuredAssistantBackgroundCount,
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showGlobalChatOverrideConfirmation = false
                        updateAppearance { copy(applyGlobalBackgroundToChat = true) }
                    },
                ) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showGlobalChatOverrideConfirmation = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    androidx.compose.runtime.CompositionLocalProvider(LocalAppearanceSaveFailed provides (saveError != null)) {
    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_advanced_appearance_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.scaffoldContainerColor,
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                AppearanceNoticeCard(
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }

            if (appearanceCapabilities.blurSupport != AdvancedAppearanceSupport.FULL) {
                item {
                    CompatibilityNoticeCard(
                        reduced = reducedEffects,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
            }

            item {
                AppearancePerformanceCard(
                    capabilities = appearanceCapabilities,
                    appearance = appearance,
                    impact = performanceImpact,
                    update = ::updateAppearance,
                )
            }

            item { LiquidGlassSettingsCard(appearance) { transform -> vm.updateAdvancedAppearance(transform) } }

            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text("聊天输入区（输入框与 Dock）") },
                ) {
                    item(
                        headlineContent = { Text("表面样式") },
                        supportingContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                                    ChatComposerMaterial.entries.forEachIndexed { index, material ->
                                        SegmentedButton(
                                            selected = composerMaterial == material,
                                            enabled = material == ChatComposerMaterial.TRANSLUCENT || blurSupported,
                                            shape = SegmentedButtonDefaults.itemShape(index, ChatComposerMaterial.entries.size),
                                            onClick = { vm.updateComposerMaterial(material) },
                                        ) {
                                            Text(when (material) {
                                                ChatComposerMaterial.TRANSLUCENT -> "半透明"
                                                ChatComposerMaterial.FROSTED -> "磨砂"
                                                ChatComposerMaterial.LIQUID_GLASS -> "液态玻璃"
                                            })
                                        }
                                    }
                                }
                                Text("输入框、附件和 Dock 共用一个表面，透出后方消息和背景。玻璃材质参数统一在上方“液态玻璃材质”调整。")
                                AppearanceCompatibilityWarning(
                                    blurSupported = blurSupported,
                                    reducedEffects = reducedEffects,
                                )
                                if (!appearance.enableInputPerformanceEffects) Text("动态效果已暂停，当前仅显示设定的不透明度。", color = Color(0xFFF9A825))
                            }
                        },
                    )
                    item(
                        headlineContent = { Text("输入区动态效果") },
                        supportingContent = { Text("控制此处的模糊与折射，关闭后仍保留不透明度。持续实时模糊可能增加 GPU 占用与耗电。") },
                        trailingContent = {
                            Switch(
                                checked = appearance.enableInputPerformanceEffects,
                                enabled = blurSupported && composerMaterial != ChatComposerMaterial.TRANSLUCENT,
                                onCheckedChange = { enabled ->
                                    updateAppearance { copy(enableInputPerformanceEffects = enabled) }
                                },
                            )
                        },
                    )
                    if (composerMaterial != ChatComposerMaterial.TRANSLUCENT) {
                        item(
                            headlineContent = {
                                Text(stringResource(R.string.setting_display_page_input_blur_radius_title))
                            },
                            supportingContent = {
                                AdvancedAppearanceSlider(
                                    description = "模糊输入区后方的消息和背景，不影响输入文字与按钮。0 表示不模糊。",
                                    value = finiteAppearanceValue(settings.displaySetting.inputBlurRadius, 0f, liveBlurMax, 0f),
                                    valueRange = MIN_LIQUID_GLASS_BLUR_RADIUS..liveBlurMax,
                                    steps = if (reducedEffects) 5 else 11,
                                    enabled = blurSupported &&
                                        appearance.enableInputPerformanceEffects,
                                    valueLabel = { value ->
                                        stringResource(
                                            R.string.setting_display_page_blur_radius_value,
                                            value.roundToInt(),
                                        )
                                    },
                                    onValueChange = { value ->
                                        updateDisplayAppearance { copy(inputBlurRadius = value) }
                                    },
                                )
                            },
                        )
                    }
                    item(
                        headlineContent = {
                            Text(stringResource(R.string.setting_display_page_input_tint_title))
                        },
                        supportingContent = {
                            AdvancedAppearanceSlider(
                                description = "0% 完全透出后方内容，100% 为不透明底色；模糊强度另行控制。关闭动态效果后仍生效。",
                                value = finiteAppearanceValue(settings.displaySetting.inputSurfaceOpacity, 0f, 1f, 1f),
                                valueRange = 0f..1f,
                                steps = 19,
                                enabled = true,
                                valueLabel = { value ->
                                    stringResource(
                                        R.string.setting_display_page_tint_value,
                                        (value * 100).roundToInt(),
                                    )
                                },
                                onValueChange = { value ->
                                    updateDisplayAppearance { copy(inputSurfaceOpacity = value) }
                                },
                            )
                        },
                    )
                }
            }


            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.setting_advanced_appearance_top_bar_section)) },
                ) {
                    item(
                        headlineContent = {
                            Text(stringResource(R.string.setting_display_page_top_bar_blur_title))
                        },
                        supportingContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (topBarBackgroundAvailable) {
                                    Text(stringResource(R.string.setting_display_page_top_bar_blur_desc))
                                } else {
                                    CompatibilityWarningText(
                                        R.string.setting_advanced_appearance_top_bar_requires_background
                                    )
                                }
                                AppearanceCompatibilityWarning(
                                    blurSupported = blurSupported,
                                    reducedEffects = reducedEffects,
                                )
                                PerformanceSettingWarning(
                                    effectsEnabled = appearance.enableTopBarPerformanceEffects
                                )
                            }
                        },
                        trailingContent = {
                            Switch(
                                checked = settings.displaySetting.enableTopBarBlur,
                                enabled = blurSupported &&
                                    topBarBackgroundAvailable &&
                                    appearance.enableTopBarPerformanceEffects,
                                onCheckedChange = { enabled ->
                                    updateDisplayAppearance { copy(enableTopBarBlur = enabled) }
                                },
                            )
                        },
                    )
                    if (settings.displaySetting.enableTopBarBlur) {
                        item(
                            headlineContent = {
                                Text(stringResource(R.string.setting_display_page_top_bar_blur_radius_title))
                            },
                            supportingContent = {
                                AdvancedAppearanceSlider(
                                    description = stringResource(
                                        R.string.setting_display_page_top_bar_blur_radius_desc
                                    ),
                                    value = settings.displaySetting.topBarBlurRadius.coerceIn(
                                        MIN_GLOBAL_BACKGROUND_BLUR_RADIUS,
                                        topBarBlurMax,
                                    ),
                                    valueRange = MIN_GLOBAL_BACKGROUND_BLUR_RADIUS..
                                        topBarBlurMax,
                                    steps = if (reducedEffects) 3 else 17,
                                    enabled = blurSupported &&
                                        topBarBackgroundAvailable &&
                                        appearance.enableTopBarPerformanceEffects,
                                    valueLabel = { value ->
                                        stringResource(
                                            R.string.setting_display_page_blur_radius_value,
                                            value.roundToInt(),
                                        )
                                    },
                                    onValueChange = { value ->
                                        updateDisplayAppearance { copy(topBarBlurRadius = value) }
                                    },
                                )
                            },
                        )
                    }
                    item(
                        headlineContent = {
                            Text(stringResource(R.string.setting_display_page_top_bar_tint_title))
                        },
                        supportingContent = {
                            AdvancedAppearanceSlider(
                                description = stringResource(R.string.setting_display_page_top_bar_tint_desc),
                                value = settings.displaySetting.topBarSurfaceOpacity.coerceIn(0.35f, 1f),
                                valueRange = 0.35f..1f,
                                steps = 12,
                                enabled = topBarBackgroundAvailable &&
                                    appearance.enableTopBarPerformanceEffects,
                                valueLabel = { value ->
                                    stringResource(
                                        R.string.setting_display_page_tint_value,
                                        (value * 100).roundToInt(),
                                    )
                                },
                                onValueChange = { value ->
                                    updateDisplayAppearance { copy(topBarSurfaceOpacity = value) }
                                },
                            )
                        },
                    )
                }
            }

            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.setting_advanced_appearance_global_background_section)) },
                ) {
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_advanced_appearance_preview_background)) },
                        supportingContent = { Text(stringResource(R.string.setting_advanced_appearance_preview_background_desc)) },
                        trailingContent = {
                            Switch(
                                checked = appearance.applyGlobalBackgroundToFullscreenPreview,
                                enabled = settings.isGlobalBackgroundActive(),
                                onCheckedChange = { enabled -> updateAppearance { copy(applyGlobalBackgroundToFullscreenPreview = enabled) } },
                            )
                        },
                    )
                    item(
                        headlineContent = {
                            Text(stringResource(R.string.setting_advanced_appearance_global_background_enabled))
                        },
                        supportingContent = {
                            Text(stringResource(R.string.setting_advanced_appearance_global_background_enabled_desc))
                        },
                        trailingContent = {
                            Switch(
                                checked = appearance.enableGlobalBackground,
                                onCheckedChange = { enabled ->
                                    updateAppearance {
                                        copy(
                                            enableGlobalBackground = enabled,
                                            applyGlobalBackgroundToChat = false,
                                            pageSurfaceStyle = if (enabled && pageSurfaceStyle == BackgroundSurfaceStyle.OPAQUE) BackgroundSurfaceStyle.TRANSLUCENT else pageSurfaceStyle,
                                        )
                                    }
                                },
                            )
                        },
                    )
                    item(
                        headlineContent = {
                            Text(
                                stringResource(
                                    R.string.setting_advanced_appearance_global_background_apply_chat
                                )
                            )
                        },
                        supportingContent = {
                            Text(
                                if (settings.isGlobalBackgroundActive()) {
                                    stringResource(
                                        R.string.setting_advanced_appearance_global_background_apply_chat_desc
                                    )
                                } else {
                                    stringResource(
                                        R.string.setting_advanced_appearance_global_background_apply_chat_requires_background
                                    )
                                }
                            )
                        },
                        trailingContent = {
                            Switch(
                                checked = appearance.applyGlobalBackgroundToChat,
                                enabled = settings.isGlobalBackgroundActive(),
                                onCheckedChange = { enabled ->
                                    when {
                                        !enabled -> updateAppearance {
                                            copy(applyGlobalBackgroundToChat = false)
                                        }

                                        configuredAssistantBackgroundCount > 0 -> {
                                            showGlobalChatOverrideConfirmation = true
                                        }

                                        else -> updateAppearance {
                                            copy(applyGlobalBackgroundToChat = true)
                                        }
                                    }
                                },
                            )
                        },
                    )
                }
            }

            if (appearance.enableGlobalBackground) {
                item {
                    Card(
                        modifier = Modifier.padding(horizontal = 8.dp),
                        colors = CustomColors.cardColorsOnSurfaceContainer,
                    ) {
                        BackgroundPicker(
                            modifier = Modifier.padding(12.dp),
                            background = appearance.globalBackground,
                            backgroundOpacity = appearance.globalBackgroundOpacity,
                            label = stringResource(R.string.setting_advanced_appearance_global_background_image),
                            description = stringResource(R.string.setting_advanced_appearance_global_background_image_desc),
                            onUpdate = { background ->
                                updateAppearance {
                                    selectGlobalBackground(background)
                                }
                            },
                        )
                    }
                }

                if (!appearance.globalBackground.isNullOrBlank()) {
                    item {
                        CardGroup(
                            modifier = Modifier.padding(horizontal = 8.dp),
                        ) {
                            item(
                                headlineContent = {
                                    Text(stringResource(R.string.setting_advanced_appearance_page_surface_style))
                                },
                                supportingContent = {
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(stringResource(R.string.setting_advanced_appearance_page_surface_style_desc))
                                        BackgroundSurfaceStyleSelector(
                                            selected = effectivePageSurfaceStyle,
                                            isSupported = appearanceCapabilities::supportsSurfaceStyle,
                                            onSelected = { style ->
                                                updateAppearance { copy(pageSurfaceStyle = style) }
                                            },
                                        )
                                        SurfaceStyleCompatibilityWarning(
                                            blurSupported = blurSupported,
                                            reducedEffects = reducedEffects,
                                        )
                                    }
                                },
                            )
                            item(
                                headlineContent = {
                                    Text(stringResource(R.string.setting_advanced_appearance_background_opacity))
                                },
                                supportingContent = {
                                    AdvancedAppearanceSlider(
                                        description = stringResource(R.string.setting_advanced_appearance_background_opacity_desc),
                                        value = appearance.globalBackgroundOpacity.coerceIn(0.2f, 1f),
                                        enabled = globalPageBackgroundVisible || settings.isGlobalBackgroundAppliedToChat(),
                                        valueRange = 0.2f..1f,
                                        steps = 15,
                                        valueLabel = { value ->
                                            stringResource(
                                                R.string.setting_advanced_appearance_background_opacity_value,
                                                (value * 100).roundToInt(),
                                            )
                                        },
                                        onValueChange = { value ->
                                            updateAppearance { copy(globalBackgroundOpacity = value) }
                                        },
                                    )
                                },
                            )
                            if (effectivePageSurfaceStyle == BackgroundSurfaceStyle.FROSTED) {
                                item(
                                    headlineContent = {
                                        Text(stringResource(R.string.setting_advanced_appearance_background_blur))
                                    },
                                    supportingContent = {
                                        AdvancedAppearanceSlider(
                                            description = stringResource(R.string.setting_advanced_appearance_background_blur_desc),
                                            value = appearance.globalBackgroundBlurRadius.coerceIn(
                                                MIN_GLOBAL_BACKGROUND_BLUR_RADIUS,
                                                backgroundBlurMax,
                                            ),
                                            valueRange = MIN_GLOBAL_BACKGROUND_BLUR_RADIUS..backgroundBlurMax,
                                            steps = if (reducedEffects) 7 else 17,
                                            enabled = blurSupported,
                                            valueLabel = { value ->
                                                stringResource(
                                                    R.string.setting_advanced_appearance_background_blur_value,
                                                    value.roundToInt(),
                                                )
                                            },
                                            onValueChange = { value ->
                                                updateAppearance { copy(globalBackgroundBlurRadius = value) }
                                            },
                                        )
                                    },
                                )
                            }
                            if (effectivePageSurfaceStyle == BackgroundSurfaceStyle.LIQUID_GLASS) {
                                item(
                                    headlineContent = {
                                        Text(stringResource(R.string.setting_advanced_appearance_liquid_blur))
                                    },
                                    supportingContent = {
                                        AdvancedAppearanceSlider(
                                            description = stringResource(R.string.setting_advanced_appearance_liquid_blur_desc),
                                            value = appearance.pageLiquidGlassBlurRadius.coerceIn(
                                                MIN_LIQUID_GLASS_BLUR_RADIUS,
                                                liveBlurMax,
                                            ),
                                            valueRange = MIN_LIQUID_GLASS_BLUR_RADIUS..liveBlurMax,
                                            steps = if (reducedEffects) 5 else 11,
                                            enabled = blurSupported,
                                            valueLabel = { value ->
                                                stringResource(
                                                    R.string.setting_advanced_appearance_background_blur_value,
                                                    value.roundToInt(),
                                                )
                                            },
                                            onValueChange = { value ->
                                                updateAppearance { copy(pageLiquidGlassBlurRadius = value) }
                                            },
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
            }

            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text("卡片与列表表面") },
                ) {
                    item(
                        headlineContent = { Text("卡片与列表不透明度") },
                        supportingContent = {
                            Column {
                                AdvancedAppearanceSlider(
                                    description = "同时用于助手背景、渐变背景和可见的全局背景。输入区和顶栏使用各自的不透明度。",
                                    value = appearance.pageSurfaceOpacity, valueRange = .35f..1f, steps = 12,
                                    enabled = canAdjustCardOpacity(settings, appearanceCapabilities),
                                    valueLabel = { "${(it * 100).roundToInt()}%" },
                                    onValueChange = { value -> updateAppearance { copy(pageSurfaceOpacity = value) } },
                                )
                                if (!canAdjustCardOpacity(settings, appearanceCapabilities)) CompatibilityWarningText(R.string.setting_advanced_appearance_requires_background)
                            }
                        },
                    )
                }
            }
            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
title = { Text(stringResource(R.string.setting_advanced_appearance_overlay_surface_section)) },
                ) {
                    item(
                        headlineContent = {
                            Text(stringResource(R.string.setting_advanced_appearance_overlay_surface_style))
                        },
                        supportingContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(stringResource(R.string.setting_advanced_appearance_overlay_surface_style_desc))
                                if (!overlayBackgroundAvailable) {
                                    CompatibilityWarningText(
                                        R.string.setting_advanced_appearance_overlay_requires_background
                                    )
                                }
                                BackgroundSurfaceStyleSelector(
                                    selected = effectiveOverlaySurfaceStyle,
                                    isSupported = appearanceCapabilities::supportsSurfaceStyle,
                                    onSelected = { style ->
                                        updateAppearance { copy(overlaySurfaceStyle = style) }
                                    },
                                )
                                SurfaceStyleCompatibilityWarning(
                                    blurSupported = blurSupported,
                                    reducedEffects = reducedEffects,
                                )
                            }
                        },
                    )
                    if (effectiveOverlaySurfaceStyle != BackgroundSurfaceStyle.OPAQUE) {
                        item(
                            headlineContent = {
                                Text(stringResource(R.string.setting_advanced_appearance_overlay_surface_opacity))
                            },
                            supportingContent = {
                                AdvancedAppearanceSlider(
                                    description = stringResource(
                                        R.string.setting_advanced_appearance_overlay_surface_opacity_desc
                                    ),
                                    value = appearance.overlaySurfaceOpacity.coerceIn(0.35f, 1f),
                                    enabled = overlayBackgroundAvailable,
                                    valueRange = 0.35f..1f,
                                    steps = 12,
                                    valueLabel = { value ->
                                        stringResource(
                                            R.string.setting_advanced_appearance_background_opacity_value,
                                            (value * 100).roundToInt(),
                                        )
                                    },
                                    onValueChange = { value ->
                                        updateAppearance { copy(overlaySurfaceOpacity = value) }
                                    },
                                )
                            },
                        )
                    }
                    if (effectiveOverlaySurfaceStyle == BackgroundSurfaceStyle.FROSTED) {
                        item(
                            headlineContent = {
                                Text(stringResource(R.string.setting_advanced_appearance_overlay_surface_blur))
                            },
                            supportingContent = {
                                AdvancedAppearanceSlider(
                                    description = stringResource(
                                        R.string.setting_advanced_appearance_overlay_surface_blur_desc
                                    ),
                                    value = appearance.overlaySurfaceBlurRadius.coerceIn(
                                        MIN_NAVIGATION_GLASS_BLUR_RADIUS,
                                        navigationBlurMax,
                                    ),
                                    valueRange = MIN_NAVIGATION_GLASS_BLUR_RADIUS..navigationBlurMax,
                                    steps = if (reducedEffects) 3 else 13,
                                    enabled = blurSupported && overlayBackgroundAvailable,
                                    valueLabel = { value ->
                                        stringResource(
                                            R.string.setting_advanced_appearance_background_blur_value,
                                            value.roundToInt(),
                                        )
                                    },
                                    onValueChange = { value ->
                                        updateAppearance { copy(overlaySurfaceBlurRadius = value) }
                                    },
                                )
                            },
                        )
                    }
                    if (effectiveOverlaySurfaceStyle == BackgroundSurfaceStyle.LIQUID_GLASS) {
                        item(
                            headlineContent = {
                                Text(stringResource(R.string.setting_advanced_appearance_liquid_blur))
                            },
                            supportingContent = {
                                AdvancedAppearanceSlider(
                                    description = stringResource(
                                        R.string.setting_advanced_appearance_liquid_blur_desc
                                    ),
                                    value = appearance.overlayLiquidGlassBlurRadius.coerceIn(
                                        MIN_LIQUID_GLASS_BLUR_RADIUS,
                                        liveBlurMax,
                                    ),
                                    valueRange = MIN_LIQUID_GLASS_BLUR_RADIUS..liveBlurMax,
                                    steps = if (reducedEffects) 5 else 11,
                                    enabled = blurSupported && overlayBackgroundAvailable,
                                    valueLabel = { value ->
                                        stringResource(
                                            R.string.setting_advanced_appearance_background_blur_value,
                                            value.roundToInt(),
                                        )
                                    },
                                    onValueChange = { value ->
                                        updateAppearance { copy(overlayLiquidGlassBlurRadius = value) }
                                    },
                                )
                            },
                        )
                    }
                }
            }

            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.setting_advanced_appearance_navigation_glass_section)) },
                ) {
                    item(
                        headlineContent = {
                            Text(stringResource(R.string.setting_advanced_appearance_navigation_glass_enabled))
                        },
                        supportingContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (chatBackgroundActive) {
                                    Text(
                                        stringResource(
                                            R.string.setting_advanced_appearance_navigation_glass_enabled_desc
                                        )
                                    )
                                } else {
                                    CompatibilityWarningText(
                                        R.string.setting_advanced_appearance_requires_background
                                    )
                                }
                                BackgroundSurfaceStyleSelector(
                                    selected = if (appearance.enableNavigationGlass) {
                                        effectiveNavigationSurfaceStyle
                                    } else {
                                        BackgroundSurfaceStyle.OPAQUE
                                    },
                                    enabled = chatBackgroundActive &&
                                        appearance.enableNavigationPerformanceEffects,
                                    isSupported = appearanceCapabilities::supportsSurfaceStyle,
                                    onSelected = { style ->
                                        updateAppearance {
                                            copy(
                                                enableNavigationGlass = style != BackgroundSurfaceStyle.OPAQUE,
                                                navigationSurfaceStyle = if (style == BackgroundSurfaceStyle.OPAQUE) {
                                                    navigationSurfaceStyle
                                                } else {
                                                    style
                                                },
                                            )
                                        }
                                    },
                                )
                                SurfaceStyleCompatibilityWarning(
                                    blurSupported = blurSupported,
                                    reducedEffects = reducedEffects,
                                )
                                PerformanceSettingWarning(
                                    effectsEnabled = appearance.enableNavigationPerformanceEffects
                                )
                            }
                        },
                    )
                    if (appearance.enableNavigationGlass) {
                        item(
                            headlineContent = {
                                Text(stringResource(R.string.setting_advanced_appearance_navigation_glass_opacity))
                            },
                            supportingContent = {
                                AdvancedAppearanceSlider(
                                    description = stringResource(R.string.setting_advanced_appearance_navigation_glass_opacity_desc),
                                    value = appearance.navigationGlassOpacity.coerceIn(0.35f, 0.95f),
                                    valueRange = 0.35f..0.95f,
                                    steps = 11,
                                    enabled = chatBackgroundActive &&
                                        appearance.enableNavigationPerformanceEffects,
                                    valueLabel = { value ->
                                        stringResource(
                                            R.string.setting_advanced_appearance_background_opacity_value,
                                            (value * 100).roundToInt(),
                                        )
                                    },
                                    onValueChange = { value ->
                                        updateAppearance { copy(navigationGlassOpacity = value) }
                                    },
                                )
                            },
                        )
                        if (effectiveNavigationSurfaceStyle == BackgroundSurfaceStyle.FROSTED) item(
                            headlineContent = {
                                Text(stringResource(R.string.setting_advanced_appearance_navigation_glass_blur))
                            },
                            supportingContent = {
                                AdvancedAppearanceSlider(
                                    description = stringResource(R.string.setting_advanced_appearance_navigation_glass_blur_desc),
                                    value = appearance.navigationGlassBlurRadius.coerceIn(
                                        MIN_NAVIGATION_GLASS_BLUR_RADIUS,
                                        navigationBlurMax,
                                    ),
                                    valueRange = MIN_NAVIGATION_GLASS_BLUR_RADIUS..navigationBlurMax,
                                    steps = if (reducedEffects) 3 else 13,
                                    enabled = blurSupported &&
                                        chatBackgroundActive &&
                                        appearance.enableNavigationPerformanceEffects,
                                    valueLabel = { value ->
                                        stringResource(
                                            R.string.setting_advanced_appearance_background_blur_value,
                                            value.roundToInt(),
                                        )
                                    },
                                    onValueChange = { value ->
                                        updateAppearance { copy(navigationGlassBlurRadius = value) }
                                    },
                                )
                            },
                        )
                        if (effectiveNavigationSurfaceStyle == BackgroundSurfaceStyle.LIQUID_GLASS) item(
                            headlineContent = {
                                Text(stringResource(R.string.setting_advanced_appearance_liquid_blur))
                            },
                            supportingContent = {
                                AdvancedAppearanceSlider(
                                    description = stringResource(R.string.setting_advanced_appearance_liquid_blur_desc),
                                    value = appearance.navigationLiquidGlassBlurRadius.coerceIn(
                                        MIN_LIQUID_GLASS_BLUR_RADIUS,
                                        liveBlurMax,
                                    ),
                                    valueRange = MIN_LIQUID_GLASS_BLUR_RADIUS..liveBlurMax,
                                    steps = if (reducedEffects) 5 else 11,
                                    enabled = blurSupported &&
                                        chatBackgroundActive &&
                                        appearance.enableNavigationPerformanceEffects,
                                    valueLabel = { value ->
                                        stringResource(
                                            R.string.setting_advanced_appearance_background_blur_value,
                                            value.roundToInt(),
                                        )
                                    },
                                    onValueChange = { value ->
                                        updateAppearance { copy(navigationLiquidGlassBlurRadius = value) }
                                    },
                                )
                            },
                        )
                    }
                }
            }

            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.setting_advanced_appearance_bubble_style_section)) },
                ) {
                    item(
                        headlineContent = {
                            Text(stringResource(R.string.setting_advanced_appearance_bubble_style))
                        },
                        supportingContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                when {
                                    !settings.displaySetting.showAssistantBubble -> CompatibilityWarningText(
                                        R.string.setting_advanced_appearance_requires_chat_bubbles
                                    )
                                    !chatBackgroundActive -> CompatibilityWarningText(
                                        R.string.setting_advanced_appearance_requires_background
                                    )
                                    else -> Text(
                                        stringResource(
                                            R.string.setting_advanced_appearance_bubble_style_desc
                                        )
                                    )
                                }
                                BubbleStyleSelector(
                                    selected = appearanceCapabilities.effectiveBubbleStyle(
                                        appearance.chatBubbleStyle
                                    ),
                                    enabled = bubbleStylesAvailable,
                                    isSupported = appearanceCapabilities::supportsBubbleStyle,
                                    onSelected = { style ->
                                        updateAppearance { copy(chatBubbleStyle = style) }
                                    },
                                )
                                BubbleStyleCompatibilityWarning(
                                    blurSupported = blurSupported,
                                    reducedEffects = reducedEffects,
                                )
                                PerformanceSettingWarning(
                                    effectsEnabled = appearance.enableBubblePerformanceEffects
                                )
                                Text(
                                    text = when (
                                        appearanceCapabilities.effectiveBubbleStyle(
                                            appearance.chatBubbleStyle
                                        )
                                    ) {
                                        ChatBubbleStyle.FROSTED -> stringResource(
                                            R.string.setting_advanced_appearance_bubble_frosted_desc
                                        )
                                        ChatBubbleStyle.OUTLINED -> stringResource(
                                            R.string.setting_advanced_appearance_bubble_outlined_desc
                                        )
                                        ChatBubbleStyle.LIQUID_GLASS -> stringResource(
                                            R.string.setting_advanced_appearance_bubble_liquid_desc
                                        )
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                    )
                }
            }

            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.setting_advanced_appearance_chat_text_section)) },
                ) {
                    item(
                        headlineContent = {
                            Text(stringResource(R.string.setting_advanced_appearance_chat_text_enabled))
                        },
                        supportingContent = {
                            Text(stringResource(R.string.setting_advanced_appearance_chat_text_enabled_desc))
                        },
                        trailingContent = {
                            Switch(
                                checked = appearance.enableChatTextReadability,
                                onCheckedChange = { enabled ->
                                    updateAppearance { copy(enableChatTextReadability = enabled) }
                                },
                            )
                        },
                    )
                    if (appearance.enableChatTextReadability) {
                        item(
                            headlineContent = {
                                Text(stringResource(R.string.setting_advanced_appearance_chat_line_height))
                            },
                            supportingContent = {
                                AdvancedAppearanceSlider(
                                    description = stringResource(
                                        R.string.setting_advanced_appearance_chat_line_height_desc
                                    ),
                                    value = appearance.chatTextLineHeightRatio.coerceIn(
                                        MIN_CHAT_TEXT_LINE_HEIGHT_RATIO,
                                        MAX_CHAT_TEXT_LINE_HEIGHT_RATIO,
                                    ),
                                    valueRange = MIN_CHAT_TEXT_LINE_HEIGHT_RATIO..
                                        MAX_CHAT_TEXT_LINE_HEIGHT_RATIO,
                                    steps = 10,
                                    valueLabel = { value ->
                                        stringResource(
                                            R.string.setting_advanced_appearance_chat_line_height_value,
                                            value,
                                        )
                                    },
                                    onValueChange = { value ->
                                        updateAppearance { copy(chatTextLineHeightRatio = value) }
                                    },
                                )
                            },
                        )
                        item(
                            headlineContent = {
                                Text(stringResource(R.string.setting_advanced_appearance_chat_paragraph_spacing))
                            },
                            supportingContent = {
                                AdvancedAppearanceSlider(
                                    description = stringResource(
                                        R.string.setting_advanced_appearance_chat_paragraph_spacing_desc
                                    ),
                                    value = appearance.chatParagraphSpacingRatio.coerceIn(
                                        MIN_CHAT_PARAGRAPH_SPACING_RATIO,
                                        MAX_CHAT_PARAGRAPH_SPACING_RATIO,
                                    ),
                                    valueRange = MIN_CHAT_PARAGRAPH_SPACING_RATIO..
                                        MAX_CHAT_PARAGRAPH_SPACING_RATIO,
                                    steps = 12,
                                    valueLabel = { value ->
                                        stringResource(
                                            R.string.setting_advanced_appearance_chat_paragraph_spacing_value,
                                            (value * 100).roundToInt(),
                                        )
                                    },
                                    onValueChange = { value ->
                                        updateAppearance { copy(chatParagraphSpacingRatio = value) }
                                    },
                                )
                            },
                        )
                    }
                    item(
                        headlineContent = {
                            Text(stringResource(R.string.setting_advanced_appearance_chat_text_compatibility_title))
                        },
                        supportingContent = {
                            Text(
                                text = stringResource(
                                    R.string.setting_advanced_appearance_chat_text_compatibility
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    )
                }
            }

            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.setting_advanced_appearance_rich_content_section)) },
                ) {
                    item(
                        headlineContent = {
                            Text(stringResource(R.string.setting_advanced_appearance_rich_content_style))
                        },
                        supportingContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(stringResource(R.string.setting_advanced_appearance_rich_content_desc))
                                RichContentStyleSelector(
                                    selected = appearance.richContentStyle,
                                    enabled = appearance.enableRichContentPerformanceEffects,
                                    onSelected = { style ->
                                        updateAppearance { copy(richContentStyle = style) }
                                    },
                                )
                                Text(
                                    text = when (appearance.richContentStyle) {
                                        RichContentStyle.TRANSLUCENT -> stringResource(
                                            R.string.setting_advanced_appearance_rich_content_translucent_desc
                                        )
                                        RichContentStyle.OUTLINED -> stringResource(
                                            R.string.setting_advanced_appearance_rich_content_outlined_desc
                                        )
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                PerformanceSettingWarning(
                                    effectsEnabled = appearance.enableRichContentPerformanceEffects
                                )
                            }
                        },
                    )
                    if (appearance.richContentStyle == RichContentStyle.TRANSLUCENT) {
                        item(
                            headlineContent = {
                                Text(stringResource(R.string.setting_advanced_appearance_rich_content_opacity))
                            },
                            supportingContent = {
                                AdvancedAppearanceSlider(
                                    description = stringResource(
                                        R.string.setting_advanced_appearance_rich_content_opacity_desc
                                    ),
                                    value = appearance.richContentSurfaceOpacity.coerceIn(0.2f, 0.9f),
                                    valueRange = 0.2f..0.9f,
                                    steps = 13,
                                    enabled = appearance.enableRichContentPerformanceEffects,
                                    valueLabel = { value ->
                                        stringResource(
                                            R.string.setting_advanced_appearance_background_opacity_value,
                                            (value * 100).roundToInt(),
                                        )
                                    },
                                    onValueChange = { value ->
                                        updateAppearance { copy(richContentSurfaceOpacity = value) }
                                    },
                                )
                            },
                        )
                    }
                    item(
                        headlineContent = {
                            Text(stringResource(R.string.setting_advanced_appearance_chat_suggestion_surface))
                        },
                        supportingContent = {
                            Text(
                                stringResource(R.string.setting_advanced_appearance_chat_suggestion_surface_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        trailingContent = {
                            Switch(
                                checked = appearance.enableChatSuggestionSurface,
                                onCheckedChange = { enabled ->
                                    updateAppearance { copy(enableChatSuggestionSurface = enabled) }
                                },
                            )
                        },
                    )
                    item(
                        headlineContent = {
                            Text(stringResource(R.string.setting_advanced_appearance_chat_suggestion_opacity))
                        },
                        supportingContent = {
                            AdvancedAppearanceSlider(
                                description = stringResource(R.string.setting_advanced_appearance_chat_suggestion_opacity_desc),
                                value = appearance.chatSuggestionSurfaceOpacity.coerceIn(0.2f, 0.9f),
                                valueRange = 0.2f..0.9f,
                                steps = 13,
                                enabled = appearance.enableChatSuggestionSurface && appearance.enableRichContentPerformanceEffects,
                                valueLabel = { value ->
                                    stringResource(
                                        R.string.setting_advanced_appearance_background_opacity_value,
                                        (value * 100).roundToInt(),
                                    )
                                },
                                onValueChange = { value ->
                                    updateAppearance { copy(chatSuggestionSurfaceOpacity = value) }
                                },
                            )
                        },
                    )
                    item(
                        headlineContent = {
                            Text(stringResource(R.string.setting_advanced_appearance_chat_suggestion_border))
                        },
                        supportingContent = {
                            AdvancedAppearanceSlider(
                                description = stringResource(R.string.setting_advanced_appearance_chat_suggestion_border_desc),
                                value = appearance.chatSuggestionBorderOpacity.coerceIn(0f, 1f),
                                valueRange = 0f..1f,
                                steps = 19,
                                enabled = appearance.enableChatSuggestionSurface,
                                valueLabel = { value ->
                                    stringResource(
                                        R.string.setting_advanced_appearance_background_opacity_value,
                                        (value * 100).roundToInt(),
                                    )
                                },
                                onValueChange = { value ->
                                    updateAppearance { copy(chatSuggestionBorderOpacity = value) }
                                },
                            )
                        },
                    )
                    item(
                        headlineContent = {
                            Text(stringResource(R.string.setting_advanced_appearance_chat_suggestion_height))
                        },
                        supportingContent = {
                            AdvancedAppearanceSlider(
                                description = stringResource(R.string.setting_advanced_appearance_chat_suggestion_height_desc),
                                value = appearance.chatSuggestionMaxHeight.coerceIn(72f, 220f),
                                valueRange = 72f..220f,
                                steps = 14,
                                enabled = true,
                                valueLabel = { value -> "${value.roundToInt()}dp" },
                                onValueChange = { value ->
                                    updateAppearance { copy(chatSuggestionMaxHeight = value) }
                                },
                            )
                        },
                    )
                    item(
                        headlineContent = {
                            Text(stringResource(R.string.setting_advanced_appearance_rich_content_compatibility_title))
                        },
                        supportingContent = {
                            Text(
                                text = stringResource(
                                    R.string.setting_advanced_appearance_rich_content_compatibility
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    )
                }
            }

            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.setting_advanced_appearance_auto_accent_section)) },
                ) {
                    item(
                        headlineContent = {
                            Text(stringResource(R.string.setting_advanced_appearance_auto_accent))
                        },
                        supportingContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    if (settings.isGlobalBackgroundActive()) {
                                        stringResource(R.string.setting_advanced_appearance_auto_accent_desc)
                                    } else {
                                        stringResource(R.string.setting_advanced_appearance_auto_accent_requires_global)
                                    }
                                )
                                when {
                                    extractingAccent -> AccentExtractionStatus()
                                    settings.isAutoAccentActive() -> {
                                        AccentColorStatus(requireNotNull(appearance.autoAccentColorArgb))
                                    }
                                }
                                if (me.rerere.rikkahub.ui.theme.BackgroundAccentState.failed && settings.isGlobalBackgroundActive() && appearance.enableAutoAccent) {
                                    Text("背景强调色提取失败，可重试或更换图片。", color = CompatibilityWarningYellow)
                                    TextButton(onClick = { me.rerere.rikkahub.ui.theme.BackgroundAccentState.retry++ }, enabled = !extractingAccent) { Text("重试提取") }
                                }
                            }
                        },
                        trailingContent = {
                            Switch(
                                checked = appearance.enableAutoAccent,
                                enabled = settings.isGlobalBackgroundActive() || appearance.enableAutoAccent,
                                onCheckedChange = { enabled ->
                                    vm.selectBackgroundAccent(enabled)
                                },
                            )
                        },
                    )
                }
            }

            item {
                TextColorModeSettings(
                    selected = appearance.textColorMode,
                    onSelected = { mode -> updateAppearance { copy(textColorMode = mode) } },
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }

            item {
                InspirationAppearanceSettings(appearance.inspirationAppearance) { value ->
                    updateAppearance { copy(inspirationAppearance = value) }
                }
            }

            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.setting_advanced_appearance_color_section)) },
                ) {
                    item(
                        headlineContent = {
                            Text(stringResource(R.string.setting_advanced_appearance_color_style))
                        },
                        supportingContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    stringResource(
                                        R.string.setting_advanced_appearance_color_style_desc
                                    )
                                )
                                AppearanceColorStyleSelector(
                                    selected = appearance.colorStyle,
                                    onSelected = { style ->
                                        updateAppearance { copy(colorStyle = style) }
                                    },
                                )
                            }
                        },
                    )
                    item(
                        headlineContent = {
                            Text(stringResource(R.string.setting_advanced_appearance_color_contrast))
                        },
                        supportingContent = {
                            AdvancedAppearanceSlider(
                                description = stringResource(
                                    R.string.setting_advanced_appearance_color_contrast_desc
                                ),
                                value = appearance.colorContrast.coerceIn(-1f, 1f),
                                valueRange = -1f..1f,
                                steps = 19,
                                valueLabel = { value ->
                                    stringResource(
                                        R.string.setting_advanced_appearance_color_contrast_value,
                                        value,
                                    )
                                },
                                onValueChange = { value ->
                                    updateAppearance { copy(colorContrast = value) }
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

@Composable
private fun BackgroundSurfaceStyleSelector(
    selected: BackgroundSurfaceStyle,
    enabled: Boolean = true,
    isSupported: (BackgroundSurfaceStyle) -> Boolean,
    onSelected: (BackgroundSurfaceStyle) -> Unit,
) {
    val options = listOf(
        BackgroundSurfaceStyle.OPAQUE to stringResource(
            R.string.setting_advanced_appearance_surface_style_opaque
        ),
        BackgroundSurfaceStyle.TRANSLUCENT to stringResource(
            R.string.setting_advanced_appearance_surface_style_translucent
        ),
        BackgroundSurfaceStyle.FROSTED to stringResource(
            R.string.setting_advanced_appearance_surface_style_frosted
        ),
        BackgroundSurfaceStyle.LIQUID_GLASS to stringResource(
            R.string.setting_advanced_appearance_surface_style_liquid
        ),
    )
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        options.chunked(2).forEach { rowOptions ->
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                rowOptions.forEachIndexed { index, (style, label) ->
                    SegmentedButton(
                        selected = selected == style,
                        onClick = { onSelected(style) },
                        enabled = enabled && isSupported(style),
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
private fun AppearanceColorStyleSelector(
    selected: AppearanceColorStyle,
    onSelected: (AppearanceColorStyle) -> Unit,
) {
    val options = listOf(
        AppearanceColorStyle.TONAL_SPOT to stringResource(
            R.string.setting_advanced_appearance_color_style_tonal_spot
        ),
        AppearanceColorStyle.NEUTRAL to stringResource(
            R.string.setting_advanced_appearance_color_style_neutral
        ),
        AppearanceColorStyle.VIBRANT to stringResource(
            R.string.setting_advanced_appearance_color_style_vibrant
        ),
        AppearanceColorStyle.EXPRESSIVE to stringResource(
            R.string.setting_advanced_appearance_color_style_expressive
        ),
        AppearanceColorStyle.MONOCHROME to stringResource(
            R.string.setting_advanced_appearance_color_style_monochrome
        ),
    )
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        options.chunked(2).forEach { rowOptions ->
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                rowOptions.forEachIndexed { index, (style, label) ->
                    SegmentedButton(
                        selected = selected == style,
                        onClick = { onSelected(style) },
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
private fun BubbleStyleSelector(
    selected: ChatBubbleStyle,
    enabled: Boolean,
    isSupported: (ChatBubbleStyle) -> Boolean,
    onSelected: (ChatBubbleStyle) -> Unit,
) {
    val options = listOf(
        ChatBubbleStyle.FROSTED to stringResource(R.string.setting_advanced_appearance_bubble_frosted),
        ChatBubbleStyle.OUTLINED to stringResource(R.string.setting_advanced_appearance_bubble_outlined),
        ChatBubbleStyle.LIQUID_GLASS to stringResource(R.string.setting_advanced_appearance_bubble_liquid),
    )
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (style, label) ->
            SegmentedButton(
                selected = selected == style,
                onClick = { onSelected(style) },
                enabled = enabled && isSupported(style),
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
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

@Composable
private fun RichContentStyleSelector(
    selected: RichContentStyle,
    enabled: Boolean = true,
    onSelected: (RichContentStyle) -> Unit,
) {
    val options = listOf(
        RichContentStyle.TRANSLUCENT to stringResource(
            R.string.setting_advanced_appearance_rich_content_translucent
        ),
        RichContentStyle.OUTLINED to stringResource(
            R.string.setting_advanced_appearance_rich_content_outlined
        ),
    )
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (style, label) ->
            SegmentedButton(
                selected = selected == style,
                onClick = { onSelected(style) },
                enabled = enabled,
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
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

@Composable
private fun AccentExtractionStatus() {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        Text(
            text = stringResource(R.string.setting_advanced_appearance_auto_accent_extracting),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AccentColorStatus(colorArgb: Long) {
    val colorHex = "#%06X".format(colorArgb.toInt() and 0x00FF_FFFF)
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.size(18.dp),
            shape = CircleShape,
            color = Color(colorArgb.toInt()),
            content = {},
        )
        Text(
            text = stringResource(R.string.setting_advanced_appearance_auto_accent_color, colorHex),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private val CompatibilityWarningYellow = Color(0xFFF9A825)

@Composable
private fun CompatibilityNoticeCard(
    reduced: Boolean,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = HugeIcons.InformationCircle,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = CompatibilityWarningYellow,
            )
            Text(
                text = stringResource(
                    if (reduced) {
                        R.string.setting_advanced_appearance_compatibility_reduced
                    } else {
                        R.string.setting_advanced_appearance_compatibility_unsupported
                    }
                ),
                style = MaterialTheme.typography.bodySmall,
                color = CompatibilityWarningYellow,
            )
        }
    }
}

@Composable
private fun AppearancePerformanceCard(
    capabilities: me.rerere.rikkahub.ui.components.ui.AdvancedAppearanceCapabilities,
    appearance: AdvancedAppearanceSetting,
    impact: AppearancePerformanceImpact,
    update: ((AdvancedAppearanceSetting) -> AdvancedAppearanceSetting) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val level = when (capabilities.blurSupport) {
        AdvancedAppearanceSupport.FULL -> stringResource(R.string.setting_advanced_appearance_compatibility_full)
        AdvancedAppearanceSupport.REDUCED -> stringResource(R.string.setting_advanced_appearance_compatibility_reduced_level)
        AdvancedAppearanceSupport.UNSUPPORTED -> stringResource(R.string.setting_advanced_appearance_compatibility_basic)
    }
    val impactText = when (impact) {
        AppearancePerformanceImpact.HIGH -> stringResource(R.string.setting_advanced_appearance_performance_high)
        AppearancePerformanceImpact.MEDIUM -> stringResource(R.string.setting_advanced_appearance_performance_medium)
        AppearancePerformanceImpact.LOW -> stringResource(R.string.setting_advanced_appearance_performance_low)
    }
    CardGroup(
        modifier = Modifier.padding(horizontal = 8.dp),
        title = { Text(stringResource(R.string.setting_advanced_appearance_performance_section)) },
    ) {
        item(
            onClick = { expanded = !expanded },
            headlineContent = { Text(stringResource(R.string.setting_advanced_appearance_compatibility_level)) },
            supportingContent = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(level)
                    Text(impactText)
                    Text(
                        text = stringResource(
                            if (expanded) {
                                R.string.setting_advanced_appearance_performance_collapse
                            } else {
                                R.string.setting_advanced_appearance_performance_expand
                            }
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            trailingContent = {
                Icon(
                    imageVector = if (expanded) HugeIcons.ArrowUp01 else HugeIcons.ArrowDown01,
                    contentDescription = stringResource(
                        if (expanded) {
                            R.string.setting_advanced_appearance_performance_collapse
                        } else {
                            R.string.setting_advanced_appearance_performance_expand
                        }
                    ),
                    modifier = Modifier.size(20.dp),
                )
            },
        )
        if (expanded) {
        item(
            headlineContent = { Text(stringResource(R.string.setting_advanced_appearance_performance_topbar)) },
            supportingContent = { Text(stringResource(R.string.setting_advanced_appearance_performance_topbar_desc)) },
            trailingContent = {
                Switch(
                    checked = appearance.enableTopBarPerformanceEffects,
                    onCheckedChange = { enabled -> update { current -> current.copy(enableTopBarPerformanceEffects = enabled) } },
                )
            },
        )
        item(
            headlineContent = { Text(stringResource(R.string.setting_advanced_appearance_performance_rich)) },
            supportingContent = { Text(stringResource(R.string.setting_advanced_appearance_performance_rich_desc)) },
            trailingContent = {
                Switch(
                    checked = appearance.enableRichContentPerformanceEffects,
                    onCheckedChange = { enabled -> update { current -> current.copy(enableRichContentPerformanceEffects = enabled) } },
                )
            },
        )
        item(
            headlineContent = { Text(stringResource(R.string.setting_advanced_appearance_performance_navigation)) },
            supportingContent = { Text(stringResource(R.string.setting_advanced_appearance_performance_navigation_desc)) },
            trailingContent = {
                Switch(
                    checked = appearance.enableNavigationPerformanceEffects,
                    onCheckedChange = { enabled ->
                        update { current -> current.copy(enableNavigationPerformanceEffects = enabled) }
                    },
                )
            },
        )
        item(
            headlineContent = { Text(stringResource(R.string.setting_advanced_appearance_performance_bubble)) },
            supportingContent = { Text(stringResource(R.string.setting_advanced_appearance_performance_bubble_desc)) },
            trailingContent = {
                Switch(
                    checked = appearance.enableBubblePerformanceEffects,
                    onCheckedChange = { enabled ->
                        update { current -> current.copy(enableBubblePerformanceEffects = enabled) }
                    },
                )
            },
        )
        item(
            headlineContent = { Text(stringResource(R.string.setting_advanced_appearance_performance_gradient)) },
            supportingContent = { Text(stringResource(R.string.setting_advanced_appearance_performance_gradient_desc)) },
            trailingContent = {
                Switch(
                    checked = appearance.enableGradientPerformanceEffects,
                    onCheckedChange = { enabled ->
                        update { current -> current.copy(enableGradientPerformanceEffects = enabled) }
                    },
                )
            },
        )
        item(
            headlineContent = {
                Text(stringResource(R.string.setting_advanced_appearance_gradient_renderer))
            },
            supportingContent = {
                val supportsAgsl = capabilities.sdkInt >= AGSL_GRADIENT_MIN_SDK
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.setting_advanced_appearance_gradient_renderer_desc))
                    GradientRendererSelector(
                        selected = appearance.gradientRendererMode,
                        supportsAgsl = supportsAgsl && !me.rerere.rikkahub.ui.components.ui.AgslGradientRuntime.failed,
                        onSelected = { mode ->
                            update { current -> current.copy(gradientRendererMode = mode) }
                        },
                    )
                    Text(
                        text = if (supportsAgsl && appearance.gradientRendererMode != GradientRendererMode.KOTLIN && me.rerere.rikkahub.ui.components.ui.AgslGradientRuntime.failed) {
                            "AGSL 渲染失败，本次运行已自动改用 Kotlin，重启后重新检测。"
                        } else stringResource(
                            if (
                                supportsAgsl &&
                                appearance.gradientRendererMode != GradientRendererMode.KOTLIN
                            ) {
                                R.string.setting_advanced_appearance_gradient_renderer_active_agsl
                            } else {
                                R.string.setting_advanced_appearance_gradient_renderer_active_kotlin
                            }
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (me.rerere.rikkahub.ui.components.ui.AgslGradientRuntime.failed) CompatibilityWarningYellow else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!supportsAgsl) {
                        Text(
                            text = stringResource(
                                R.string.setting_advanced_appearance_gradient_renderer_agsl_unavailable
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = CompatibilityWarningYellow,
                        )
                    }
                }
            },
        )
        item(
            headlineContent = { Text(stringResource(R.string.setting_advanced_appearance_respect_reduced_motion)) },
            supportingContent = { Text(stringResource(R.string.setting_advanced_appearance_respect_reduced_motion_desc)) },
            trailingContent = {
                Switch(
                    checked = appearance.respectSystemReducedMotion,
                    onCheckedChange = { enabled ->
                        update { current -> current.copy(respectSystemReducedMotion = enabled) }
                    },
                )
            },
        )
        }
    }
}

@Composable
private fun GradientRendererSelector(
    selected: GradientRendererMode,
    supportsAgsl: Boolean,
    onSelected: (GradientRendererMode) -> Unit,
) {
    val options = listOf(
        GradientRendererMode.AUTO to stringResource(
            R.string.setting_advanced_appearance_gradient_renderer_auto
        ),
        GradientRendererMode.AGSL to stringResource(
            R.string.setting_advanced_appearance_gradient_renderer_agsl
        ),
        GradientRendererMode.KOTLIN to stringResource(
            R.string.setting_advanced_appearance_gradient_renderer_kotlin
        ),
    )
    val displayedSelection = if (!supportsAgsl && selected == GradientRendererMode.AGSL) {
        GradientRendererMode.KOTLIN
    } else {
        selected
    }
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (mode, label) ->
            SegmentedButton(
                selected = displayedSelection == mode,
                onClick = { onSelected(mode) },
                enabled = mode != GradientRendererMode.AGSL || supportsAgsl,
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
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

@Composable
private fun AppearanceCompatibilityWarning(
    blurSupported: Boolean,
    reducedEffects: Boolean,
) {
    when {
        !blurSupported -> CompatibilityWarningText(
            R.string.setting_advanced_appearance_compatibility_blur_unavailable
        )
        reducedEffects -> CompatibilityWarningText(
            R.string.setting_advanced_appearance_compatibility_blur_reduced
        )
    }
}

@Composable
private fun SurfaceStyleCompatibilityWarning(
    blurSupported: Boolean,
    reducedEffects: Boolean,
) {
    when {
        !blurSupported -> CompatibilityWarningText(
            R.string.setting_advanced_appearance_compatibility_surface_unavailable
        )
        reducedEffects -> CompatibilityWarningText(
            R.string.setting_advanced_appearance_compatibility_surface_reduced
        )
    }
}

@Composable
private fun BubbleStyleCompatibilityWarning(
    blurSupported: Boolean,
    reducedEffects: Boolean,
) {
    when {
        !blurSupported -> CompatibilityWarningText(
            R.string.setting_advanced_appearance_compatibility_bubble_unavailable
        )
        reducedEffects -> CompatibilityWarningText(
            R.string.setting_advanced_appearance_compatibility_bubble_reduced
        )
    }
}

@Composable
private fun PerformanceSettingWarning(effectsEnabled: Boolean) {
    if (!effectsEnabled) {
        CompatibilityWarningText(
            R.string.setting_advanced_appearance_performance_effects_disabled
        )
    }
}

@Composable
private fun CompatibilityWarningText(stringId: Int) {
    Text(
        text = stringResource(stringId),
        style = MaterialTheme.typography.bodySmall,
        color = CompatibilityWarningYellow,
    )
}

@Composable
private fun AppearanceNoticeCard(modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = HugeIcons.InformationCircle,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = stringResource(R.string.setting_advanced_appearance_notice_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = stringResource(R.string.setting_advanced_appearance_notice_override),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.setting_advanced_appearance_notice_glass),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.setting_advanced_appearance_notice_performance),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun AdvancedAppearanceSlider(
    description: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    enabled: Boolean = true,
    valueLabel: @Composable (Float) -> String,
    onValueChange: (Float) -> Unit,
) {
    val safeValue = finiteAppearanceValue(value, valueRange.start, valueRange.endInclusive, valueRange.start)
    var sliderValue by remember(description, valueRange) { mutableFloatStateOf(safeValue) }
    var dragging by remember(description, valueRange) { mutableStateOf(false) }
    var pendingCommit by remember(description, valueRange) { mutableStateOf<Float?>(null) }
    val saveFailed = LocalAppearanceSaveFailed.current
    val currentEnabled by rememberUpdatedState(enabled)
    val currentOnValueChange by rememberUpdatedState(onValueChange)

    LaunchedEffect(description, safeValue, enabled, saveFailed) {
        if (shouldSyncAppearanceSlider(dragging, pendingCommit, safeValue, enabled, saveFailed)) {
            sliderValue = safeValue
            dragging = false
            pendingCommit = null
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = description,
            color = if (enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            },
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Slider(
                value = sliderValue,
                onValueChange = { updatedValue ->
                    if (!currentEnabled) return@Slider
                    dragging = true
                    pendingCommit = null
                    sliderValue = finiteAppearanceValue(updatedValue, valueRange.start, valueRange.endInclusive, safeValue)
                },
                onValueChangeFinished = {
                    if (!currentEnabled) {
                        dragging = false
                        pendingCommit = null
                        sliderValue = safeValue
                        return@Slider
                    }
                    val committedValue = sliderValue
                    dragging = false
                    if (abs(committedValue - safeValue) < 0.0001f) {
                        dragging = false
                        pendingCommit = null
                    } else {
                        pendingCommit = committedValue
                        currentOnValueChange(committedValue)
                    }
                },
                valueRange = valueRange,
                steps = steps,
                enabled = enabled,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = valueLabel(sliderValue),
                modifier = Modifier.widthIn(min = 56.dp),
                textAlign = TextAlign.End,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                },
            )
        }
    }
}
