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
import androidx.compose.material3.LargeFlexibleTopAppBar
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.InformationCircle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.AdvancedAppearanceSetting
import me.rerere.rikkahub.data.datastore.BackgroundSurfaceStyle
import me.rerere.rikkahub.data.datastore.ChatBubbleStyle
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
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.AppearanceAlertDialog
import me.rerere.rikkahub.ui.components.ui.AdvancedAppearanceSupport
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.LocalAdvancedAppearanceCapabilities
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.pages.assistant.detail.BackgroundPicker
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.ui.theme.extractBackgroundAccent
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel
import kotlin.math.abs
import kotlin.math.roundToInt
import com.dokar.sonner.ToastType
import kotlinx.coroutines.CancellationException

@Composable
fun SettingPreferencesAdvancedAppearancePage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val appearance = settings.advancedAppearanceSetting
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
    val bubbleStylesAvailable = settings.displaySetting.showAssistantBubble && chatBackgroundActive
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val context = LocalContext.current
    val toaster = LocalToaster.current
    var extractingAccent by remember { mutableStateOf(false) }
    var showGlobalChatOverrideConfirmation by remember { mutableStateOf(false) }
    val configuredAssistantBackgroundCount = settings.configuredAssistantBackgroundCount()
    val accentExtractionFailed = stringResource(R.string.setting_advanced_appearance_auto_accent_failed)

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

    LaunchedEffect(
        appearance.enableAutoAccent,
        appearance.globalBackground,
        settings.isGlobalBackgroundActive(),
    ) {
        val background = appearance.globalBackground
        if (
            !appearance.enableAutoAccent ||
            !settings.isGlobalBackgroundActive() ||
            background.isNullOrBlank() ||
            appearance.autoAccentColorArgb != null
        ) {
            return@LaunchedEffect
        }

        extractingAccent = true
        try {
            val accent = try {
                extractBackgroundAccent(context, background)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            vm.updateAdvancedAppearance { currentAppearance ->
                if (
                    currentAppearance.enableAutoAccent &&
                    currentAppearance.globalBackground == background
                ) {
                    currentAppearance.copy(
                        autoAccentColorArgb = accent,
                    )
                } else {
                    currentAppearance
                }
            }
            if (accent == null) {
                toaster.show(accentExtractionFailed, type = ToastType.Error)
            }
        } finally {
            extractingAccent = false
        }
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
        containerColor = CustomColors.topBarColors.containerColor,
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
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.setting_advanced_appearance_chat_input_section)) },
                ) {
                    item(
                        headlineContent = {
                            Text(stringResource(R.string.setting_display_page_enable_blur_effect_title))
                        },
                        supportingContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    if (chatBackgroundActive) {
                                        stringResource(R.string.setting_display_page_enable_blur_effect_desc)
                                    } else {
                                        stringResource(R.string.setting_advanced_appearance_requires_background)
                                    }
                                )
                                AppearanceCompatibilityWarning(
                                    blurSupported = blurSupported,
                                    reducedEffects = reducedEffects,
                                )
                            }
                        },
                        trailingContent = {
                            Switch(
                                checked = settings.displaySetting.enableBlurEffect,
                                enabled = blurSupported,
                                onCheckedChange = { enabled ->
                                    updateDisplayAppearance { copy(enableBlurEffect = enabled) }
                                },
                            )
                        },
                    )
                    if (settings.displaySetting.enableBlurEffect) {
                        item(
                            headlineContent = {
                                Text(stringResource(R.string.setting_display_page_input_blur_radius_title))
                            },
                            supportingContent = {
                                AdvancedAppearanceSlider(
                                    description = stringResource(
                                        R.string.setting_display_page_input_blur_radius_desc
                                    ),
                                    value = settings.displaySetting.inputBlurRadius.coerceIn(
                                        MIN_LIQUID_GLASS_BLUR_RADIUS,
                                        liveBlurMax,
                                    ),
                                    valueRange = MIN_LIQUID_GLASS_BLUR_RADIUS..
                                        liveBlurMax,
                                    steps = if (reducedEffects) 5 else 11,
                                    enabled = blurSupported,
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
                        item(
                            headlineContent = {
                                Text(stringResource(R.string.setting_display_page_input_tint_title))
                            },
                            supportingContent = {
                                AdvancedAppearanceSlider(
                                    description = stringResource(R.string.setting_display_page_input_tint_desc),
                                    value = settings.displaySetting.inputSurfaceOpacity.coerceIn(0f, 1f),
                                    valueRange = 0f..1f,
                                    steps = 19,
                                    enabled = blurSupported,
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
                                Text(stringResource(R.string.setting_display_page_top_bar_blur_desc))
                                AppearanceCompatibilityWarning(
                                    blurSupported = blurSupported,
                                    reducedEffects = reducedEffects,
                                )
                            }
                        },
                        trailingContent = {
                            Switch(
                                checked = settings.displaySetting.enableTopBarBlur,
                                enabled = blurSupported,
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
                                    enabled = blurSupported,
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
                        item(
                            headlineContent = {
                                Text(stringResource(R.string.setting_display_page_top_bar_tint_title))
                            },
                            supportingContent = {
                                AdvancedAppearanceSlider(
                                    description = stringResource(R.string.setting_display_page_top_bar_tint_desc),
                                    value = settings.displaySetting.topBarSurfaceOpacity.coerceIn(0f, 1f),
                                    valueRange = 0f..1f,
                                    steps = 19,
                                    enabled = blurSupported,
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
            }

            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.setting_advanced_appearance_global_background_section)) },
                ) {
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
                                    copy(
                                        globalBackground = background,
                                        applyGlobalBackgroundToChat =
                                            applyGlobalBackgroundToChat &&
                                                !globalBackground.isNullOrBlank() &&
                                                !background.isNullOrBlank(),
                                        autoAccentColorArgb = if (background == globalBackground) {
                                            autoAccentColorArgb
                                        } else {
                                            null
                                        },
                                    )
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
                                            selected = appearance.pageSurfaceStyle,
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
                            if (appearance.pageSurfaceStyle == BackgroundSurfaceStyle.FROSTED) {
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
                            if (appearance.pageSurfaceStyle == BackgroundSurfaceStyle.LIQUID_GLASS) {
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
                            item(
                                headlineContent = {
                                    Text(stringResource(R.string.setting_advanced_appearance_page_surface_opacity))
                                },
                                supportingContent = {
                                    AdvancedAppearanceSlider(
                                        description = stringResource(
                                            R.string.setting_advanced_appearance_page_surface_opacity_desc
                                        ),
                                        value = appearance.pageSurfaceOpacity.coerceIn(0.35f, 1f),
                                        valueRange = 0.35f..1f,
                                        steps = 12,
                                        valueLabel = { value ->
                                            stringResource(
                                                R.string.setting_advanced_appearance_background_opacity_value,
                                                (value * 100).roundToInt(),
                                            )
                                        },
                                        onValueChange = { value ->
                                            updateAppearance { copy(pageSurfaceOpacity = value) }
                                        },
                                    )
                                },
                            )
                        }
                    }
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
                                BackgroundSurfaceStyleSelector(
                                    selected = appearance.overlaySurfaceStyle,
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
                    if (appearance.overlaySurfaceStyle != BackgroundSurfaceStyle.OPAQUE) {
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
                    if (appearance.overlaySurfaceStyle == BackgroundSurfaceStyle.FROSTED) {
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
                                    enabled = blurSupported,
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
                    if (appearance.overlaySurfaceStyle == BackgroundSurfaceStyle.LIQUID_GLASS) {
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
                                    enabled = blurSupported,
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
                    title = { Text(stringResource(R.string.setting_advanced_appearance_chat_dock_section)) },
                ) {
                    item(
                        headlineContent = {
                            Text(stringResource(R.string.setting_advanced_appearance_chat_dock_enabled))
                        },
                        supportingContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    if (chatBackgroundActive) {
                                        stringResource(R.string.setting_advanced_appearance_chat_dock_enabled_desc)
                                    } else {
                                        stringResource(R.string.setting_advanced_appearance_requires_background)
                                    }
                                )
                                AppearanceCompatibilityWarning(
                                    blurSupported = blurSupported,
                                    reducedEffects = reducedEffects,
                                )
                            }
                        },
                        trailingContent = {
                            Switch(
                                checked = appearance.enableChatDockGlass,
                                enabled = blurSupported,
                                onCheckedChange = { enabled ->
                                    updateAppearance { copy(enableChatDockGlass = enabled) }
                                },
                            )
                        },
                    )
                    if (appearance.enableChatDockGlass) {
                        item(
                            headlineContent = {
                                Text(stringResource(R.string.setting_advanced_appearance_chat_dock_opacity))
                            },
                            supportingContent = {
                                AdvancedAppearanceSlider(
                                    description = stringResource(R.string.setting_advanced_appearance_chat_dock_opacity_desc),
                                    value = appearance.chatDockGlassOpacity.coerceIn(0.2f, 0.95f),
                                    valueRange = 0.2f..0.95f,
                                    steps = 14,
                                    enabled = blurSupported,
                                    valueLabel = { value ->
                                        stringResource(
                                            R.string.setting_advanced_appearance_background_opacity_value,
                                            (value * 100).roundToInt(),
                                        )
                                    },
                                    onValueChange = { value ->
                                        updateAppearance { copy(chatDockGlassOpacity = value) }
                                    },
                                )
                            },
                        )
                        item(
                            headlineContent = {
                                Text(stringResource(R.string.setting_advanced_appearance_liquid_blur))
                            },
                            supportingContent = {
                                AdvancedAppearanceSlider(
                                    description = stringResource(R.string.setting_advanced_appearance_liquid_blur_desc),
                                    value = appearance.chatDockGlassBlurRadius.coerceIn(
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
                                        updateAppearance { copy(chatDockGlassBlurRadius = value) }
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
                                Text(
                                    if (chatBackgroundActive) {
                                        stringResource(R.string.setting_advanced_appearance_navigation_glass_enabled_desc)
                                    } else {
                                        stringResource(R.string.setting_advanced_appearance_requires_background)
                                    }
                                )
                                BackgroundSurfaceStyleSelector(
                                    selected = if (appearance.enableNavigationGlass) {
                                        appearance.navigationSurfaceStyle
                                    } else {
                                        BackgroundSurfaceStyle.OPAQUE
                                    },
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
                        if (appearance.navigationSurfaceStyle == BackgroundSurfaceStyle.FROSTED) item(
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
                                    enabled = blurSupported,
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
                        if (appearance.navigationSurfaceStyle == BackgroundSurfaceStyle.LIQUID_GLASS) item(
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
                                    enabled = blurSupported,
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
                                Text(
                                    when {
                                        !settings.displaySetting.showAssistantBubble -> stringResource(
                                            R.string.setting_advanced_appearance_requires_chat_bubbles
                                        )
                                        !chatBackgroundActive -> stringResource(
                                            R.string.setting_advanced_appearance_requires_background
                                        )
                                        else -> stringResource(
                                            R.string.setting_advanced_appearance_bubble_style_desc
                                        )
                                    }
                                )
                                BubbleStyleSelector(
                                    selected = appearance.chatBubbleStyle,
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
                                Text(
                                    text = when (appearance.chatBubbleStyle) {
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
                                    appearance.enableAutoAccent && appearance.autoAccentColorArgb != null -> {
                                        AccentColorStatus(appearance.autoAccentColorArgb)
                                    }
                                }
                            }
                        },
                        trailingContent = {
                            Switch(
                                checked = appearance.enableAutoAccent,
                                enabled = settings.isGlobalBackgroundActive(),
                                onCheckedChange = { enabled ->
                                    updateAppearance { copy(enableAutoAccent = enabled) }
                                },
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun BackgroundSurfaceStyleSelector(
    selected: BackgroundSurfaceStyle,
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
                        enabled = isSupported(style),
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
                    text = stringResource(R.string.setting_advanced_appearance_notice_readability),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.setting_advanced_appearance_notice_performance),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    text = stringResource(R.string.setting_advanced_appearance_notice_bubbles),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.setting_advanced_appearance_notice_rich_content),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.setting_advanced_appearance_notice_auto_accent),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AdvancedAppearanceSlider(
    description: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    enabled: Boolean = true,
    valueLabel: @Composable (Float) -> String,
    onValueChange: (Float) -> Unit,
) {
    var sliderValue by remember { mutableFloatStateOf(value) }
    var dragging by remember { mutableStateOf(false) }
    var pendingCommit by remember { mutableStateOf<Float?>(null) }

    LaunchedEffect(value) {
        if (!dragging || pendingCommit != null) {
            sliderValue = value
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
                    dragging = true
                    pendingCommit = null
                    sliderValue = updatedValue
                },
                onValueChangeFinished = {
                    val committedValue = sliderValue
                    if (abs(committedValue - value) < 0.0001f) {
                        dragging = false
                    } else {
                        pendingCommit = committedValue
                        onValueChange(committedValue)
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
