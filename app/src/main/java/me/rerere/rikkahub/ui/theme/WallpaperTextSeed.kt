package me.rerere.rikkahub.ui.theme

import android.app.WallpaperColors
import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.material3.representativeThemeColor
import me.rerere.rikkahub.data.datastore.resolveChatBackground
import me.rerere.rikkahub.data.datastore.isGlobalBackgroundActive

internal data class WallpaperTextSeed(val argb: Int? = null, val source: String = "未启用")
private data class WallpaperSeedKey(val provided: Int?, val wallpaperId: Int?)
private val wallpaperSeeds = java.util.concurrent.ConcurrentHashMap<WallpaperSeedKey, WallpaperTextSeed>()

internal suspend fun prepareStartupAppearance(context: Context, settings: me.rerere.rikkahub.data.datastore.Settings) {
    val appearance = settings.advancedAppearanceSetting
    if (appearance.textColorMode == me.rerere.rikkahub.data.datastore.TextColorMode.SYSTEM_WALLPAPER) {
        val manager = context.getSystemService(WallpaperManager::class.java)
        val key = WallpaperSeedKey(appearance.wallpaperTextSeedArgb?.toInt(), appearance.wallpaperTextSeedWallpaperId)
        wallpaperSeeds[key] = readWallpaperTextSeed(context, manager, providedSeed = key.provided, providedWallpaperId = key.wallpaperId)
    }
    val size = context.resources.displayMetrics
    val aspect = size.widthPixels.toFloat() / size.heightPixels.coerceAtLeast(1)
    val chat = settings.resolveChatBackground()
    listOfNotNull(appearance.globalBackground.takeIf { settings.isGlobalBackgroundActive() },
        chat.background.takeIf { chat.isActive && !chat.useGradientBackground }).distinct().forEach { source ->
        // Network images must never keep the starting window waiting for a socket timeout.
        if (!source.startsWith("http", true)) runCatching {
            extractBackgroundBitmapAnalysis(context, source, aspectRatio = aspect)
        }
    }
}

/** Metadata requires no storage permission; provided originals use shared MCU analysis. */
@Composable
internal fun rememberWallpaperTextSeed(enabled: Boolean, providedSeed: Int? = null,
    providedWallpaperId: Int? = null): WallpaperTextSeed {
    val context = LocalContext.current.applicationContext
    val configuration = LocalConfiguration.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    var result by remember(context, enabled, providedSeed, providedWallpaperId) {
        mutableStateOf(if (enabled) wallpaperSeeds[WallpaperSeedKey(providedSeed, providedWallpaperId)]
            ?: WallpaperTextSeed(source = "正在读取系统配色…") else WallpaperTextSeed())
    }
    DisposableEffect(context, lifecycle, configuration, enabled, providedSeed, providedWallpaperId) {
        val manager = runCatching { context.getSystemService(WallpaperManager::class.java) }.getOrNull()
        var refreshJob: Job? = null
        fun refresh(colors: WallpaperColors? = null) {
            if (!enabled) return
            refreshJob?.cancel()
            refreshJob = scope.launch {
                // Some ROMs broadcast before their color service has finished.
                for (attempt in 0..2) {
                    if (attempt > 0) delay(if (attempt == 1) 300L else 900L)
                    val next = withContext(Dispatchers.IO) {
                        readWallpaperTextSeed(context, manager, if (attempt == 0) colors else null,
                            providedSeed, providedWallpaperId)
                    }
                    result = next
                    if (wallpaperSeeds.size > 8) wallpaperSeeds.clear()
                    wallpaperSeeds[WallpaperSeedKey(providedSeed, providedWallpaperId)] = next
                    if (next.source.startsWith("系统壁纸配色") || next.source.startsWith("壁纸原图")) break
                }
            }
        }
        val listener = if (Build.VERSION.SDK_INT >= 27) WallpaperManager.OnColorsChangedListener { colors, which ->
            if (which and WallpaperManager.FLAG_SYSTEM != 0) refresh(colors)
        } else null
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) { refresh() }
        }
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh() }
        val registered = enabled && Build.VERSION.SDK_INT >= 27 && listener != null && runCatching {
            manager?.addOnColorsChangedListener(listener, Handler(Looper.getMainLooper()))
            manager != null
        }.getOrDefault(false)
        @Suppress("DEPRECATION")
        val broadcastRegistered = enabled && runCatching {
            ContextCompat.registerReceiver(context, receiver, IntentFilter(Intent.ACTION_WALLPAPER_CHANGED),
                ContextCompat.RECEIVER_EXPORTED)
            true
        }.getOrDefault(false)
        lifecycle.addObserver(observer)
        refresh()
        onDispose {
            refreshJob?.cancel()
            lifecycle.removeObserver(observer)
            if (registered && Build.VERSION.SDK_INT >= 27 && listener != null) runCatching { manager?.removeOnColorsChangedListener(listener) }
            if (broadcastRegistered) runCatching { context.unregisterReceiver(receiver) }
        }
    }
    return result
}

