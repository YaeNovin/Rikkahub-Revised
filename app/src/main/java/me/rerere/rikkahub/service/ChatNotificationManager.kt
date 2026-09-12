package me.rerere.rikkahub.service

import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.utils.cancelNotification
import me.rerere.rikkahub.utils.sendNotification
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

// Live Update 通知节流间隔：流式输出每个chunk都会触发一次更新，
// notify() 是 binder IPC 且系统本身会对高频更新限流，必须在应用侧节流

/**
 * 订阅 [AppEventBus] 上的聊天生成事件，负责后台生成相关的系统通知
 * （Live Update 进度通知和生成完成通知）。
 */
class ChatNotificationManager(
    private val context: Application,
    appScope: AppScope,
    eventBus: AppEventBus,
    private val settingsStore: SettingsStore,
) {
    private val isForeground = MutableStateFlow(false)
    private val liveUpdateLastSentAt = ConcurrentHashMap<Uuid, Long>()
    private val latestUpdates = ConcurrentHashMap<Uuid, AppEvent.ChatGenerationUpdate>()
    private val lastPosted = mutableMapOf<Uuid, AppEvent.ChatGenerationUpdate>()

    init {
        appScope.launch {
            settingsStore.settingsFlow.map { it.displaySetting }.distinctUntilChanged().collect {
                clearAllLiveUpdateNotifications()
            }
        }
        // ProcessLifecycleOwner 要求在主线程注册观察者
        appScope.launch {
            ProcessLifecycleOwner.get().lifecycle.addObserver(
                LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_START -> {
                            isForeground.value = true
                            clearAllLiveUpdateNotifications()
                        }
                        Lifecycle.Event.ON_STOP -> isForeground.value = false
                        else -> {}
                    }
                }
            )
        }
        appScope.launch {
            eventBus.events.collect { event ->
                when (event) {
                    is AppEvent.ChatGenerationUpdate -> handleGenerationUpdate(event)
                    is AppEvent.ChatGenerationEnded -> handleGenerationEnded(event)
                    else -> {}
                }
            }
        }
        appScope.launch {
            while (true) {
                delay(1000)
                val setting = settingsStore.settingsFlow.value.displaySetting
                if (isForeground.value || !setting.enableNotificationOnMessageGeneration || !setting.enableLiveUpdateNotification) {
                    clearAllLiveUpdateNotifications()
                } else {
                    latestUpdates.values.toList().forEach { handleGenerationUpdate(it) }
                }
            }
        }
    }

    private fun handleGenerationUpdate(event: AppEvent.ChatGenerationUpdate) {
        latestUpdates[event.conversationId] = event
        if (isForeground.value) return
        val displaySetting = settingsStore.settingsFlow.value.displaySetting
        if (!displaySetting.enableNotificationOnMessageGeneration) return
        if (!displaySetting.enableLiveUpdateNotification) return
        if (lastPosted[event.conversationId] == event) return

        val now = SystemClock.elapsedRealtime()
        val interval = settingsStore.settingsFlow.value.displaySetting.liveUpdateIntervalSeconds.coerceIn(1, 10) * 1000L
        val lastSentAt = liveUpdateLastSentAt[event.conversationId]
        if (lastSentAt != null && now - lastSentAt < interval) return
        liveUpdateLastSentAt[event.conversationId] = now

        sendLiveUpdateNotification(event.conversationId, event.lastMessage, event.senderName)
        lastPosted[event.conversationId] = event
    }

    private fun handleGenerationEnded(event: AppEvent.ChatGenerationEnded) {
        latestUpdates.remove(event.conversationId)
        cancelLiveUpdateNotification(event.conversationId)

        val contentPreview = event.contentPreview ?: return
        if (isForeground.value) return
        if (!settingsStore.settingsFlow.value.displaySetting.enableNotificationOnMessageGeneration) return
        sendGenerationDoneNotification(event.conversationId, event.senderName, contentPreview)
    }

    private fun sendGenerationDoneNotification(
        conversationId: Uuid,
        senderName: String,
        contentPreview: String
    ) {
        context.sendNotification(
            channelId = CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID,
            notificationId = getDoneNotificationId(conversationId)
        ) {
            title = senderName
            content = if (settingsStore.settingsFlow.value.displaySetting.notificationContentPreview) contentPreview else "生成已完成，点击查看"
            autoCancel = true
            useDefaults = true
            category = NotificationCompat.CATEGORY_MESSAGE
            contentIntent = getPendingIntent(context, conversationId)
        }
    }

    private fun getLiveUpdateNotificationId(conversationId: Uuid): Int {
        return conversationId.hashCode() + 10000
    }

    private fun sendLiveUpdateNotification(
        conversationId: Uuid,
        lastMessage: UIMessage,
        senderName: String
    ) {
        // 确定当前状态
        val (chipText, statusText, contentText) = determineNotificationContent(lastMessage.parts)

        context.sendNotification(
            channelId = CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID,
            notificationId = getLiveUpdateNotificationId(conversationId)
        ) {
            title = senderName
            content = if (settingsStore.settingsFlow.value.displaySetting.notificationContentPreview) contentText else statusText
            subText = statusText
            ongoing = true
            onlyAlertOnce = true
            category = NotificationCompat.CATEGORY_PROGRESS
            useBigTextStyle = false
            contentIntent = getPendingIntent(context, conversationId)
            requestPromotedOngoing = true
            shortCriticalText = chipText
        }
    }

    private fun determineNotificationContent(parts: List<UIMessagePart>): Triple<String, String, String> {
        // 检查最近的 part 来确定状态
        val lastReasoning = parts.filterIsInstance<UIMessagePart.Reasoning>().lastOrNull()
        val lastTool = parts.filterIsInstance<UIMessagePart.Tool>().lastOrNull()
        val lastText = parts.filterIsInstance<UIMessagePart.Text>().lastOrNull()

        return when {
            // 正在执行工具
            lastTool != null && !lastTool.isExecuted -> {
                val toolName = lastTool.toolName.substringAfterLast("__")
                Triple(
                    context.getString(R.string.notification_live_update_chip_tool),
                    context.getString(R.string.notification_live_update_tool, toolName),
                    lastTool.input.take(100)
                )
            }
            // 正在思考（Reasoning 未结束）
            lastReasoning != null && lastReasoning.finishedAt == null -> {
                Triple(
                    context.getString(R.string.notification_live_update_chip_thinking),
                    context.getString(R.string.notification_live_update_thinking),
                    lastReasoning.reasoning.takeLast(200)
                )
            }
            // 正在写回复
            lastText != null -> {
                Triple(
                    context.getString(R.string.notification_live_update_chip_writing),
                    context.getString(R.string.notification_live_update_writing),
                    lastText.text.takeLast(200)
                )
            }
            // 默认状态
            else -> {
                Triple(
                    context.getString(R.string.notification_live_update_chip_writing),
                    context.getString(R.string.notification_live_update_title),
                    ""
                )
            }
        }
    }

    private fun cancelLiveUpdateNotification(conversationId: Uuid) {
        lastPosted.remove(conversationId)
        liveUpdateLastSentAt.remove(conversationId)
        context.cancelNotification(getLiveUpdateNotificationId(conversationId))
    }

    private fun getDoneNotificationId(conversationId: Uuid): Int = conversationId.hashCode() + 20_000

    private fun clearAllLiveUpdateNotifications() {
        liveUpdateLastSentAt.keys.toList().forEach { cancelLiveUpdateNotification(it) }
    }

    private fun getPendingIntent(context: Context, conversationId: Uuid): PendingIntent {
        val intent = Intent(context, RouteActivity::class.java).apply {
            data = android.net.Uri.parse("rikkahub-notification://conversation/$conversationId")
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("conversationId", conversationId.toString())
        }
        return PendingIntent.getActivity(
            context,
            conversationId.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }
}
