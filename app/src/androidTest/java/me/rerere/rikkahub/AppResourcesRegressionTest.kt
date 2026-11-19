package me.rerere.rikkahub

import android.content.res.Configuration
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.ui.hooks.currentAppLocale
import me.rerere.rikkahub.ui.hooks.rememberAppDateFormat
import me.rerere.rikkahub.ui.hooks.rememberAppDateTimeFormatter
import me.rerere.rikkahub.ui.hooks.rememberAppResources
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.util.Date
import java.util.Locale

class AppResourcesRegressionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun languageSwitchUpdatesInFlightCallbackAndFormattersWithoutRestartingWork() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val english = Configuration(context.resources.configuration).apply { setLocale(Locale.ENGLISH) }
        val chinese = Configuration(context.resources.configuration).apply { setLocale(Locale.SIMPLIFIED_CHINESE) }
        var configuration by mutableStateOf(english)
        var completion: (() -> String)? = null
        var starts = 0
        var month = ""
        var dateMonth = ""
        var language = ""
        compose.setContent {
            val selectedResources = remember(configuration) { context.createConfigurationContext(configuration).resources }
            CompositionLocalProvider(LocalConfiguration provides configuration, LocalResources provides selectedResources) {
                val resources by rememberAppResources()
                val locale = currentAppLocale()
                val formatter = rememberAppDateFormat("MMMM")
                val dateFormatter = rememberAppDateTimeFormatter("MMMM")
                LaunchedEffect(Unit) { starts++; completion = { resources.getString(R.string.cancel) } }
                SideEffect {
                    language = locale.language
                    month = formatter.format(Date(1704067200000L))
                    dateMonth = dateFormatter.format(LocalDate.of(2024, 1, 1))
                }
            }
        }
        compose.waitForIdle()
        val before = requireNotNull(completion).invoke()
        val firstMonth = month
        val firstDateMonth = dateMonth
        compose.runOnIdle { configuration = chinese }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(1, starts)
            assertEquals("zh", language)
            assertNotEquals(before, requireNotNull(completion).invoke())
            assertEquals(context.createConfigurationContext(chinese).resources.getString(R.string.cancel), completion!!.invoke())
            assertNotEquals(firstMonth, month)
            assertNotEquals(firstDateMonth, dateMonth)
        }
    }
}
