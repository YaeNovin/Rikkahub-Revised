package me.rerere.rikkahub.ui.components.webview

import android.app.ActivityManager
import android.content.Context
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.webkit.WebView

// NONE inherits the accelerated window; forcing HARDWARE adds an unnecessary
// whole-WebView texture. Only detached bitmap-export views use SOFTWARE.
internal fun previewLayerType(exporting: Boolean): Int =
    if (exporting) View.LAYER_TYPE_SOFTWARE else View.LAYER_TYPE_NONE

internal fun WebView.notifyRenderEnvironment() {
    val lowMemory = (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.isLowRamDevice == true
    val powerSave = (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isPowerSaveMode == true
    val reduceMotion = runCatching {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }.getOrDefault(false)
    // Only native-observed booleans are passed; no input-derived JS or native bridge.
    evaluateJavascript("if (typeof window.rikkaSetRenderEnvironment === 'function') window.rikkaSetRenderEnvironment({hardwareAccelerated:$isHardwareAccelerated,lowMemory:$lowMemory,powerSave:$powerSave,reduceMotion:$reduceMotion});", null)
}
