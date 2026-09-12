package me.rerere.rikkahub.ui.pages.setting

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import me.rerere.rikkahub.ui.components.ui.AppearanceAlertDialog as AlertDialog
import androidx.compose.material3.Icon
import me.rerere.rikkahub.ui.components.ui.LargeFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.permission.PermissionManager
import me.rerere.rikkahub.ui.components.ui.permission.PermissionNotification
import me.rerere.rikkahub.ui.components.ui.permission.rememberPermissionState
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import me.rerere.rikkahub.utils.toLocalDateTime
import org.koin.androidx.compose.koinViewModel
import java.time.Instant

private val UPDATE_PAUSE_DAY_OPTIONS = listOf(7, 14, 21)
private const val MILLIS_PER_DAY = 24 * 60 * 60 * 1_000L

@Composable
fun SettingPreferencesNotificationPage(vm: SettingVM = koinViewModel()) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val protection: me.rerere.rikkahub.service.GenerationKeepAlive = org.koin.compose.koinInject()
    val protectionProblem by protection.statusProblem.collectAsStateWithLifecycle()
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var batteryUnrestricted by remember { mutableStateOf(false) }
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        fun refresh() {
            batteryUnrestricted = context.getSystemService(android.os.PowerManager::class.java)
                .isIgnoringBatteryOptimizations(context.packageName)
        }
        refresh()
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val settings by vm.settings.collectAsStateWithLifecycle()
    var displaySetting by remember(settings) { mutableStateOf(settings.displaySetting) }
    var showUpdatePauseDialog by remember { mutableStateOf(false) }
    var selectedUpdatePauseDays by remember { mutableStateOf(UPDATE_PAUSE_DAY_OPTIONS.first()) }

    fun updateDisplaySetting(setting: DisplaySetting) {
        displaySetting = setting
        vm.updateDisplaySetting { setting }
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val updateChecksEnabled =
        displaySetting.updateCheckDisabledUntilEpochMillis <= System.currentTimeMillis()
    val permissionState = rememberPermissionState(
        permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) setOf(
            PermissionNotification
        ) else emptySet(),
    )
    PermissionManager(permissionState = permissionState)

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(stringResource(R.string.setting_page_preferences_notification))
                },
                navigationIcon = {
                    BackButton()
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.scaffoldContainerColor
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("后台生成", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                    Text(if (batteryUnrestricted) "电池优化：已豁免" else "电池优化：仍受系统限制")
                    Text("生成期间显示后台任务通知，全部结束后自动停止。省电模式、厂商后台限制或强制结束应用仍可能中断连接。",
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                    protectionProblem?.let { Text(it, color = androidx.compose.material3.MaterialTheme.colorScheme.error) }
                    TextButton(onClick = {
                        runCatching { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                            .onFailure {
                                runCatching { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    android.net.Uri.parse("package:${context.packageName}"))) }
                            }
                    }) { Text("电池与后台限制设置") }
                }
            }
            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) {
                    item(
                        onClick = {
                            selectedUpdatePauseDays = UPDATE_PAUSE_DAY_OPTIONS.first()
                            showUpdatePauseDialog = true
                        },
                        headlineContent = {
                            Text(stringResource(R.string.setting_display_page_show_updates_title))
                        },
                        supportingContent = {
                            Text(
                                if (updateChecksEnabled) {
                                    stringResource(R.string.setting_update_reminder_enabled)
                                } else {
                                    stringResource(
                                        R.string.setting_update_reminder_paused_until,
                                        Instant.ofEpochMilli(
                                            displaySetting.updateCheckDisabledUntilEpochMillis
                                        ).toLocalDateTime(),
                                    )
                                }
                            )
                        },
                        trailingContent = {
                            Icon(HugeIcons.ArrowRight01, contentDescription = null)
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_notification_message_generated)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_notification_message_generated_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.enableNotificationOnMessageGeneration,
                                onCheckedChange = {
                                    if (it && !permissionState.allPermissionsGranted) {
                                        permissionState.requestPermissions()
                                    }
                                    updateDisplaySetting(displaySetting.copy(enableNotificationOnMessageGeneration = it))
                                }
                            )
                        },
                    )
                    if (displaySetting.enableNotificationOnMessageGeneration) {
                        item(
                            headlineContent = { Text("实时通知刷新间隔") },
                            supportingContent = {
                                Column {
                                    Text("${displaySetting.liveUpdateIntervalSeconds.coerceIn(1, 10)} 秒")
                                    androidx.compose.material3.Slider(
                                        value = displaySetting.liveUpdateIntervalSeconds.coerceIn(1, 10).toFloat(),
                                        onValueChange = { updateDisplaySetting(displaySetting.copy(liveUpdateIntervalSeconds = it.toInt())) },
                                        valueRange = 1f..10f, steps = 8,
                                        enabled = displaySetting.enableLiveUpdateNotification,
                                    )
                                }
                            },
                        )
                        item(
                            headlineContent = { Text("显示生成内容预览") },
                            supportingContent = { Text("关闭后通知仅显示状态，不展示模型输出文本") },
                            trailingContent = { Switch(checked = displaySetting.notificationContentPreview,
                                onCheckedChange = { updateDisplaySetting(displaySetting.copy(notificationContentPreview = it)) }) },
                        )
                        item(
                            headlineContent = { Text(stringResource(R.string.setting_display_page_live_update_notification)) },
                            supportingContent = { Text(stringResource(R.string.setting_display_page_live_update_notification_desc)) },
                            trailingContent = {
                                Switch(
                                    checked = displaySetting.enableLiveUpdateNotification,
                                    onCheckedChange = {
                                        updateDisplaySetting(displaySetting.copy(enableLiveUpdateNotification = it))
                                    }
                                )
                            },
                        )
                    }
                    item(
                        headlineContent = { Text("后台生成保护") },
                        supportingContent = { Text("使用常驻前台通知和短时唤醒锁，降低系统挂起导致的断连") },
                        trailingContent = { Switch(checked = displaySetting.backgroundGenerationProtection,
                            onCheckedChange = {
                                if (it && !permissionState.allPermissionsGranted) permissionState.requestPermissions()
                                updateDisplaySetting(displaySetting.copy(backgroundGenerationProtection = it))
                            }) },
                    )
                    item(
                        headlineContent = { Text("生成期间保持唤醒") },
                        supportingContent = { Text("仅在有生成任务时生效，会增加耗电") },
                        trailingContent = { Switch(checked = displaySetting.generationWakeLock,
                            enabled = displaySetting.backgroundGenerationProtection,
                            onCheckedChange = { updateDisplaySetting(displaySetting.copy(generationWakeLock = it)) }) },
                    )
                }
            }
        }
    }

    if (showUpdatePauseDialog) {
        AlertDialog(
            onDismissRequest = { showUpdatePauseDialog = false },
            title = { Text(stringResource(R.string.setting_update_reminder_pause_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(stringResource(R.string.setting_update_reminder_pause_description))
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        UPDATE_PAUSE_DAY_OPTIONS.forEachIndexed { index, days ->
                            SegmentedButton(
                                selected = selectedUpdatePauseDays == days,
                                onClick = { selectedUpdatePauseDays = days },
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = index,
                                    count = UPDATE_PAUSE_DAY_OPTIONS.size,
                                ),
                            ) {
                                Text(stringResource(R.string.setting_update_reminder_pause_days, days))
                            }
                        }
                    }
                    if (!updateChecksEnabled) {
                        TextButton(
                            onClick = {
                                updateDisplaySetting(
                                    displaySetting.copy(updateCheckDisabledUntilEpochMillis = 0L)
                                )
                                showUpdatePauseDialog = false
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.setting_update_reminder_resume_now))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        updateDisplaySetting(
                            displaySetting.copy(
                                updateCheckDisabledUntilEpochMillis =
                                    System.currentTimeMillis() +
                                        selectedUpdatePauseDays * MILLIS_PER_DAY,
                            )
                        )
                        showUpdatePauseDialog = false
                    }
                ) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showUpdatePauseDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}
