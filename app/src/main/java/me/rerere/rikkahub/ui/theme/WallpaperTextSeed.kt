package me.rerere.rikkahub.ui.theme

import android.app.WallpaperColors
import android.app.WallpaperManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class WallpaperTextSeed(val argb: Int? = null, val source: String = "未启用")

/** Uses system color metadata, never requests access to the wallpaper image or storage. */
@Composable
internal fun rememberWallpaperTextSeed(enabled: Boolean): WallpaperTextSeed {
    if (!enabled) return WallpaperTextSeed()
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) return WallpaperTextSeed(source = "此系统无法提供壁纸配色，使用自动清晰")
    return rememberSupportedWallpaperTextSeed()
}

@RequiresApi(Build.VERSION_CODES.O_MR1)
@Composable
private fun rememberSupportedWallpaperTextSeed(): WallpaperTextSeed {
    val context = LocalContext.current.applicationContext
    val configuration = LocalConfiguration.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    var result by remember(context) { mutableStateOf(WallpaperTextSeed(source = "正在读取系统配色…")) }
    DisposableEffect(context, lifecycle, configuration) {
        val manager = context.getSystemService(WallpaperManager::class.java)
        var refreshJob: Job? = null
        fun refresh(colors: WallpaperColors? = null) {
            refreshJob?.cancel()
            refreshJob = scope.launch {
                result = withContext(Dispatchers.IO) { readWallpaperTextSeed(context, manager, colors) }
            }
        }
        val listener = WallpaperManager.OnColorsChangedListener { colors, which ->
            if (which and WallpaperManager.FLAG_SYSTEM != 0) refresh(colors)
        }
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh() }
        val registered = runCatching {
            manager?.addOnColorsChangedListener(listener, Handler(Looper.getMainLooper()))
            manager != null
        }.getOrDefault(false)
        lifecycle.addObserver(observer)
        refresh()
        onDispose {
            refreshJob?.cancel()
            lifecycle.removeObserver(observer)
            if (registered) runCatching { manager?.removeOnColorsChangedListener(listener) }
        }
    }
    return result
}

@RequiresApi(Build.VERSION_CODES.O_MR1)
private fun readWallpaperTextSeed(
    context: Context,
    manager: WallpaperManager?,
    updatedColors: WallpaperColors?,
): WallpaperTextSeed {
    val colors = updatedColors ?: runCatching {
        manager?.getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
    }.getOrNull()
    val systemAccent = if (colors == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        runCatching { context.getColor(android.R.color.system_accent1_500) }.getOrNull()
    } else null
    return resolveWallpaperTextSeed(colors?.primaryColor?.toArgb(), systemAccent)
}

internal fun resolveWallpaperTextSeed(wallpaperArgb: Int?, systemArgb: Int?): WallpaperTextSeed = when {
    wallpaperArgb != null -> WallpaperTextSeed(wallpaperArgb, "系统壁纸配色")
    systemArgb != null -> WallpaperTextSeed(systemArgb, "系统动态配色（壁纸颜色不可用）")
    else -> WallpaperTextSeed(source = "系统未提供壁纸颜色，使用自动清晰")
}
