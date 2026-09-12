package me.rerere.rikkahub.ui.components.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import me.rerere.rikkahub.data.model.LiquidGlassSettings
import me.rerere.rikkahub.data.model.LiquidGlassRenderer

internal data class GlassRenderProfile(val refractionEnabled: Boolean, val blurLimit: Float, val opticalScale: Float)

internal const val MAX_REFRACTION_AREA_DP = 180000f
internal const val MAX_REFRACTION_SURFACES = 3

internal fun needsGlassRenderSlot(glass: Boolean, blurRadius: Float, sdk: Int, renderer: LiquidGlassRenderer,
    refraction: Float, areaDp: Float, runtimeFailed: Boolean): Boolean =
    blurRadius > 0f || (glass && sdk >= 33 && renderer != LiquidGlassRenderer.STANDARD && refraction > 0f &&
        areaDp > 0f && areaDp <= MAX_REFRACTION_AREA_DP && !runtimeFailed)

internal fun resolveGlassProfile(settings: LiquidGlassSettings, sdk: Int, runtimeFailed: Boolean, busy: Boolean,
    powerSave: Boolean, areaDp: Float, slot: Int, blurLimit: Float): GlassRenderProfile {
    val reduced = settings.adaptivePerformance && (busy || powerSave)
    return GlassRenderProfile(
        refractionEnabled = sdk >= 33 && !runtimeFailed && settings.renderer != LiquidGlassRenderer.STANDARD &&
            !reduced && areaDp > 0 && areaDp <= MAX_REFRACTION_AREA_DP && slot in 0 until MAX_REFRACTION_SURFACES,
        blurLimit = if (reduced) minOf(blurLimit, if (powerSave) 4f else 8f) else blurLimit,
        opticalScale = if (reduced) .6f else if (sdk < 33) .7f else 1f,
    )
}

@Composable
internal fun rememberGlassPowerSave(): Boolean {
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    fun current() = context.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true
    var saving by remember { mutableStateOf(current()) }
    DisposableEffect(context, lifecycle) {
        val receiver = object : BroadcastReceiver() { override fun onReceive(context: Context?, intent: Intent?) { saving = current() } }
        val registered = runCatching { ContextCompat.registerReceiver(context, receiver, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED) }.isSuccess
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) saving = current() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); if (registered) runCatching { context.unregisterReceiver(receiver) } }
    }
    return saving
}

internal val LocalGlassPowerSave = compositionLocalOf { false }
internal val LocalGlassBusy = compositionLocalOf { false }
