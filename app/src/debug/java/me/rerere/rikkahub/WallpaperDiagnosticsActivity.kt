package me.rerere.rikkahub

import android.app.WallpaperManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.compositeOver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import me.rerere.rikkahub.ui.theme.backgroundSamplesWithOverlay
import me.rerere.rikkahub.ui.theme.backgroundTextSeed
import me.rerere.rikkahub.ui.theme.extractBackgroundSamples
import me.rerere.rikkahub.ui.theme.extractBackgroundBitmapAnalysis
import me.rerere.rikkahub.ui.theme.textForegroundFor
import me.rerere.rikkahub.ui.theme.wcagContrastRatio
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Read-only app-UID diagnostics. Does not change wallpapers, permissions or real preferences. */
class WallpaperDiagnosticsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch(Dispatchers.Default) {
            val result = JSONObject()
            val manager = WallpaperManager.getInstance(this@WallpaperDiagnosticsActivity)
            val system = manager.getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
            fun hex(value: Int?) = value?.toUInt()?.toString(16)
            result.put("api", Build.VERSION.SDK_INT)
            result.put("manufacturer", Build.MANUFACTURER)
            result.put("model", Build.MODEL)
            result.put("systemPrimary", hex(system?.primaryColor?.toArgb()))
            result.put("systemSecondary", hex(system?.secondaryColor?.toArgb()))
            result.put("systemTertiary", hex(system?.tertiaryColor?.toArgb()))
            result.put("systemColorHints", system?.colorHints)
            result.put("wallpaperId", manager.getWallpaperId(WallpaperManager.FLAG_SYSTEM))
            result.put("allFilesAccess", Environment.isExternalStorageManager())
            result.put("inversion", Settings.Secure.getInt(contentResolver, "accessibility_display_inversion_enabled", 0))
            result.put("daltonizer", Settings.Secure.getInt(contentResolver, "accessibility_display_daltonizer_enabled", 0))
            result.put("highTextContrast", Settings.Secure.getInt(contentResolver, "high_text_contrast_enabled", 0))
            try {
                @Suppress("DEPRECATION")
                val file = manager.getWallpaperFile(WallpaperManager.FLAG_SYSTEM)
                result.put("systemBitmapAccess", if (file == null) "null" else "readable")
                file?.close()
            } catch (e: SecurityException) {
                result.put("systemBitmapAccess", e.javaClass.simpleName)
            }
            val source = File(filesDir, "diagnostic-background")
            if (source.isFile) {
                val base = Color(0xFF15111A)
                val firstReadStarted = System.nanoTime()
                val raw = extractBackgroundSamples(this@WallpaperDiagnosticsActivity, source.toURI().toString(), 1f, base).orEmpty()
                result.put("firstReadMs", (System.nanoTime() - firstReadStarted) / 1_000_000.0)
                val samples = backgroundSamplesWithOverlay(raw, base, .28f, .42f)
                val started = System.nanoTime()
                val analyzed = extractBackgroundBitmapAnalysis(this@WallpaperDiagnosticsActivity, source.toURI().toString())
                result.put("analysisCacheReused", analyzed === extractBackgroundBitmapAnalysis(this@WallpaperDiagnosticsActivity, source.toURI().toString()))
                result.put("cachedReadMs", (System.nanoTime() - started) / 1_000_000.0)
                val appSeed = analyzed?.seedArgb ?: backgroundTextSeed(raw)
                result.put("sampleCount", samples.size)
                result.put("appSeed", hex(appSeed))
                result.put("rawSamples", JSONArray(raw.map { it.toArgb() }))
                val rows = JSONArray()
                for (seed in listOf(appSeed, system?.primaryColor?.toArgb())) {
                    for (surface in listOf(Color.Transparent, Color(0xFF413738).copy(alpha = .35f))) {
                        val painted = samples.map { surface.compositeOver(it) }
                        val foreground = textForegroundFor(painted, seed)
                        rows.put(JSONObject().put("seed", hex(seed)).put("surface", hex(surface.toArgb()))
                            .put("foreground", hex(foreground.toArgb()))
                            .put("minContrast", painted.minOfOrNull { wcagContrastRatio(foreground, it) }))
                    }
                }
                result.put("renderedPalette", rows)
            }
            File(filesDir, "wallpaper-diagnostic.json").writeText(result.toString(2))
        }
    }
}
