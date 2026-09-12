package me.rerere.rikkahub.ui.theme

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.isGlobalBackgroundActive
import me.rerere.rikkahub.data.datastore.AdvancedAppearanceSetting

internal object BackgroundAccentState {
    var loading by mutableStateOf(false)
    var failed by mutableStateOf(false)
    var retry by mutableIntStateOf(0)
}

internal fun AdvancedAppearanceSetting.withExtractedBackgroundAccent(source: String, color: Long): AdvancedAppearanceSetting =
    if (enableGlobalBackground && enableAutoAccent && globalBackground == source && autoAccentColorArgb == null) copy(autoAccentColorArgb = color) else this

/** Keep extraction alive across navigation, and retry after an app restart if no color was saved. */
@Composable
internal fun MaintainBackgroundAccent(settings: Settings, store: SettingsStore) {
    val context = LocalContext.current.applicationContext
    val appearance = settings.advancedAppearanceSetting
    LaunchedEffect(appearance.globalBackground, appearance.enableAutoAccent,
        settings.isGlobalBackgroundActive(), appearance.autoAccentColorArgb, BackgroundAccentState.retry) {
        BackgroundAccentState.failed = false
        if (!appearance.enableAutoAccent || !settings.isGlobalBackgroundActive() || appearance.autoAccentColorArgb != null) return@LaunchedEffect
        val source = appearance.globalBackground ?: return@LaunchedEffect
        BackgroundAccentState.loading = true
        try {
            val color = extractBackgroundAccent(context, source)
            if (color == null) BackgroundAccentState.failed = true
            else store.updateAdvancedAppearance { current ->
                current.withExtractedBackgroundAccent(source, color)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { BackgroundAccentState.failed = true }
        finally { BackgroundAccentState.loading = false }
    }
}
