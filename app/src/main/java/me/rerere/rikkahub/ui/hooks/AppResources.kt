package me.rerere.rikkahub.ui.hooks

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import java.text.SimpleDateFormat
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Read this state when an event completes, without restarting the event on language changes. */
@Composable
internal fun rememberAppResources(): State<Resources> = rememberUpdatedState(LocalResources.current)

@Composable
internal fun currentAppLocale(): Locale {
    val locales = LocalConfiguration.current.locales
    return if (locales.isEmpty) Locale.ROOT else locales[0]
}

@Composable
internal fun rememberAppDateFormat(pattern: String): SimpleDateFormat {
    val locale = currentAppLocale()
    return remember(pattern, locale) { SimpleDateFormat(pattern, locale) }
}

@Composable
internal fun rememberAppDateTimeFormatter(pattern: String): DateTimeFormatter {
    val locale = currentAppLocale()
    return remember(pattern, locale) { DateTimeFormatter.ofPattern(pattern, locale) }
}
