package me.rerere.rikkahub.service

import android.app.Service
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import me.rerere.common.android.Logging
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import org.koin.android.ext.android.get
import java.util.concurrent.atomic.AtomicBoolean

/** Each lease belongs to one generation, so an older completion cannot stop another task. */
internal class GenerationLeases {
    private val count = MutableStateFlow(0)
    val activeCount = count.asStateFlow()

    @Synchronized fun acquire(): () -> Unit {
        count.value += 1
        val closed = AtomicBoolean(false)
        return {
            if (closed.compareAndSet(false, true)) synchronized(this) { count.value -= 1 }
        }
    }
}

class GenerationKeepAlive(private val context: Context,
    private val settings: me.rerere.rikkahub.data.datastore.SettingsStore,
    appScope: me.rerere.rikkahub.AppScope,
) {
    internal val leases = GenerationLeases()
    private val main = Handler(Looper.getMainLooper())
    private val problem = MutableStateFlow<String?>(null)
    val statusProblem = problem.asStateFlow()

    init {
        appScope.launch {
            settings.settingsFlow.map { it.displaySetting.backgroundGenerationProtection }.distinctUntilChanged().collect { enabled ->
                if (!enabled) {
                    context.stopService(Intent(context, GenerationKeepAliveService::class.java))
                } else if (leases.activeCount.value > 0 && ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) start()
            }
        }
        main.post {
            ProcessLifecycleOwner.get().lifecycle.addObserver(LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_START && leases.activeCount.value > 0) start()
            })
        }
    }

    fun track(job: Job) {
        if (job.isCompleted) return
        val release = leases.acquire()
        // Start while the user is still in the app, not when ON_STOP arrives too late.
        if (Looper.myLooper() == Looper.getMainLooper()) start() else main.post { start() }
        job.invokeOnCompletion { release() }
    }

    private fun start() {
        if (!settings.settingsFlow.value.displaySetting.backgroundGenerationProtection) return
        if (leases.activeCount.value == 0) return
        try {
            ContextCompat.startForegroundService(context, Intent(context, GenerationKeepAliveService::class.java))
        } catch (e: Exception) { report(e) }
    }

    internal fun report(error: Exception) {
        problem.value = "系统限制了后台生成保护，请保持应用前台或检查电池设置。"
        Logging.logSoftwareError("GenerationKeepAlive", "foreground protection", error)
    }

    internal fun started() { problem.value = null }
}

internal const val GENERATION_CHANNEL = "background_generation"
internal const val GENERATION_NOTIFICATION_ID = 42017

internal fun generationNotification(context: Context, count: Int, video: Boolean = false): android.app.Notification {
    val manager = context.getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(NotificationChannel(GENERATION_CHANNEL, "后台生成", NotificationManager.IMPORTANCE_LOW))
    val open = PendingIntent.getActivity(context, 42017,
        Intent(context, RouteActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    return NotificationCompat.Builder(context, GENERATION_CHANNEL)
        .setSmallIcon(R.drawable.small_icon)
        .setContentTitle(if (video) "正在下载生成的视频" else "正在生成内容")
        .setContentText(if (video) "下载完成后自动结束" else "$count 个任务正在运行")
        .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
        .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .build()
}

class GenerationKeepAliveService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observer: Job? = null
    private var renewal: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val keeper: GenerationKeepAlive by lazy { get() }
    private val settings: me.rerere.rikkahub.data.datastore.SettingsStore by lazy { get() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            ServiceCompat.startForeground(this, GENERATION_NOTIFICATION_ID,
                generationNotification(this, keeper.leases.activeCount.value),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
            keeper.started()
        } catch (e: Exception) {
            keeper.report(e)
            stopSelf()
            return START_NOT_STICKY
        }
        observer?.cancel()
        observer = scope.launch {
            keeper.leases.activeCount.collect { count ->
                if (count == 0) {
                    releaseWakeLock()
                    stopSelfResult(startId)
                } else {
                    getSystemService(NotificationManager::class.java).notify(GENERATION_NOTIFICATION_ID,
                        generationNotification(this@GenerationKeepAliveService, count))
                }
            }
        }
        if (renewal == null) renewal = scope.launch {
            try {
                val power = getSystemService(PowerManager::class.java)
                val lock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:generation")
                    .apply { setReferenceCounted(false) }
                wakeLock = lock
                while (isActive && keeper.leases.activeCount.value > 0) {
                    if (settings.settingsFlow.value.displaySetting.generationWakeLock) {
                        lock.acquire(90_000L)
                    } else if (lock.isHeld) lock.release()
                    delay(60_000L)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { keeper.report(e) }
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        keeper.report(IllegalStateException("Android foreground dataSync time limit reached"))
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun releaseWakeLock() {
        renewal?.cancel()
        renewal = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    override fun onDestroy() {
        scope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }
}