internal fun readWallpaperTextSeed(
    context: Context,
    manager: WallpaperManager?,
    updatedColors: WallpaperColors? = null,
    providedSeed: Int? = null,
    providedWallpaperId: Int? = null,
): WallpaperTextSeed {
    val currentId = runCatching { manager?.getWallpaperId(WallpaperManager.FLAG_SYSTEM) }.getOrNull()
    val colors = if (Build.VERSION.SDK_INT >= 27) updatedColors ?: runCatching {
        manager?.getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
    }.getOrNull() else null
    val candidates = colors?.let { listOfNotNull(it.primaryColor, it.secondaryColor, it.tertiaryColor)
        .map { color -> color.toArgb() } }.orEmpty()
    val systemAccent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        runCatching { context.getColor(android.R.color.system_accent1_500) }.getOrNull()
    } else null
    return resolveWallpaperTextSeed(candidates, systemAccent, providedSeed, providedWallpaperId, currentId)
}

internal fun resolveWallpaperTextSeed(wallpaperArgb: Int?, systemArgb: Int?): WallpaperTextSeed =
    resolveWallpaperTextSeed(listOfNotNull(wallpaperArgb), systemArgb)

internal fun resolveWallpaperTextSeed(wallpaperColors: List<Int>, systemArgb: Int?,
    providedSeed: Int? = null, providedWallpaperId: Int? = null, currentWallpaperId: Int? = null): WallpaperTextSeed {
    val originalMatches = providedWallpaperId == null || providedWallpaperId <= 0 ||
        currentWallpaperId == null || currentWallpaperId <= 0 || providedWallpaperId == currentWallpaperId
    if (providedSeed != null && (providedSeed ushr 24) == 255 && originalMatches) {
        return WallpaperTextSeed(providedSeed, "壁纸原图取色（更换壁纸后可重新提供）")
    }
    // The API supplies order, not populations. Keep primary dominant while
    // allowing chromatic secondary/tertiary colors when primary is unsuitable.
    val population = linkedMapOf<Int, Int>()
    wallpaperColors.take(3).forEachIndexed { index, color ->
        population[color] = (population[color] ?: 0) + listOf(70, 20, 10)[index]
    }
    val seed = representativeThemeColor(population)
    val stale = if (providedSeed != null && !originalMatches) "；原图已因壁纸变化失效" else ""
    if (seed != null) return WallpaperTextSeed(seed, "系统壁纸配色$stale")
    if (systemArgb != null && (systemArgb ushr 24) == 255) {
        return WallpaperTextSeed(systemArgb, "系统动态配色（ROM 未提供壁纸颜色）$stale")
    }
    return WallpaperTextSeed(source = "ROM 未提供壁纸颜色，可提供壁纸原图；当前使用自动清晰$stale")
}
