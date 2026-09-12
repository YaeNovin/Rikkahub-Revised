package me.rerere.rikkahub.ui.components.message

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.ui.AskUserInteraction

val LocalAskUserDraftWriter = staticCompositionLocalOf<((String, String, JsonObject, Set<String>) -> Unit)?> { null }
val LocalAskUserDraftScope = staticCompositionLocalOf { "" }

internal fun askUserClock(context: Context): AskUserInteraction.Clock = AskUserInteraction.Clock(
    System.currentTimeMillis(), SystemClock.elapsedRealtime(),
    runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT) }.getOrDefault(-1),
)
